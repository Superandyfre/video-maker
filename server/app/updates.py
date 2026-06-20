from __future__ import annotations

import json

from fastapi import HTTPException, status
from pydantic import ValidationError

from app.config import ANDROID_UPDATE_MANIFEST_PATH
from app.schemas import AppUpdateResponse


def load_android_update_manifest() -> AppUpdateResponse:
    if not ANDROID_UPDATE_MANIFEST_PATH.exists():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Android update manifest not found",
        )

    try:
        raw = json.loads(ANDROID_UPDATE_MANIFEST_PATH.read_text(encoding="utf-8"))
        return AppUpdateResponse.model_validate(raw)
    except (OSError, json.JSONDecodeError, ValidationError) as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Android update manifest is invalid",
        ) from exc
