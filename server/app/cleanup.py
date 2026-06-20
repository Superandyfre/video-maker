from __future__ import annotations

import logging
import shutil
import time
from dataclasses import dataclass
from pathlib import Path

from app.config import OUTPUT_DIR, TEMP_DIR, UPLOAD_DIR
from app.storage import delete_upload_record_by_filename

logger = logging.getLogger(__name__)


@dataclass
class CleanupResult:
    deleted_files: int = 0
    deleted_dirs: int = 0
    freed_bytes: int = 0

    def to_dict(self) -> dict[str, int]:
        return {
            "deleted_files": self.deleted_files,
            "deleted_dirs": self.deleted_dirs,
            "freed_bytes": self.freed_bytes,
        }


def _cutoff_timestamp(retention_hours: int) -> float:
    return time.time() - max(0, retention_hours) * 3600


def _is_expired(path: Path, cutoff: float) -> bool:
    try:
        return path.stat().st_mtime < cutoff
    except FileNotFoundError:
        return False


def _file_size(path: Path) -> int:
    try:
        return path.stat().st_size
    except FileNotFoundError:
        return 0


def cleanup_expired_files(directory: Path, retention_hours: int) -> CleanupResult:
    result = CleanupResult()
    if not directory.exists():
        return result
    cutoff = _cutoff_timestamp(retention_hours)
    for path in directory.iterdir():
        if not path.is_file() or path.name == ".gitkeep":
            continue
        if not _is_expired(path, cutoff):
            continue
        size = _file_size(path)
        try:
            path.unlink()
        except FileNotFoundError:
            continue
        if directory == UPLOAD_DIR:
            delete_upload_record_by_filename(path.name)
        result.deleted_files += 1
        result.freed_bytes += size
        logger.info("Deleted expired file %s", path)
    return result


def cleanup_expired_temp_dirs(retention_hours: int) -> CleanupResult:
    result = CleanupResult()
    if not TEMP_DIR.exists():
        return result
    cutoff = _cutoff_timestamp(retention_hours)
    for path in TEMP_DIR.iterdir():
        if path.name == ".gitkeep":
            continue
        if not path.is_dir() or not _is_expired(path, cutoff):
            continue
        size = sum(_file_size(child) for child in path.rglob("*") if child.is_file())
        try:
            shutil.rmtree(path)
        except FileNotFoundError:
            continue
        result.deleted_dirs += 1
        result.freed_bytes += size
        logger.info("Deleted expired temp directory %s", path)
    return result


def run_cleanup(
    *,
    temp_retention_hours: int,
    upload_retention_hours: int | None = None,
    output_retention_hours: int | None = None,
) -> dict[str, dict[str, int]]:
    results: dict[str, dict[str, int]] = {
        "temp": cleanup_expired_temp_dirs(temp_retention_hours).to_dict()
    }
    if upload_retention_hours is not None:
        results["uploads"] = cleanup_expired_files(UPLOAD_DIR, upload_retention_hours).to_dict()
    if output_retention_hours is not None:
        results["outputs"] = cleanup_expired_files(OUTPUT_DIR, output_retention_hours).to_dict()
    return results
