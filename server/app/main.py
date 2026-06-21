from __future__ import annotations

import asyncio
import json
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, File, HTTPException, UploadFile, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse

from app.auth import require_api_token, require_configured_api_token
from app.cleanup import run_cleanup
from app.config import (
    CORS_ALLOW_ORIGINS,
    DOWNLOAD_DIR,
    OUTPUT_DIR,
    OUTPUT_RETENTION_HOURS,
    TEMP_RETENTION_HOURS,
    UPLOAD_DIR,
    UPLOAD_RETENTION_HOURS,
    VERSION,
)
from app.bgm_selector import build_bgm_candidates, finalize_bgm_selection
from app.deepseek_client import DeepSeekError, select_bgm_from_candidates
from app.ffmpeg_utils import (
    check_ffmpeg_binary_available,
    check_ffprobe_binary_available,
    get_ffmpeg_path,
    get_ffprobe_path,
)
from app.jobs import create_job, get_job
from app.jobs import list_generation_history
from app.renderer import render_video
from app.schemas import (
    BgmConfig,
    BgmProfile,
    CapabilitiesResponse,
    CreateJobRequest,
    CreateJobResponse,
    AppUpdateResponse,
    GenerationHistoryItem,
    GenerationHistoryResponse,
    JobStatusResponse,
    RenderOptions,
    SmartJobRequest,
    TemplateInfo,
    UploadResponse,
    VoiceInfo,
)
from app.smart_script import generate_smart_script
from app.storage import (
    FileTooLargeError,
    InvalidFileContentError,
    InvalidFileExtensionError,
    ensure_directories,
    get_upload_path_by_file_id,
    save_upload_file,
)
from app.templates import TemplateError, list_templates, validate_template_name
from app.updates import load_android_update_manifest

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    ensure_directories()
    run_cleanup(temp_retention_hours=TEMP_RETENTION_HOURS)
    yield


app = FastAPI(
    title="Video Maker Server",
    description="FastAPI backend for generating 9:16 marketing videos from uploaded media assets.",
    version=VERSION,
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=CORS_ALLOW_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


VOICES = [
    VoiceInfo(id="zh-CN-XiaoxiaoNeural", name="晓晓", locale="zh-CN", gender="female"),
    VoiceInfo(id="zh-CN-YunxiNeural", name="云希", locale="zh-CN", gender="male"),
    VoiceInfo(id="zh-CN-YunjianNeural", name="云健", locale="zh-CN", gender="male"),
]


def _resolve_served_file(root: Path, requested_path: str) -> Path:
    if "\\" in requested_path:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="File not found")
    relative_path = Path(requested_path)
    if relative_path.is_absolute() or any(part in {"", ".", ".."} for part in relative_path.parts):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="File not found")
    root_path = root.resolve()
    candidate = (root_path / relative_path).resolve()
    try:
        candidate.relative_to(root_path)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="File not found") from exc
    if not candidate.is_file():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="File not found")
    return candidate


def _protected_file_response(root: Path, requested_path: str) -> FileResponse:
    return FileResponse(_resolve_served_file(root, requested_path))


def _validate_render_inputs(template: str, assets: list[str]) -> None:
    try:
        validate_template_name(template)
    except TemplateError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    missing_assets = [file_id for file_id in assets if get_upload_path_by_file_id(file_id) is None]
    if missing_assets:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Uploaded asset not found: {missing_assets[0]}",
        )


def _job_to_response(job) -> JobStatusResponse:
    return JobStatusResponse(
        job_id=job.job_id,
        status=job.status.value,
        phase=getattr(job, "phase", None),
        progress=job.progress,
        message=job.message,
        video_url=job.video_url,
        error=job.error or None,
        created_at=job.created_at,
        updated_at=job.updated_at,
    )


def _estimate_video_duration(script: list[str]) -> float | None:
    if not script:
        return None
    char_count = sum(len(sentence.strip()) for sentence in script)
    # TTS is generated later; this estimate is only for BGM duration scoring.
    return max(8.0, min(90.0, char_count * 0.22 + len(script) * 0.45))


async def _select_smart_bgm(prompt: str, smart_script, auto_bgm: bool) -> BgmConfig:
    if not auto_bgm:
        logger.info(
            "BGM decision metadata: %s",
            json.dumps({"method": "disabled", "reason": "auto_bgm_false"}, ensure_ascii=False),
        )
        return BgmConfig(enabled=False)

    bgm_profile = smart_script.bgm_profile or BgmProfile(bgm_mood=smart_script.bgm_mood)
    try:
        candidates = build_bgm_candidates(
            prompt=prompt,
            bgm_profile=bgm_profile,
            video_duration=_estimate_video_duration(smart_script.script),
            max_candidates=10,
        )
        if len(candidates) < 3:
            selection = finalize_bgm_selection(
                candidates,
                bgm_profile,
                method="deterministic_top_score",
                reason="candidate_count_below_llm_threshold",
            )
        else:
            try:
                decision = await asyncio.to_thread(
                    select_bgm_from_candidates,
                    prompt,
                    smart_script.title,
                    smart_script.script,
                    bgm_profile,
                    candidates,
                )
                selection = finalize_bgm_selection(
                    candidates,
                    bgm_profile,
                    selected_bgm_id=decision.selected_bgm_id,
                    method="llm_candidate_select",
                    reason=decision.reason,
                    confidence=decision.confidence,
                )
            except DeepSeekError as exc:
                selection = finalize_bgm_selection(
                    candidates,
                    bgm_profile,
                    method="deterministic_top_score",
                    reason="DeepSeek BGM candidate selection failed; using top score",
                    fallback_reason=f"llm_candidate_select_failed:{type(exc).__name__}",
                )
        logger.info("BGM decision metadata: %s", json.dumps(selection.metadata, ensure_ascii=False))
        return selection.config
    except Exception as exc:
        logger.warning("BGM selection failed; rendering without BGM: %s", exc)
        logger.info(
            "BGM decision metadata: %s",
            json.dumps(
                {
                    "method": "disabled",
                    "fallback_used": True,
                    "fallback_reason": f"bgm_selection_failed:{type(exc).__name__}",
                    "bgm_mood": smart_script.bgm_mood,
                },
                ensure_ascii=False,
            ),
        )
        return BgmConfig(enabled=False)


@app.get("/api/health")
async def health() -> dict[str, object]:
    return {
        "status": "ok",
        "ffmpeg_available": check_ffmpeg_binary_available(),
        "ffmpeg_path": get_ffmpeg_path(),
        "ffprobe_available": check_ffprobe_binary_available(),
        "ffprobe_path": get_ffprobe_path(),
        "version": VERSION,
    }


@app.get("/app/android/latest.json", response_model=AppUpdateResponse)
async def latest_android_version(_auth: None = Depends(require_api_token)) -> AppUpdateResponse:
    return load_android_update_manifest()


@app.get("/uploads/{file_path:path}")
async def serve_upload(file_path: str, _auth: None = Depends(require_api_token)) -> FileResponse:
    return _protected_file_response(UPLOAD_DIR, file_path)


@app.get("/outputs/{file_path:path}")
async def serve_output(file_path: str, _auth: None = Depends(require_api_token)) -> FileResponse:
    return _protected_file_response(OUTPUT_DIR, file_path)


@app.get("/downloads/{file_path:path}")
async def serve_download(file_path: str, _auth: None = Depends(require_api_token)) -> FileResponse:
    return _protected_file_response(DOWNLOAD_DIR, file_path)


@app.post("/api/upload", response_model=UploadResponse)
async def upload_file(
    file: UploadFile = File(...),
    _auth: None = Depends(require_api_token),
) -> UploadResponse:
    try:
        info = await save_upload_file(file)
    except InvalidFileExtensionError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    except InvalidFileContentError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    except FileTooLargeError as exc:
        raise HTTPException(status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("Upload failed")
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Upload failed") from exc

    return UploadResponse(
        file_id=info.file_id,
        filename=info.filename,
        media_type=info.media_type,
        url=f"/uploads/{info.filename}",
        size_bytes=info.size_bytes,
    )


@app.post("/api/jobs", response_model=CreateJobResponse)
async def create_video_job(
    request: CreateJobRequest,
    _auth: None = Depends(require_api_token),
) -> CreateJobResponse:
    _validate_render_inputs(request.template, request.assets)

    job = create_job(request.model_dump(mode="json"))
    return CreateJobResponse(job_id=job.job_id, status=job.status.value)


@app.post("/api/smart-jobs", response_model=CreateJobResponse)
async def create_smart_video_job(
    request: SmartJobRequest,
    _auth: None = Depends(require_api_token),
) -> CreateJobResponse:
    _validate_render_inputs(request.template, request.assets)

    try:
        smart_script = await asyncio.to_thread(
            generate_smart_script,
            request.prompt,
            request.template,
            len(request.assets),
        )
        bgm = await _select_smart_bgm(request.prompt, smart_script, request.auto_bgm)
        logger.info(
            "Smart job generated content: title=%r script=%r product_keywords=%r bgm_mood=%r bgm_profile=%r keyword_overlays=%r",
            smart_script.title,
            smart_script.script,
            smart_script.product_keywords,
            smart_script.bgm_mood,
            smart_script.bgm_profile.model_dump() if smart_script.bgm_profile else None,
            [overlay.model_dump() for overlay in smart_script.keyword_overlays],
        )
    except Exception as exc:
        logger.exception("Smart job preparation failed")
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Smart job preparation failed") from exc

    render_request = CreateJobRequest(
        template=request.template,
        title=smart_script.title,
        script=smart_script.script,
        assets=request.assets,
        voice=request.voice,
        ratio=request.ratio,
        resolution=request.resolution,
        fps=request.fps,
        bgm=bgm,
        options=RenderOptions(
            subtitle_enabled=True,
            title_enabled=False,
            image_motion="slow_zoom",
            transition="fade",
        ),
        keyword_overlays=smart_script.keyword_overlays,
    )

    job = create_job(render_request.model_dump(mode="json"))
    return CreateJobResponse(job_id=job.job_id, status=job.status.value)


@app.get("/api/jobs/{job_id}", response_model=JobStatusResponse)
async def get_video_job(
    job_id: str,
    _auth: None = Depends(require_api_token),
) -> JobStatusResponse:
    job = get_job(job_id)
    if job is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Job not found")
    return _job_to_response(job)


@app.get("/api/history", response_model=GenerationHistoryResponse)
async def get_generation_history(
    limit: int = 20,
    _auth: None = Depends(require_api_token),
) -> GenerationHistoryResponse:
    return GenerationHistoryResponse(
        items=[
            GenerationHistoryItem(
                job_id=item.job_id,
                prompt=item.prompt,
                video_url=item.video_url,
                created_at=item.created_at,
                updated_at=item.updated_at,
            )
            for item in list_generation_history(limit=limit)
        ]
    )


@app.get("/api/templates")
async def get_templates() -> dict[str, list[TemplateInfo]]:
    return {"templates": [TemplateInfo(**item) for item in list_templates()]}


@app.get("/api/voices")
async def get_voices() -> list[VoiceInfo]:
    return VOICES


@app.get("/api/capabilities", response_model=CapabilitiesResponse)
async def get_capabilities(_auth: None = Depends(require_api_token)) -> CapabilitiesResponse:
    return CapabilitiesResponse()


@app.post("/api/admin/cleanup")
async def cleanup_files(_auth: None = Depends(require_configured_api_token)) -> dict[str, object]:
    return {
        "status": "ok",
        "retention_hours": {
            "temp": TEMP_RETENTION_HOURS,
            "uploads": UPLOAD_RETENTION_HOURS,
            "outputs": OUTPUT_RETENTION_HOURS,
        },
        "deleted": run_cleanup(
            temp_retention_hours=TEMP_RETENTION_HOURS,
            upload_retention_hours=UPLOAD_RETENTION_HOURS,
            output_retention_hours=OUTPUT_RETENTION_HOURS,
        ),
    }


@app.exception_handler(HTTPException)
async def http_exception_handler(_request, exc: HTTPException):
    return JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})
