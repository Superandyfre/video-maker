from __future__ import annotations

import json
import logging
import os
import subprocess
from pathlib import Path
from shutil import which
from typing import Any

from app.config import ALLOWED_IMAGE_EXTENSIONS, ALLOWED_VIDEO_EXTENSIONS, FFMPEG_BIN, FFPROBE_BIN

logger = logging.getLogger(__name__)


def _is_executable(path: str | None) -> bool:
    return bool(path) and Path(path).is_file() and os.access(path, os.X_OK)


def get_ffmpeg_path() -> str | None:
    configured = (os.getenv("FFMPEG_BIN") or FFMPEG_BIN).strip()
    if configured:
        return configured
    return which("ffmpeg")


def get_ffprobe_path() -> str | None:
    configured = (os.getenv("FFPROBE_BIN") or FFPROBE_BIN).strip()
    if configured:
        return configured
    return which("ffprobe")


def check_ffmpeg_binary_available() -> bool:
    return _is_executable(get_ffmpeg_path())


def check_ffprobe_binary_available() -> bool:
    return _is_executable(get_ffprobe_path())


def _replace_binary(cmd: list[str]) -> list[str]:
    if not cmd:
        return cmd
    resolved = None
    if cmd[0] == "ffmpeg":
        resolved = get_ffmpeg_path()
    elif cmd[0] == "ffprobe":
        resolved = get_ffprobe_path()
    if resolved:
        return [resolved, *cmd[1:]]
    return cmd


def run_ffmpeg(cmd: list[str], timeout: int = 600) -> subprocess.CompletedProcess[str]:
    resolved_cmd = _replace_binary(cmd)
    try:
        result = subprocess.run(
            resolved_cmd,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(f"FFmpeg command timed out after {timeout} seconds") from exc
    except OSError as exc:
        raise RuntimeError(f"Failed to start FFmpeg command: {exc}") from exc

    if result.returncode != 0:
        stderr = result.stderr or ""
        tail = "\n".join(stderr.strip().splitlines()[-20:])
        logger.error("FFmpeg failed: %s\n%s", " ".join(resolved_cmd[:3]), tail)
        raise RuntimeError(f"FFmpeg failed with exit code {result.returncode}: {tail}")
    return result


def run_ffprobe_duration(path: Path) -> float:
    cmd = [
        get_ffprobe_path() or "ffprobe",
        "-v",
        "error",
        "-show_entries",
        "format=duration",
        "-of",
        "default=noprint_wrappers=1:nokey=1",
        str(path),
    ]
    try:
        result = subprocess.run(
            cmd,
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (subprocess.TimeoutExpired, OSError) as exc:
        raise RuntimeError(f"Failed to run ffprobe: {exc}") from exc
    if result.returncode != 0:
        tail = "\n".join((result.stderr or "").strip().splitlines()[-20:])
        raise RuntimeError(f"ffprobe failed: {tail}")
    try:
        return max(0.0, float(result.stdout.strip()))
    except ValueError as exc:
        raise RuntimeError(f"ffprobe returned invalid duration for {path.name}") from exc


def check_ffmpeg_available() -> bool:
    return check_ffmpeg_binary_available() and check_ffprobe_binary_available()


def get_media_info(path: Path) -> dict[str, Any]:
    cmd = [
        get_ffprobe_path() or "ffprobe",
        "-v",
        "error",
        "-print_format",
        "json",
        "-show_streams",
        "-show_format",
        str(path),
    ]
    try:
        result = subprocess.run(
            cmd,
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (subprocess.TimeoutExpired, OSError) as exc:
        raise RuntimeError(f"Failed to run ffprobe: {exc}") from exc
    if result.returncode != 0:
        tail = "\n".join((result.stderr or "").strip().splitlines()[-20:])
        raise RuntimeError(f"ffprobe failed: {tail}")
    return json.loads(result.stdout or "{}")


def is_video(path: Path) -> bool:
    return path.suffix.lower() in ALLOWED_VIDEO_EXTENSIONS


def is_image(path: Path) -> bool:
    return path.suffix.lower() in ALLOWED_IMAGE_EXTENSIONS
