from __future__ import annotations

import asyncio
import logging
import subprocess
from pathlib import Path
from typing import Any

from app.config import (
    BASE_DIR,
    BGM_DIR,
    CLEANUP_TEMP_AFTER_SUCCESS,
    DEFAULT_FPS,
    FONT_DIR,
    JOB_TIMEOUT_SECONDS,
    SUPPORTED_RESOLUTIONS,
)
from app.ffmpeg_utils import get_ffmpeg_path, is_image, is_video, run_ffmpeg, run_ffprobe_duration
from app.jobs import JobStatus, update_job
from app.keyword_overlay import build_keyword_overlay_filter_chain, validate_keyword_overlays
from app.schemas import CreateJobRequest
from app.storage import (
    cleanup_job_temp_dir,
    get_output_path,
    get_upload_path_by_file_id,
    make_job_temp_dir,
)
from app.subtitle import generate_ass_subtitle, split_script_to_subtitle_events, subtitle_max_chars_per_line, wrap_subtitle_text
from app.templates import load_template
from app.tts import synthesize_speech

logger = logging.getLogger(__name__)

SUPPORTED_BGM_EXTENSIONS = {".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac"}
_FILTER_CACHE: dict[str, bool] = {}


class WorkerOwnershipLostError(RuntimeError):
    pass


def _update_render_job(job_id: str, worker_id: str | None = None, **kwargs) -> None:
    job = update_job(job_id, expected_worker_id=worker_id, **kwargs)
    if worker_id is not None and job is None:
        raise WorkerOwnershipLostError(f"Worker lost ownership for job {job_id}")


def _parse_resolution(resolution: str) -> tuple[int, int]:
    return SUPPORTED_RESOLUTIONS[resolution]


def _public_error(exc: Exception) -> str:
    message = str(exc)
    return message.replace(str(BASE_DIR), "<project>")


def _filter_path(path: Path) -> str:
    return path.as_posix().replace("\\", "\\\\").replace(":", "\\:").replace(",", "\\,")


def _drawtext_path(path: Path) -> str:
    return path.as_posix().replace("\\", "\\\\").replace(":", "\\:").replace("'", r"\'")


def _ffmpeg_has_filter(name: str) -> bool:
    if name in _FILTER_CACHE:
        return _FILTER_CACHE[name]
    ffmpeg_path = get_ffmpeg_path() or "ffmpeg"
    try:
        result = subprocess.run(
            [ffmpeg_path, "-hide_banner", "-filters"],
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )
    except (OSError, subprocess.TimeoutExpired):
        _FILTER_CACHE[name] = False
        return False
    available = result.returncode == 0 and any(
        line.split()[1:2] == [name] for line in result.stdout.splitlines() if line.split()
    )
    _FILTER_CACHE[name] = available
    return available


async def _run_ffmpeg(cmd: list[str], timeout: int = JOB_TIMEOUT_SECONDS) -> None:
    await asyncio.to_thread(run_ffmpeg, cmd, timeout)


async def _probe_duration(path: Path) -> float:
    return await asyncio.to_thread(run_ffprobe_duration, path)


def _standard_video_filter(
    width: int,
    height: int,
    fps: int,
    duration: float,
    transition: str,
) -> str:
    filters = [
        f"scale={width}:{height}:force_original_aspect_ratio=increase",
        f"crop={width}:{height}",
        f"fps={fps}",
        "setsar=1",
    ]
    _add_fade_filters(filters, duration, transition)
    filters.append("format=yuv420p")
    return ",".join(filters)


def _image_video_filter(
    width: int,
    height: int,
    fps: int,
    duration: float,
    image_motion: str,
    transition: str,
) -> str:
    filters = [
        f"scale={width}:{height}:force_original_aspect_ratio=increase",
        f"crop={width}:{height}",
        "setsar=1",
    ]
    if image_motion == "slow_zoom":
        filters.append(
            "zoompan="
            "z='min(zoom+0.0015,1.08)':"
            "d=1:"
            "x='iw/2-(iw/zoom/2)':"
            "y='ih/2-(ih/zoom/2)':"
            f"s={width}x{height}:"
            f"fps={fps}"
        )
    else:
        filters.append(f"fps={fps}")
    _add_fade_filters(filters, duration, transition)
    filters.append("format=yuv420p")
    return ",".join(filters)


def _add_fade_filters(filters: list[str], duration: float, transition: str) -> None:
    if transition != "fade" or duration <= 0.55:
        return
    fade_duration = min(0.25, max(0.08, duration / 6))
    fade_out_start = max(0.0, duration - fade_duration)
    filters.append(f"fade=t=in:st=0:d={fade_duration:.3f}")
    filters.append(f"fade=t=out:st={fade_out_start:.3f}:d={fade_duration:.3f}")


def _segment_common_output_args(fps: int, output_path: Path) -> list[str]:
    return [
        "-an",
        "-c:v",
        "libx264",
        "-preset",
        "veryfast",
        "-crf",
        "20",
        "-r",
        str(fps),
        "-pix_fmt",
        "yuv420p",
        "-video_track_timescale",
        str(max(1000, fps * 1000)),
        str(output_path),
    ]


def _build_image_segment_cmd(
    input_path: Path,
    output_path: Path,
    duration: float,
    width: int,
    height: int,
    fps: int,
    image_motion: str,
    transition: str,
) -> list[str]:
    vf = _image_video_filter(width, height, fps, duration, image_motion, transition)
    return [
        "ffmpeg",
        "-y",
        "-loop",
        "1",
        "-i",
        str(input_path),
        "-t",
        f"{duration:.3f}",
        "-vf",
        vf,
        *_segment_common_output_args(fps, output_path),
    ]


def _build_video_segment_cmd(
    input_path: Path,
    output_path: Path,
    duration: float,
    width: int,
    height: int,
    fps: int,
    transition: str,
) -> list[str]:
    vf = _standard_video_filter(width, height, fps, duration, transition)
    return [
        "ffmpeg",
        "-y",
        "-stream_loop",
        "-1",
        "-i",
        str(input_path),
        "-t",
        f"{duration:.3f}",
        "-vf",
        vf,
        *_segment_common_output_args(fps, output_path),
    ]


def _write_concat_file(segment_paths: list[Path], concat_path: Path) -> None:
    lines = []
    for segment in segment_paths:
        escaped = segment.as_posix().replace("'", "'\\''")
        lines.append(f"file '{escaped}'")
    concat_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _build_concat_cmd(concat_path: Path, output_path: Path) -> list[str]:
    return [
        "ffmpeg",
        "-y",
        "-f",
        "concat",
        "-safe",
        "0",
        "-i",
        str(concat_path),
        "-c",
        "copy",
        str(output_path),
    ]


def _build_subtitle_cmd(input_path: Path, subtitle_path: Path, output_path: Path) -> list[str]:
    vf = f"subtitles=filename={_filter_path(subtitle_path)}:fontsdir={_filter_path(FONT_DIR)}"
    return [
        "ffmpeg",
        "-y",
        "-i",
        str(input_path),
        "-vf",
        vf,
        "-an",
        "-c:v",
        "libx264",
        "-preset",
        "veryfast",
        "-crf",
        "20",
        "-pix_fmt",
        "yuv420p",
        str(output_path),
    ]


def _build_drawtext_subtitle_cmd(
    input_path: Path,
    output_path: Path,
    script: list[str],
    total_duration: float,
    temp_dir: Path,
    width: int,
    height: int,
    template_config: dict[str, Any],
) -> list[str]:
    font_file = _find_font_file()
    if font_file is None:
        raise RuntimeError("No usable font file found for drawtext subtitles")

    subtitle_config = template_config.get("subtitle", {})
    scale_x = width / 1080
    scale_y = height / 1920
    font_size = max(24, int(subtitle_config.get("font_size", 83) * scale_x))
    y = int(subtitle_config.get("y", int(height * 0.82)) * scale_y)
    max_chars = subtitle_max_chars_per_line(width, font_size)
    line_spacing = max(6, int(font_size * 0.12))
    bottom_padding = max(90, int(height * 0.065))
    events = split_script_to_subtitle_events(script, total_duration)
    filters: list[str] = []
    for index, (start, end, text) in enumerate(events):
        text_file = temp_dir / f"subtitle_{index:03d}.txt"
        wrapped_text = wrap_subtitle_text(text, max_chars)
        text_file.write_text(wrapped_text, encoding="utf-8")
        line_count = max(1, wrapped_text.count("\n") + 1)
        text_height = font_size * line_count + line_spacing * max(0, line_count - 1)
        safe_y = max(int(height * 0.55), min(y, height - bottom_padding - text_height))
        filters.append(
            "drawtext="
            f"fontfile='{_drawtext_path(font_file)}':"
            f"textfile='{_drawtext_path(text_file)}':"
            f"fontsize={font_size}:"
            "fontcolor=white:"
            f"borderw={int(subtitle_config.get('outline', 3))}:"
            "bordercolor=black@0.90:"
            "shadowx=2:shadowy=2:shadowcolor=black@0.45:"
            f"line_spacing={line_spacing}:"
            r"x=max(40\,(w-text_w)/2):"
            f"y={safe_y}:"
            f"enable='between(t,{start:.3f},{end:.3f})'"
        )
    vf = ",".join(filters) if filters else "null"
    return [
        "ffmpeg",
        "-y",
        "-i",
        str(input_path),
        "-vf",
        vf,
        "-an",
        "-c:v",
        "libx264",
        "-preset",
        "veryfast",
        "-crf",
        "20",
        "-pix_fmt",
        "yuv420p",
        str(output_path),
    ]


def _find_font_file() -> Path | None:
    for pattern in ("*.ttf", "*.otf", "*.ttc"):
        for path in sorted(FONT_DIR.glob(pattern)):
            if path.is_file():
                return path

    candidates = [
        Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
        Path("/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf"),
        Path("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"),
        Path("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"),
        Path("/usr/share/fonts/truetype/arphic/uming.ttc"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
    ]
    for path in candidates:
        if path.exists():
            return path
    return None


def _wrap_title(title: str, max_chars_per_line: int) -> str:
    text = title.strip()
    if not text:
        return ""
    max_chars = max(4, max_chars_per_line)
    return "\n".join(text[index : index + max_chars] for index in range(0, len(text), max_chars))


def _build_title_cmd(
    input_path: Path,
    output_path: Path,
    title_file: Path,
    width: int,
    height: int,
    template_config: dict[str, Any],
) -> list[str]:
    title_config = template_config.get("title", {})
    scale_x = width / 1080
    scale_y = height / 1920
    font_size = max(24, int(title_config.get("font_size", 72) * scale_x))
    y = int(title_config.get("y", 120) * scale_y)
    font_file = _find_font_file()
    options = []
    if font_file is not None:
        options.append(f"fontfile={_filter_path(font_file)}")
    options.extend(
        [
            f"textfile={_filter_path(title_file)}",
            f"fontsize={font_size}",
            "fontcolor=white",
            "borderw=4",
            "bordercolor=black@0.85",
            "shadowx=2",
            "shadowy=2",
            "shadowcolor=black@0.45",
            "line_spacing=12",
            "x=(w-text_w)/2",
            f"y={y}",
        ]
    )
    vf = "drawtext=" + ":".join(options)
    return [
        "ffmpeg",
        "-y",
        "-i",
        str(input_path),
        "-vf",
        vf,
        "-an",
        "-c:v",
        "libx264",
        "-preset",
        "veryfast",
        "-crf",
        "20",
        "-pix_fmt",
        "yuv420p",
        str(output_path),
    ]


def _build_keyword_overlay_cmd(
    input_path: Path,
    output_path: Path,
    request: CreateJobRequest,
    segment_durations: list[float],
    temp_dir: Path,
    width: int,
    height: int,
    fps: int,
) -> list[str] | None:
    total_duration = sum(max(0.1, duration) for duration in segment_durations)
    validated_overlays = validate_keyword_overlays(
        request.keyword_overlays,
        len(segment_durations),
        segment_durations,
        total_duration,
    )
    if request.keyword_overlays and not validated_overlays:
        logger.warning("All keyword overlays were rejected by validation; continuing without overlays")
    vf = build_keyword_overlay_filter_chain(
        validated_overlays,
        temp_dir,
        width,
        height,
        _find_font_file(),
    )
    if not vf:
        return None
    return [
        "ffmpeg",
        "-y",
        "-i",
        str(input_path),
        "-vf",
        vf,
        "-an",
        "-c:v",
        "libx264",
        "-preset",
        "veryfast",
        "-crf",
        "20",
        "-r",
        str(fps),
        "-pix_fmt",
        "yuv420p",
        str(output_path),
    ]


def _resolve_bgm_path(filename: str | None) -> Path:
    if not filename:
        raise RuntimeError("BGM is enabled but bgm.filename is missing")
    if "\\" in filename:
        raise RuntimeError("BGM filename must be a safe relative path")
    relative_path = Path(filename)
    if relative_path.is_absolute() or any(part in {"", ".", ".."} for part in relative_path.parts):
        raise RuntimeError("BGM filename must be a safe relative path")
    path = (BGM_DIR / relative_path).resolve()
    try:
        path.relative_to(BGM_DIR.resolve())
    except ValueError as exc:
        raise RuntimeError("BGM filename is invalid") from exc
    if path.suffix.lower() not in SUPPORTED_BGM_EXTENSIONS:
        raise RuntimeError("BGM file extension is not supported")
    if not path.exists() or not path.is_file():
        raise RuntimeError(f"BGM file does not exist: {filename}")
    return path


def _build_audio_cmd(
    video_path: Path,
    voice_path: Path,
    output_path: Path,
    request: CreateJobRequest,
) -> list[str]:
    if not request.bgm.enabled:
        return [
            "ffmpeg",
            "-y",
            "-i",
            str(video_path),
            "-i",
            str(voice_path),
            "-map",
            "0:v:0",
            "-map",
            "1:a:0",
            "-c:v",
            "copy",
            "-c:a",
            "aac",
            "-b:a",
            "192k",
            "-shortest",
            "-movflags",
            "+faststart",
            str(output_path),
        ]

    bgm_path = _resolve_bgm_path(request.bgm.filename)
    volume = max(0.0, min(1.0, request.bgm.volume))
    return [
        "ffmpeg",
        "-y",
        "-i",
        str(video_path),
        "-i",
        str(voice_path),
        "-stream_loop",
        "-1",
        "-i",
        str(bgm_path),
        "-filter_complex",
        f"[1:a]volume=1.0[voice];[2:a]volume={volume:.3f}[bgm];"
        "[voice][bgm]amix=inputs=2:duration=first:dropout_transition=2[aout]",
        "-map",
        "0:v:0",
        "-map",
        "[aout]",
        "-c:v",
        "copy",
        "-c:a",
        "aac",
        "-b:a",
        "192k",
        "-shortest",
        "-movflags",
        "+faststart",
        str(output_path),
    ]


async def _render_segments(
    asset_paths: list[Path],
    temp_dir: Path,
    segment_duration: float,
    width: int,
    height: int,
    fps: int,
    request: CreateJobRequest,
) -> list[Path]:
    segment_paths: list[Path] = []
    for index, asset_path in enumerate(asset_paths):
        output_path = temp_dir / f"segment_{index:03d}.mp4"
        if is_image(asset_path):
            cmd = _build_image_segment_cmd(
                asset_path,
                output_path,
                segment_duration,
                width,
                height,
                fps,
                request.options.image_motion,
                request.options.transition,
            )
        elif is_video(asset_path):
            cmd = _build_video_segment_cmd(
                asset_path,
                output_path,
                segment_duration,
                width,
                height,
                fps,
                request.options.transition,
            )
        else:
            raise RuntimeError(f"Unsupported asset type for file: {asset_path.name}")
        await _run_ffmpeg(cmd)
        segment_paths.append(output_path)
    return segment_paths


async def render_video(job_id: str, request: CreateJobRequest, worker_id: str | None = None) -> Path:
    temp_dir: Path | None = None
    try:
        _update_render_job(
            job_id,
            worker_id,
            status=JobStatus.running,
            phase="preparing",
            progress=5,
            message="Preparing render",
        )
        cleanup_job_temp_dir(job_id)
        temp_dir = make_job_temp_dir(job_id)
        template_config = load_template(request.template)
        width, height = _parse_resolution(request.resolution)
        fps = request.fps or DEFAULT_FPS

        asset_paths: list[Path] = []
        for file_id in request.assets:
            path = get_upload_path_by_file_id(file_id)
            if path is None:
                raise RuntimeError(f"Uploaded asset not found: {file_id}")
            asset_paths.append(path)

        voice_path = temp_dir / "voice.mp3"
        _update_render_job(job_id, worker_id, phase="tts", progress=10, message="Generating TTS audio")
        audio_duration = await synthesize_speech(request.script, request.voice, voice_path)
        audio_duration = max(0.5, audio_duration)
        _update_render_job(job_id, worker_id, phase="tts", progress=20, message="TTS audio generated")

        segment_duration = max(0.1, audio_duration / max(1, len(asset_paths)))
        _update_render_job(job_id, worker_id, phase="rendering_segments", progress=30, message="Rendering video segments")
        segment_paths = await _render_segments(
            asset_paths,
            temp_dir,
            segment_duration,
            width,
            height,
            fps,
            request,
        )
        _update_render_job(job_id, worker_id, phase="rendering_segments", progress=40, message="Video segments rendered")

        concat_path = temp_dir / "concat.txt"
        joined_path = temp_dir / "joined.mp4"
        _write_concat_file(segment_paths, concat_path)
        await _run_ffmpeg(_build_concat_cmd(concat_path, joined_path))
        _update_render_job(job_id, worker_id, phase="joining_segments", progress=60, message="Video segments joined")

        working_video = joined_path
        segment_durations = [segment_duration] * len(asset_paths)
        if request.options.subtitle_enabled:
            subtitle_path = temp_dir / "subtitle.ass"
            subtitle_video_path = temp_dir / "with_subtitles.mp4"
            generate_ass_subtitle(
                request.script,
                audio_duration,
                subtitle_path,
                width,
                height,
                template_config,
            )
            if _ffmpeg_has_filter("subtitles"):
                try:
                    await _run_ffmpeg(_build_subtitle_cmd(working_video, subtitle_path, subtitle_video_path))
                except RuntimeError as exc:
                    logger.warning("ASS subtitles filter failed, falling back to drawtext subtitles: %s", exc)
                    await _run_ffmpeg(
                        _build_drawtext_subtitle_cmd(
                            working_video,
                            subtitle_video_path,
                            request.script,
                            audio_duration,
                            temp_dir,
                            width,
                            height,
                            template_config,
                        )
                    )
            else:
                logger.warning("FFmpeg subtitles filter is unavailable; using drawtext subtitle fallback")
                await _run_ffmpeg(
                    _build_drawtext_subtitle_cmd(
                        working_video,
                        subtitle_video_path,
                        request.script,
                        audio_duration,
                        temp_dir,
                        width,
                        height,
                        template_config,
                    )
                )
            working_video = subtitle_video_path
        _update_render_job(job_id, worker_id, phase="subtitles", progress=75, message="Subtitles processed")

        if request.options.title_enabled and request.title.strip():
            logger.info("Title rendering is disabled; ignoring title for job %s", job_id)
        _update_render_job(job_id, worker_id, phase="title", progress=82, message="Title skipped")

        if request.keyword_overlays:
            keyword_video_path = temp_dir / "with_keyword_overlays.mp4"
            keyword_cmd = _build_keyword_overlay_cmd(
                working_video,
                keyword_video_path,
                request,
                segment_durations,
                temp_dir,
                width,
                height,
                fps,
            )
            if keyword_cmd is not None:
                try:
                    await _run_ffmpeg(keyword_cmd)
                    working_video = keyword_video_path
                    _update_render_job(
                        job_id,
                        worker_id,
                        phase="keyword_overlays",
                        progress=85,
                        message="Keyword overlays processed",
                    )
                except RuntimeError as exc:
                    logger.warning("Keyword overlay rendering failed; continuing without overlays: %s", exc)

        final_output_path = get_output_path(job_id)
        await _run_ffmpeg(_build_audio_cmd(working_video, voice_path, final_output_path, request))
        final_duration = await _probe_duration(final_output_path)
        if final_duration <= 0 or final_output_path.stat().st_size == 0:
            raise RuntimeError("Final MP4 was created but appears to be empty")
        _update_render_job(job_id, worker_id, phase="mixing_audio", progress=90, message="Audio mixed")

        _update_render_job(
            job_id,
            worker_id,
            status=JobStatus.done,
            phase="completed",
            progress=100,
            message="Video generated successfully",
            video_url=f"/outputs/{final_output_path.name}",
            error="",
        )
        if CLEANUP_TEMP_AFTER_SUCCESS and temp_dir is not None:
            cleanup_job_temp_dir(job_id)
        return final_output_path
    except WorkerOwnershipLostError:
        logger.warning("Render job %s stopped because worker ownership was lost", job_id)
        raise
    except Exception as exc:
        logger.exception("Render job %s failed", job_id)
        failed_job = update_job(
            job_id,
            status=JobStatus.failed,
            phase="failed",
            progress=0,
            message="Failed",
            error=_public_error(exc),
            expected_worker_id=worker_id,
        )
        if worker_id is not None and failed_job is None:
            logger.warning("Failed to mark job %s failed because worker ownership was lost", job_id)
        raise
