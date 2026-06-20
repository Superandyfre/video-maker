from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

import yaml

from app.config import BGM_ALLOW_ATTRIBUTION_REQUIRED, BGM_DIR
from app.ffmpeg_utils import run_ffprobe_duration
from app.schemas import BgmCandidate, BgmConfig, BgmProfile

logger = logging.getLogger(__name__)
MANIFEST_PATH = BGM_DIR / "manifest.yaml"
USAGE_HISTORY_PATH = BGM_DIR / "usage_history.json"
SUPPORTED_BGM_EXTENSIONS = {".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac"}
VALID_MOODS = {"warm_lifestyle", "light_commercial", "tech_clean", "energetic_promo", "calm_clean"}
DEFAULT_VOLUME = 0.08
BGM_VOLUME_GAIN = 1.30


@dataclass(frozen=True)
class BgmSelection:
    config: BgmConfig
    metadata: dict[str, Any]


def _safe_track_path(filename: str) -> Path | None:
    if not filename or "\\" in filename:
        return None
    relative_path = Path(filename)
    if relative_path.is_absolute() or any(part in {"", ".", ".."} for part in relative_path.parts):
        return None
    path = (BGM_DIR / relative_path).resolve()
    try:
        path.relative_to(BGM_DIR.resolve())
    except ValueError:
        return None
    if path.suffix.lower() not in SUPPORTED_BGM_EXTENSIONS:
        return None
    if not path.exists() or not path.is_file():
        return None
    return path


def _as_bool(value: Any, default: bool = False) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    text = str(value).strip().lower()
    if text in {"1", "true", "yes", "on"}:
        return True
    if text in {"0", "false", "no", "off"}:
        return False
    return default


def _as_float(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _clean_tags(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    tags: list[str] = []
    for item in value:
        text = str(item or "").strip()[:24]
        if text and text not in tags:
            tags.append(text)
        if len(tags) >= 16:
            break
    return tags


def _normalize_mood(value: Any) -> str:
    text = str(value or "light_commercial").strip()
    return text if text in VALID_MOODS else "light_commercial"


def _load_usage_history() -> dict[str, dict[str, Any]]:
    if not USAGE_HISTORY_PATH.exists():
        return {}
    try:
        data = json.loads(USAGE_HISTORY_PATH.read_text(encoding="utf-8"))
    except Exception as exc:
        logger.warning("Failed to read BGM usage history: %s", exc)
        return {}
    if not isinstance(data, dict):
        return {}
    return {str(key): value for key, value in data.items() if isinstance(value, dict)}


def record_bgm_usage(filename: str) -> None:
    if _safe_track_path(filename) is None:
        return
    try:
        history = _load_usage_history()
        item = history.get(filename, {})
        used_count = int(item.get("used_count", 0)) if isinstance(item, dict) else 0
        history[filename] = {
            "used_count": used_count + 1,
            "last_used_at": datetime.utcnow().isoformat(timespec="seconds"),
        }
        USAGE_HISTORY_PATH.write_text(json.dumps(history, ensure_ascii=False, indent=2), encoding="utf-8")
    except Exception as exc:
        logger.warning("Failed to write BGM usage history: %s", exc)


def _usage_penalty(filename: str, history: dict[str, dict[str, Any]]) -> tuple[float, list[str]]:
    item = history.get(filename) or {}
    penalty = 0.0
    reasons: list[str] = []
    try:
        used_count = int(item.get("used_count", 0))
    except (TypeError, ValueError):
        used_count = 0
    if used_count > 0:
        count_penalty = min(15.0, used_count * 2.0)
        penalty -= count_penalty
        reasons.append(f"usage_count_penalty:{used_count}")
    last_used_at = str(item.get("last_used_at") or "")
    if last_used_at:
        try:
            last_used = datetime.fromisoformat(last_used_at)
            if datetime.utcnow() - last_used < timedelta(hours=24):
                penalty -= 20.0
                reasons.append("recent_usage_penalty")
        except ValueError:
            pass
    return penalty, reasons


def _duration_for_track(filename: str, item: dict[str, Any]) -> float | None:
    duration = _as_float(item.get("duration"), 0.0)
    if duration > 0:
        return duration
    path = _safe_track_path(filename)
    if path is None:
        return None
    try:
        return run_ffprobe_duration(path)
    except Exception as exc:
        logger.warning("Skipping BGM with unreadable duration filename=%s error=%s", filename, exc)
        return None


def _load_tracks() -> list[dict[str, Any]]:
    if not MANIFEST_PATH.exists():
        return []
    try:
        data = yaml.safe_load(MANIFEST_PATH.read_text(encoding="utf-8")) or {}
    except Exception as exc:
        logger.warning("Failed to read BGM manifest: %s", exc)
        return []
    tracks = data.get("tracks", [])
    if not isinstance(tracks, list):
        return []
    usable: list[dict[str, Any]] = []
    for item in tracks:
        if not isinstance(item, dict):
            continue
        filename = str(item.get("filename") or "").strip()
        if _safe_track_path(filename) is None:
            continue
        usable.append(item)
    return usable


def _passes_hard_filters(item: dict[str, Any], filename: str, duration: float | None, bgm_profile: BgmProfile) -> tuple[bool, str | None]:
    if _safe_track_path(filename) is None:
        return False, "unsafe_or_missing_file"
    if _as_bool(item.get("vocal"), False):
        return False, "vocal_track"
    if _as_bool(item.get("commercial_use"), True) is False:
        return False, "commercial_use_false"
    if _as_bool(item.get("derivative_allowed"), True) is False:
        return False, "derivative_allowed_false"
    if "license_checked" in item and _as_bool(item.get("license_checked"), True) is False:
        return False, "license_checked_false"
    attribution_required = _as_bool(item.get("attribution_required"), False)
    if attribution_required and not BGM_ALLOW_ATTRIBUTION_REQUIRED:
        return False, "attribution_required_disabled"
    if duration is None or duration <= 0:
        return False, "duration_unreadable"
    if duration < 10:
        return False, "duration_too_short"
    if "vocal" in {tag.lower() for tag in bgm_profile.avoid_tags} and _as_bool(item.get("vocal"), False):
        return False, "avoid_vocal"
    return True, None


def score_bgm_track(
    track: dict[str, Any],
    prompt: str,
    bgm_profile: BgmProfile,
    video_duration: float | None,
    usage_history: dict[str, dict[str, Any]] | None = None,
) -> tuple[float, list[str]]:
    filename = str(track.get("filename") or "").strip()
    mood = _normalize_mood(track.get("mood"))
    tags = _clean_tags(track.get("tags"))
    duration = _as_float(track.get("duration"), 0.0)
    usage_history = usage_history or {}
    score = 0.0
    reasons: list[str] = []

    if mood == bgm_profile.bgm_mood:
        score += 40
        reasons.append("mood_match")
    else:
        score -= 10
        reasons.append("mood_mismatch")

    tag_set = {tag.lower() for tag in tags}
    for tag in bgm_profile.preferred_tags:
        if tag and tag.lower() in tag_set:
            score += 8
            reasons.append(f"tag_match:{tag}")
    for tag in tags:
        if tag and tag in prompt:
            score += 5
            reasons.append(f"prompt_tag_match:{tag}")

    if not _as_bool(track.get("vocal"), False):
        score += 20
        reasons.append("instrumental")
    if _as_bool(track.get("commercial_use"), True):
        score += 25
        reasons.append("commercial_use")
    if _as_bool(track.get("derivative_allowed"), True):
        score += 15
        reasons.append("derivative_allowed")
    if not _as_bool(track.get("attribution_required"), False):
        score += 15
        reasons.append("no_attribution_required")
    if video_duration and duration >= video_duration:
        score += 10
        reasons.append("covers_video_duration")
    if 30 <= duration <= 180:
        score += 8
        reasons.append("short_video_duration_fit")
    if _as_bool(track.get("loopable"), False):
        score += 5
        reasons.append("loopable")

    for tag in bgm_profile.avoid_tags:
        if tag and tag.lower() in tag_set:
            score -= 12
            reasons.append(f"avoid_tag_penalty:{tag}")
    if video_duration and duration < video_duration * 0.5:
        score -= 10
        reasons.append("too_short_for_video")

    usage_delta, usage_reasons = _usage_penalty(filename, usage_history)
    score += usage_delta
    reasons.extend(usage_reasons)
    return score, reasons


def build_bgm_candidates(
    prompt: str,
    bgm_profile: BgmProfile,
    video_duration: float | None,
    max_candidates: int = 10,
) -> list[BgmCandidate]:
    tracks = _load_tracks()
    if not tracks:
        return []
    usage_history = _load_usage_history()
    candidates: list[BgmCandidate] = []
    for item in tracks:
        filename = str(item.get("filename") or "").strip()
        duration = _duration_for_track(filename, item)
        passes, reason = _passes_hard_filters(item, filename, duration, bgm_profile)
        if not passes:
            logger.debug("Skipping BGM filename=%s reason=%s", filename, reason)
            continue
        normalized_item = dict(item)
        normalized_item["duration"] = duration
        score, score_reasons = score_bgm_track(normalized_item, prompt, bgm_profile, video_duration, usage_history)
        volume = _as_float(item.get("volume"), DEFAULT_VOLUME)
        candidates.append(
            BgmCandidate(
                id="pending",
                filename=filename,
                mood=_normalize_mood(item.get("mood")),
                tags=_clean_tags(item.get("tags")),
                duration=float(duration or 0.0),
                vocal=_as_bool(item.get("vocal"), False),
                loopable=_as_bool(item.get("loopable"), False),
                volume=volume,
                score=score,
                score_reasons=score_reasons,
                attribution_required=_as_bool(item.get("attribution_required"), False),
                attribution=str(item.get("attribution") or "").strip() or None,
                source=str(item.get("source") or "").strip() or None,
            )
        )

    candidates.sort(key=lambda candidate: candidate.score, reverse=True)
    selected = candidates[: max(1, max_candidates)]
    return [candidate.model_copy(update={"id": f"bgm_{index:03d}"}) for index, candidate in enumerate(selected, start=1)]


def _final_volume(candidate: BgmCandidate, bgm_profile: BgmProfile) -> float:
    volume = candidate.volume if candidate.volume > 0 else DEFAULT_VOLUME
    if bgm_profile.volume_hint is not None:
        volume = volume * 0.7 + bgm_profile.volume_hint * 0.3
    mood_ranges = {
        "warm_lifestyle": (0.06, 0.08),
        "calm_clean": (0.06, 0.08),
        "tech_clean": (0.05, 0.08),
        "light_commercial": (0.06, 0.10),
        "energetic_promo": (0.06, 0.12),
    }
    low, high = mood_ranges.get(bgm_profile.bgm_mood, (0.04, 0.12))
    volume = max(low, min(high, volume)) * BGM_VOLUME_GAIN
    return round(max(0.04, min(0.12, volume)), 3)



def _candidate_has_readable_audio(candidate: BgmCandidate) -> bool:
    path = _safe_track_path(candidate.filename)
    if path is None:
        return False
    try:
        return run_ffprobe_duration(path) > 0
    except Exception as exc:
        logger.warning("Skipping selected BGM candidate with unreadable audio filename=%s error=%s", candidate.filename, exc)
        return False


def finalize_bgm_selection(
    candidates: list[BgmCandidate],
    bgm_profile: BgmProfile,
    selected_bgm_id: str | None = None,
    method: str = "deterministic_top_score",
    reason: str = "highest deterministic score",
    confidence: float | None = None,
    fallback_reason: str | None = None,
) -> BgmSelection:
    if not candidates:
        return BgmSelection(
            config=BgmConfig(enabled=False),
            metadata={
                "method": "disabled",
                "fallback_used": False,
                "bgm_profile": bgm_profile.model_dump(),
                "candidate_count": 0,
                "reason": "no usable BGM candidates",
            },
        )

    available = [candidate for candidate in candidates if _candidate_has_readable_audio(candidate)]
    if not available:
        return BgmSelection(
            config=BgmConfig(enabled=False),
            metadata={
                "method": "disabled",
                "fallback_used": True,
                "fallback_reason": "selected files unavailable",
                "bgm_profile": bgm_profile.model_dump(),
                "candidate_count": len(candidates),
            },
        )

    fallback_used = bool(fallback_reason)
    selected = None
    if selected_bgm_id:
        selected = next((candidate for candidate in available if candidate.id == selected_bgm_id), None)
        if selected is None:
            fallback_used = True
            fallback_reason = fallback_reason or "llm_selected_bgm_id_not_in_candidates"
    if selected is None:
        selected = available[0]

    volume = _final_volume(selected, bgm_profile)
    record_bgm_usage(selected.filename)
    metadata = {
        "method": method,
        "fallback_used": fallback_used,
        "fallback_reason": fallback_reason,
        "bgm_profile": bgm_profile.model_dump(),
        "candidate_count": len(candidates),
        "selected_bgm_id": selected.id,
        "selected_filename": selected.filename,
        "selected_mood": selected.mood,
        "selected_tags": selected.tags,
        "score": selected.score,
        "score_reasons": selected.score_reasons[:12],
        "reason": reason,
        "confidence": confidence,
        "volume": volume,
    }
    if selected.attribution_required:
        metadata["attribution_required"] = True
        metadata["attribution"] = selected.attribution
    return BgmSelection(config=BgmConfig(enabled=True, filename=selected.filename, volume=volume), metadata=metadata)


def select_bgm(prompt: str, bgm_mood: str, auto_bgm: bool) -> BgmConfig:
    if not auto_bgm:
        return BgmConfig(enabled=False)
    bgm_profile = BgmProfile(bgm_mood=bgm_mood)
    try:
        candidates = build_bgm_candidates(prompt, bgm_profile, video_duration=None, max_candidates=1)
        return finalize_bgm_selection(candidates, bgm_profile).config
    except Exception as exc:
        logger.warning("Deterministic BGM selection failed: %s", exc)
        return BgmConfig(enabled=False)
