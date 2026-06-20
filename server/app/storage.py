from __future__ import annotations

import logging
import shutil
from dataclasses import dataclass
from pathlib import Path
from threading import RLock
from uuid import uuid4

import aiofiles
from fastapi import UploadFile

from app.config import (
    ALLOWED_IMAGE_EXTENSIONS,
    ALLOWED_UPLOAD_EXTENSIONS,
    ALLOWED_VIDEO_EXTENSIONS,
    BGM_DIR,
    DOWNLOAD_DIR,
    FONT_DIR,
    MAX_UPLOAD_SIZE_BYTES,
    OUTPUT_DIR,
    STATE_DIR,
    TEMP_DIR,
    TEMPLATE_DIR,
    UPLOAD_DIR,
)

logger = logging.getLogger(__name__)


class StorageError(ValueError):
    pass


class FileTooLargeError(StorageError):
    pass


class InvalidFileExtensionError(StorageError):
    pass


@dataclass(frozen=True)
class UploadedFileInfo:
    file_id: str
    filename: str
    path: Path
    media_type: str
    size_bytes: int


uploaded_files: dict[str, UploadedFileInfo] = {}
_uploaded_files_lock = RLock()


def ensure_directories() -> None:
    for directory in [UPLOAD_DIR, OUTPUT_DIR, TEMP_DIR, DOWNLOAD_DIR, STATE_DIR, TEMPLATE_DIR, FONT_DIR, BGM_DIR]:
        directory.mkdir(parents=True, exist_ok=True)


def validate_file_extension(filename: str) -> str:
    suffix = Path(filename or "").suffix.lower()
    if suffix not in ALLOWED_UPLOAD_EXTENSIONS:
        raise InvalidFileExtensionError(
            "Unsupported file extension. Allowed: jpg, jpeg, png, webp, mp4, mov, m4v"
        )
    return suffix


def detect_media_type_by_extension(filename_or_path: str | Path) -> str:
    value = str(filename_or_path)
    suffix = value.lower() if value.startswith(".") else Path(value).suffix.lower()
    if suffix in ALLOWED_IMAGE_EXTENSIONS:
        return "image"
    if suffix in ALLOWED_VIDEO_EXTENSIONS:
        return "video"
    raise InvalidFileExtensionError(f"Unsupported file extension: {suffix}")


async def save_upload_file(upload_file: UploadFile) -> UploadedFileInfo:
    suffix = validate_file_extension(upload_file.filename or "")
    media_type = detect_media_type_by_extension(suffix)
    file_id = str(uuid4())
    filename = f"{file_id}{suffix}"
    destination = UPLOAD_DIR / filename

    size = 0
    try:
        async with aiofiles.open(destination, "wb") as out_file:
            while True:
                chunk = await upload_file.read(1024 * 1024)
                if not chunk:
                    break
                size += len(chunk)
                if size > MAX_UPLOAD_SIZE_BYTES:
                    raise FileTooLargeError(f"File exceeds {MAX_UPLOAD_SIZE_BYTES} bytes")
                await out_file.write(chunk)
    except Exception:
        if destination.exists():
            destination.unlink(missing_ok=True)
        raise
    finally:
        await upload_file.close()

    info = UploadedFileInfo(
        file_id=file_id,
        filename=filename,
        path=destination,
        media_type=media_type,
        size_bytes=size,
    )
    with _uploaded_files_lock:
        uploaded_files[file_id] = info
    logger.info("Saved uploaded file %s as %s (%s bytes)", file_id, filename, size)
    return info


def get_upload_path_by_file_id(file_id: str) -> Path | None:
    with _uploaded_files_lock:
        info = uploaded_files.get(file_id)
    if info is not None and info.path.exists():
        return info.path
    for path in UPLOAD_DIR.glob(f"{file_id}.*"):
        if path.is_file():
            return path
    return None


def list_uploaded_files() -> list[UploadedFileInfo]:
    with _uploaded_files_lock:
        return list(uploaded_files.values())


def delete_upload_record_by_filename(filename: str) -> bool:
    with _uploaded_files_lock:
        for file_id, info in list(uploaded_files.items()):
            if info.filename == filename:
                del uploaded_files[file_id]
                return True
    return False


def get_output_path(job_id: str) -> Path:
    return OUTPUT_DIR / f"{job_id}.mp4"


def make_job_temp_dir(job_id: str) -> Path:
    path = TEMP_DIR / job_id
    path.mkdir(parents=True, exist_ok=True)
    return path


def cleanup_job_temp_dir(job_id: str) -> None:
    path = TEMP_DIR / job_id
    if path.exists():
        shutil.rmtree(path)
