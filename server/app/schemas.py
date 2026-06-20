from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import Any, Literal, Optional

from pydantic import BaseModel, Field, field_validator

from app.config import DEFAULT_FPS, DEFAULT_RESOLUTION, DEFAULT_VOICE, SUPPORTED_RESOLUTIONS


class UploadResponse(BaseModel):
    file_id: str
    filename: str
    media_type: str
    url: str
    size_bytes: int


class VoiceConfig(BaseModel):
    speaker: str = Field(default=DEFAULT_VOICE, min_length=1, max_length=100)
    rate: str = Field(default="+0%", max_length=16)
    volume: str = Field(default="+0%", max_length=16)

    @field_validator("rate", "volume")
    @classmethod
    def validate_percent_value(cls, value: str) -> str:
        if not value.endswith("%"):
            raise ValueError("rate and volume must be percentage strings, for example '+0%'")
        prefix = value[:-1]
        if prefix.startswith("+"):
            prefix = prefix[1:]
        if not prefix or not prefix.lstrip("-").isdigit():
            raise ValueError("rate and volume must be percentage strings, for example '+0%'")
        return value


class BgmConfig(BaseModel):
    enabled: bool = False
    filename: Optional[str] = Field(default=None, max_length=255)
    volume: float = Field(default=0.15, ge=0.0, le=1.0)

    @field_validator("filename")
    @classmethod
    def validate_filename(cls, value: Optional[str]) -> Optional[str]:
        if value is None:
            return value
        if "\\" in value:
            raise ValueError("bgm filename must be a safe relative path")
        path = Path(value)
        if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
            raise ValueError("bgm filename must be a safe relative path")
        return value


BgmMood = Literal["warm_lifestyle", "light_commercial", "tech_clean", "energetic_promo", "calm_clean"]


class BgmProfile(BaseModel):
    scene: Optional[str] = Field(default=None, max_length=80)
    emotion: Optional[str] = Field(default=None, max_length=80)
    pace: Literal["slow", "medium", "fast"] = "medium"
    bgm_mood: BgmMood = "light_commercial"
    preferred_tags: list[str] = Field(default_factory=list, max_length=12)
    avoid_tags: list[str] = Field(default_factory=list, max_length=12)
    volume_hint: Optional[float] = None

    @field_validator("scene", "emotion")
    @classmethod
    def clean_optional_text(cls, value: Optional[str]) -> Optional[str]:
        if value is None:
            return None
        text = str(value).strip()[:80]
        return text or None

    @field_validator("pace", mode="before")
    @classmethod
    def validate_pace(cls, value: Any) -> str:
        text = str(value or "medium").strip().lower()
        return text if text in {"slow", "medium", "fast"} else "medium"

    @field_validator("bgm_mood", mode="before")
    @classmethod
    def validate_bgm_mood(cls, value: Any) -> str:
        allowed = {"warm_lifestyle", "light_commercial", "tech_clean", "energetic_promo", "calm_clean"}
        text = str(value or "light_commercial").strip()
        return text if text in allowed else "light_commercial"

    @field_validator("preferred_tags", "avoid_tags", mode="before")
    @classmethod
    def clean_tags(cls, value: Any) -> list[str]:
        if not isinstance(value, list):
            return []
        tags: list[str] = []
        for item in value:
            text = str(item or "").strip()[:24]
            if text and text not in tags:
                tags.append(text)
            if len(tags) >= 12:
                break
        return tags

    @field_validator("volume_hint", mode="before")
    @classmethod
    def validate_volume_hint(cls, value: Any) -> Optional[float]:
        if value is None or value == "":
            return None
        try:
            number = float(value)
        except (TypeError, ValueError):
            return None
        return max(0.0, min(1.0, number))


class BgmCandidate(BaseModel):
    id: str
    filename: str
    mood: BgmMood
    tags: list[str] = Field(default_factory=list)
    duration: float = 0.0
    vocal: bool = False
    loopable: bool = False
    volume: float = 0.08
    score: float = 0.0
    score_reasons: list[str] = Field(default_factory=list)
    attribution_required: bool = False
    attribution: Optional[str] = None
    source: Optional[str] = None


class BgmDecision(BaseModel):
    selected_bgm_id: str = Field(..., min_length=1, max_length=40)
    reason: str = Field(default="", max_length=240)
    confidence: float = 0.0

    @field_validator("reason")
    @classmethod
    def clean_reason(cls, value: str) -> str:
        return str(value or "").strip()[:240]

    @field_validator("confidence", mode="before")
    @classmethod
    def clamp_confidence(cls, value: Any) -> float:
        try:
            number = float(value)
        except (TypeError, ValueError):
            return 0.0
        return max(0.0, min(1.0, number))


class RenderOptions(BaseModel):
    subtitle_enabled: bool = True
    title_enabled: bool = True
    image_motion: str = "slow_zoom"
    transition: str = "fade"

    @field_validator("image_motion")
    @classmethod
    def validate_image_motion(cls, value: str) -> str:
        allowed = {"slow_zoom", "none"}
        if value not in allowed:
            raise ValueError(f"image_motion must be one of {sorted(allowed)}")
        return value

    @field_validator("transition")
    @classmethod
    def validate_transition(cls, value: str) -> str:
        allowed = {"fade", "none"}
        if value not in allowed:
            raise ValueError(f"transition must be one of {sorted(allowed)}")
        return value


class KeywordOverlay(BaseModel):
    text: str = Field(..., min_length=1, max_length=120)
    segment_index: int = Field(default=0, ge=0)
    start_ratio: float = Field(default=0.20, ge=0.0, le=1.0)
    end_ratio: float = Field(default=0.75, ge=0.0, le=1.0)
    keyword_type: Literal["experience", "function", "scene", "promo", "spec", "generic"] = "generic"
    style: Literal["pop", "clean", "badge"] = "pop"
    effect: Literal[
        "soft_float",
        "strong_badge",
        "clean_scene",
        "promo_flash",
        "spec_corner",
        "default_pop",
    ] = "default_pop"
    position: Literal[
        "center_upper",
        "center",
        "right_upper",
        "left_middle",
        "corner_right",
        "corner_left",
    ] = "center_upper"
    intensity: Literal["low", "medium", "high"] = "medium"
    reason: Optional[str] = Field(default=None, max_length=200)

    @field_validator("text")
    @classmethod
    def validate_overlay_text(cls, value: str) -> str:
        text = value.strip()
        if not text:
            raise ValueError("keyword overlay text cannot be empty")
        return text

    @field_validator("keyword_type", mode="before")
    @classmethod
    def validate_keyword_type(cls, value: Any) -> str:
        allowed = {"experience", "function", "scene", "promo", "spec", "generic"}
        text = str(value or "generic").strip()
        return text if text in allowed else "generic"

    @field_validator("style", mode="before")
    @classmethod
    def validate_style(cls, value: Any) -> str:
        allowed = {"pop", "clean", "badge"}
        text = str(value or "pop").strip()
        return text if text in allowed else "pop"

    @field_validator("effect", mode="before")
    @classmethod
    def validate_effect(cls, value: Any) -> str:
        allowed = {"soft_float", "strong_badge", "clean_scene", "promo_flash", "spec_corner", "default_pop"}
        text = str(value or "default_pop").strip()
        return text if text in allowed else "default_pop"

    @field_validator("position", mode="before")
    @classmethod
    def validate_position(cls, value: Any) -> str:
        allowed = {"center_upper", "center", "right_upper", "left_middle", "corner_right", "corner_left"}
        text = str(value or "center_upper").strip()
        return text if text in allowed else "center_upper"

    @field_validator("intensity", mode="before")
    @classmethod
    def validate_intensity(cls, value: Any) -> str:
        allowed = {"low", "medium", "high"}
        text = str(value or "medium").strip()
        return text if text in allowed else "medium"

    @field_validator("reason")
    @classmethod
    def validate_reason(cls, value: Optional[str]) -> Optional[str]:
        if value is None:
            return None
        text = value.strip()
        return text[:200] or None

    @field_validator("end_ratio")
    @classmethod
    def validate_ratio_order(cls, value: float, info) -> float:
        start = info.data.get("start_ratio", 0.20)
        if value <= start:
            return min(1.0, start + 0.30)
        return value


class SmartScriptResult(BaseModel):
    title: str = Field(default="", max_length=80)
    script: list[str] = Field(default_factory=list, min_length=1, max_length=8)
    bgm_mood: str = Field(default="light_commercial", max_length=80)
    bgm_profile: Optional[BgmProfile] = None
    style_notes: str = Field(default="", max_length=300)
    product_keywords: list[str] = Field(default_factory=list, max_length=6)
    keyword_overlays: list[KeywordOverlay] = Field(default_factory=list, max_length=4)


class CreateJobRequest(BaseModel):
    template: str = Field(..., min_length=1, max_length=80)
    title: str = Field(default="", max_length=120)
    script: list[str] = Field(..., min_length=1, max_length=30)
    assets: list[str] = Field(..., min_length=1, max_length=30)
    voice: VoiceConfig = Field(default_factory=VoiceConfig)
    ratio: str = "9:16"
    resolution: str = DEFAULT_RESOLUTION
    fps: int = Field(default=DEFAULT_FPS, ge=1, le=60)
    bgm: BgmConfig = Field(default_factory=BgmConfig)
    options: RenderOptions = Field(default_factory=RenderOptions)
    keyword_overlays: list[KeywordOverlay] = Field(default_factory=list, max_length=8)

    @field_validator("template")
    @classmethod
    def validate_template_text(cls, value: str) -> str:
        if "/" in value or "\\" in value or value.startswith("."):
            raise ValueError("template name is invalid")
        return value

    @field_validator("script")
    @classmethod
    def validate_script_items(cls, value: list[str]) -> list[str]:
        cleaned: list[str] = []
        for item in value:
            text = item.strip()
            if not text:
                raise ValueError("script items cannot be empty")
            if len(text) > 300:
                raise ValueError("each script item must be 300 characters or fewer")
            cleaned.append(text)
        return cleaned

    @field_validator("assets")
    @classmethod
    def validate_asset_ids(cls, value: list[str]) -> list[str]:
        cleaned: list[str] = []
        for item in value:
            text = item.strip()
            if not text:
                raise ValueError("asset ids cannot be empty")
            if "/" in text or "\\" in text:
                raise ValueError("asset id is invalid")
            cleaned.append(text)
        return cleaned

    @field_validator("ratio")
    @classmethod
    def validate_ratio(cls, value: str) -> str:
        if value != "9:16":
            raise ValueError("only 9:16 is supported in v1")
        return value

    @field_validator("resolution")
    @classmethod
    def validate_resolution(cls, value: str) -> str:
        if value not in SUPPORTED_RESOLUTIONS:
            raise ValueError(f"resolution must be one of {sorted(SUPPORTED_RESOLUTIONS)}")
        return value


class SmartJobRequest(BaseModel):
    template: str = Field(..., min_length=1, max_length=80)
    prompt: str = Field(..., min_length=5, max_length=500)
    assets: list[str] = Field(..., min_length=1, max_length=30)
    voice: VoiceConfig = Field(default_factory=VoiceConfig)
    ratio: str = "9:16"
    resolution: str = DEFAULT_RESOLUTION
    fps: int = Field(default=DEFAULT_FPS, ge=1, le=60)
    auto_bgm: bool = True

    @field_validator("template")
    @classmethod
    def validate_template_text(cls, value: str) -> str:
        if "/" in value or "\\" in value or value.startswith("."):
            raise ValueError("template name is invalid")
        return value

    @field_validator("prompt")
    @classmethod
    def validate_prompt(cls, value: str) -> str:
        text = value.strip()
        if len(text) < 5:
            raise ValueError("prompt must be at least 5 characters")
        return text

    @field_validator("assets")
    @classmethod
    def validate_asset_ids(cls, value: list[str]) -> list[str]:
        cleaned: list[str] = []
        for item in value:
            text = item.strip()
            if not text:
                raise ValueError("asset ids cannot be empty")
            if "/" in text or "\\" in text:
                raise ValueError("asset id is invalid")
            cleaned.append(text)
        return cleaned

    @field_validator("ratio")
    @classmethod
    def validate_ratio(cls, value: str) -> str:
        if value != "9:16":
            raise ValueError("only 9:16 is supported in v1")
        return value

    @field_validator("resolution")
    @classmethod
    def validate_resolution(cls, value: str) -> str:
        if value not in SUPPORTED_RESOLUTIONS:
            raise ValueError(f"resolution must be one of {sorted(SUPPORTED_RESOLUTIONS)}")
        return value


class CreateJobResponse(BaseModel):
    job_id: str
    status: str


class JobStatusResponse(BaseModel):
    job_id: str
    status: str
    phase: Optional[str] = None
    progress: int
    message: str
    video_url: Optional[str] = None
    error: Optional[str] = None
    created_at: datetime
    updated_at: datetime


class GenerationHistoryItem(BaseModel):
    job_id: str
    prompt: str
    video_url: str
    created_at: datetime
    updated_at: datetime


class GenerationHistoryResponse(BaseModel):
    items: list[GenerationHistoryItem]


class TemplateInfo(BaseModel):
    name: str
    display_name: str
    description: str
    ratio: str


class VoiceInfo(BaseModel):
    id: str
    name: str
    locale: str
    gender: str


class AppUpdateResponse(BaseModel):
    versionCode: int = Field(..., ge=1)
    versionName: str = Field(..., min_length=1, max_length=40)
    apkUrl: str = Field(..., min_length=1, max_length=500)
    releaseNotes: str = Field(default="", max_length=2000)
    sha256: Optional[str] = Field(default=None, max_length=128)
