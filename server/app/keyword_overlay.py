from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

from app.schemas import KeywordOverlay

KEYWORD_TYPES = {"experience", "function", "scene", "promo", "spec", "generic"}
EFFECTS = {"soft_float", "strong_badge", "clean_scene", "promo_flash", "spec_corner", "default_pop"}
# LLM may choose an artistic preset, but only from combinations the renderer owns.
ALLOWED_EFFECTS_BY_TYPE = {
    "experience": {"soft_float", "default_pop", "clean_scene"},
    "function": {"strong_badge", "default_pop", "spec_corner"},
    "scene": {"clean_scene", "soft_float", "spec_corner"},
    "promo": {"promo_flash", "strong_badge", "default_pop"},
    "spec": {"spec_corner", "strong_badge", "clean_scene"},
    "generic": {"default_pop", "soft_float", "clean_scene"},
}
POSITIONS = {"center_upper", "center", "right_upper", "left_middle", "corner_right", "corner_left"}
INTENSITIES = {"low", "medium", "high"}
STYLES = {"pop", "clean", "badge"}

EFFECT_FALLBACK = {
    "experience": "soft_float",
    "function": "strong_badge",
    "scene": "clean_scene",
    "promo": "promo_flash",
    "spec": "spec_corner",
    "generic": "default_pop",
}

EFFECT_KEYWORD_TYPE = {
    "soft_float": "experience",
    "strong_badge": "function",
    "clean_scene": "scene",
    "promo_flash": "promo",
    "spec_corner": "spec",
    "default_pop": "generic",
}

EFFECT_STYLE = {
    "soft_float": "clean",
    "strong_badge": "badge",
    "clean_scene": "clean",
    "promo_flash": "pop",
    "spec_corner": "badge",
    "default_pop": "pop",
}

EFFECT_POSITION = {
    "soft_float": "center_upper",
    "strong_badge": "center",
    "clean_scene": "left_middle",
    "promo_flash": "center",
    "spec_corner": "corner_right",
    "default_pop": "center_upper",
}

_URL_RE = re.compile(r"https?://\S+|www\.\S+", re.IGNORECASE)
_CONTROL_RE = re.compile(r"[\x00-\x1f\x7f]")
_EMOJI_RE = re.compile("[\U0001F300-\U0001FAFF\U00002700-\U000027BF]")
_UNSAFE_TEXT_RE = re.compile(r"[{}<>|`$\\]")
_LONG_PUNCT_RE = re.compile(r"[，。！？、,.!?]{2,}")


@dataclass(frozen=True)
class ValidatedKeywordOverlay:
    text: str
    segment_index: int
    start: float
    end: float
    keyword_type: str
    style: str
    effect: str
    position: str
    intensity: str
    reason: str | None = None


def _drawtext_path(path: Path) -> str:
    return path.as_posix().replace("\\", "\\\\").replace(":", "\\:").replace("'", r"\'")


def _clean_overlay_text(value: str) -> str:
    text = _URL_RE.sub("", str(value or ""))
    text = _CONTROL_RE.sub("", text)
    text = _EMOJI_RE.sub("", text)
    text = _UNSAFE_TEXT_RE.sub("", text)
    text = _LONG_PUNCT_RE.sub("", text)
    text = re.sub(r"\s+", "", text).strip("'\"：:；;，,。.!！?？、")
    return text[:8]


def _clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def _segment_starts(segment_durations: list[float]) -> list[float]:
    starts: list[float] = []
    current = 0.0
    for duration in segment_durations:
        starts.append(current)
        current += max(0.1, duration)
    return starts


def _overlay_limit(total_duration: float) -> int:
    if total_duration <= 12:
        return 2
    if total_duration <= 25:
        return 3
    return 4


def _coerce_keyword_type(value: str) -> str:
    return value if value in KEYWORD_TYPES else "generic"


def _coerce_effect(keyword_type: str, effect: str) -> str:
    keyword_type = _coerce_keyword_type(keyword_type)
    if effect not in EFFECTS:
        return EFFECT_FALLBACK[keyword_type]
    if effect not in ALLOWED_EFFECTS_BY_TYPE[keyword_type]:
        return EFFECT_FALLBACK[keyword_type]
    return effect


def _coerce_position(effect: str, position: str) -> str:
    if position not in POSITIONS:
        return EFFECT_POSITION.get(effect, "center_upper")
    return position


def _coerce_intensity(effect: str, intensity: str) -> str:
    if intensity not in INTENSITIES:
        intensity = "medium"
    if intensity == "high" and effect not in {"promo_flash", "strong_badge"}:
        return "medium"
    return intensity


def _timing_for_overlay(
    overlay: KeywordOverlay,
    segment_index: int,
    segment_starts: list[float],
    segment_durations: list[float],
    total_duration: float,
) -> tuple[float, float]:
    segment_duration = max(0.1, segment_durations[segment_index])
    segment_start = segment_starts[segment_index]
    segment_end = min(total_duration, segment_start + segment_duration)
    start_ratio = overlay.start_ratio
    end_ratio = overlay.end_ratio
    if not 0.0 <= start_ratio <= 1.0 or not 0.0 <= end_ratio <= 1.0 or end_ratio <= start_ratio:
        start_ratio, end_ratio = 0.20, 0.75
    start = segment_start + segment_duration * start_ratio
    raw_end = segment_start + segment_duration * end_ratio
    duration = _clamp(raw_end - start, 1.2, 3.0)
    end = min(segment_end, start + duration)
    if end - start < min(1.2, segment_duration):
        start = max(segment_start, end - min(1.2, segment_duration))
    return max(0.0, start), max(0.0, end)


def validate_keyword_overlays(
    overlays: list[KeywordOverlay],
    segment_count: int,
    segment_durations: list[float],
    total_duration: float,
) -> list[ValidatedKeywordOverlay]:
    if not overlays or segment_count <= 0 or not segment_durations or total_duration <= 0:
        return []

    segment_starts = _segment_starts(segment_durations)
    max_items = min(_overlay_limit(total_duration), segment_count)
    used_segments: set[int] = set()
    validated: list[ValidatedKeywordOverlay] = []

    for overlay in overlays:
        if len(validated) >= max_items:
            break
        text = _clean_overlay_text(overlay.text)
        if not text:
            continue
        segment_index = max(0, min(segment_count - 1, overlay.segment_index))
        if segment_index in used_segments:
            continue
        keyword_type = _coerce_keyword_type(overlay.keyword_type)
        effect = _coerce_effect(keyword_type, overlay.effect)
        style = EFFECT_STYLE.get(effect, overlay.style if overlay.style in STYLES else "pop")
        position = _coerce_position(effect, overlay.position)
        intensity = _coerce_intensity(effect, overlay.intensity)
        start, end = _timing_for_overlay(overlay, segment_index, segment_starts, segment_durations, total_duration)
        if end <= start:
            continue

        if validated:
            previous_end = validated[-1].end
            if start < previous_end + 0.4:
                shift = previous_end + 0.4 - start
                segment_end = min(total_duration, segment_starts[segment_index] + segment_durations[segment_index])
                if end + shift <= segment_end:
                    start += shift
                    end += shift
                else:
                    continue

        used_segments.add(segment_index)
        validated.append(
            ValidatedKeywordOverlay(
                text=text,
                segment_index=segment_index,
                start=start,
                end=end,
                keyword_type=keyword_type,
                style=style,
                effect=effect,
                position=position,
                intensity=intensity,
                reason=overlay.reason,
            )
        )
    return validated


def _font_size(effect: str, intensity: str, width: int) -> int:
    scale = width / 1080
    base = {
        "soft_float": 70,
        "strong_badge": 82,
        "clean_scene": 62,
        "promo_flash": 88,
        "spec_corner": 58,
        "default_pop": 76,
    }.get(effect, 70)
    delta = {"low": -6, "medium": 0, "high": 8}.get(intensity, 0)
    return max(34, int((base + delta) * scale))


def _position_expr(position: str, width: int, height: int) -> tuple[str, str]:
    if position == "center":
        return "(w-text_w)/2", str(int(height * 0.48))
    if position == "right_upper":
        return "w-text_w-90", str(int(height * 0.32))
    if position == "left_middle":
        return str(int(width * 0.12)), str(int(height * 0.45))
    if position == "corner_right":
        return "w-text_w-90", str(int(height * 0.28))
    if position == "corner_left":
        return "90", str(int(height * 0.28))
    return "(w-text_w)/2", str(int(height * 0.35))


def _effect_palette(effect: str) -> dict[str, str]:
    if effect == "soft_float":
        return {"fill": "0xFFF7B8", "stroke": "0xFF63B8", "glow": "0xFF8AD8", "accent": "0x8EF7FF", "shadow": "0x3E1064"}
    if effect == "strong_badge":
        return {"fill": "0xFFF06A", "stroke": "0xFF3D4A", "glow": "0xFF8A00", "accent": "0x00D1FF", "shadow": "0x4A1200"}
    if effect == "clean_scene":
        return {"fill": "0xF5FFF8", "stroke": "0x35D07F", "glow": "0x6DF2B2", "accent": "0x9AF7FF", "shadow": "0x0B4230"}
    if effect == "promo_flash":
        return {"fill": "0xFFFFFF", "stroke": "0xFF2D55", "glow": "0xFFD400", "accent": "0xFF7A00", "shadow": "0x4F001C"}
    if effect == "spec_corner":
        return {"fill": "0xEFFFFF", "stroke": "0x1677FF", "glow": "0x72E7FF", "accent": "0xFFE66D", "shadow": "0x00224A"}
    return {"fill": "0xFFFFFF", "stroke": "0xFF4DA6", "glow": "0xFFE45C", "accent": "0x70E1FF", "shadow": "0x33104A"}


def _motion_y(base_y: str, overlay: ValidatedKeywordOverlay) -> str:
    if overlay.effect == "soft_float":
        return f"{base_y}-18*sin((t-{overlay.start:.3f})*3.2)"
    if overlay.effect == "promo_flash":
        return f"{base_y}-10*sin((t-{overlay.start:.3f})*8.0)"
    if overlay.effect == "default_pop":
        return f"{base_y}-10*sin((t-{overlay.start:.3f})*4.5)"
    return base_y


def _add_to_expr(expr: str, offset: int) -> str:
    if offset == 0:
        return expr
    sign = "+" if offset > 0 else ""
    return f"{expr}{sign}{offset}"


def _drawtext_layer(
    text_file: Path,
    font_file: Path | None,
    fontsize: int,
    x: str,
    y: str,
    start: float,
    end: float,
    fontcolor: str,
    bordercolor: str,
    borderw: int,
    shadowcolor: str,
    shadowx: int,
    shadowy: int,
) -> str:
    options: list[str] = []
    if font_file is not None:
        options.append(f"fontfile='{_drawtext_path(font_file)}'")
    options.extend(
        [
            f"textfile='{_drawtext_path(text_file)}'",
            f"fontsize={fontsize}",
            f"fontcolor={fontcolor}",
            f"borderw={borderw}",
            f"bordercolor={bordercolor}",
            f"shadowx={shadowx}",
            f"shadowy={shadowy}",
            f"shadowcolor={shadowcolor}",
            "box=0",
            f"x={x}",
            f"y={y}",
            f"enable='between(t,{start:.3f},{end:.3f})'",
        ]
    )
    return "drawtext=" + ":".join(str(option) for option in options)


def _preset_layers(
    overlay: ValidatedKeywordOverlay,
    text_file: Path,
    font_file: Path | None,
    width: int,
    height: int,
) -> list[str]:
    x, y = _position_expr(overlay.position, width, height)
    y = _motion_y(y, overlay)
    palette = _effect_palette(overlay.effect)
    fontsize = _font_size(overlay.effect, overlay.intensity, width)
    glow_border = 13 if overlay.effect in {"strong_badge", "promo_flash"} else 10
    main_border = 5 if overlay.effect in {"strong_badge", "promo_flash", "default_pop"} else 4
    return [
        _drawtext_layer(
            text_file,
            font_file,
            fontsize + 6,
            x,
            y,
            overlay.start,
            overlay.end,
            f"{palette['glow']}@0.18",
            f"{palette['glow']}@0.88",
            glow_border,
            f"{palette['shadow']}@0.20",
            0,
            0,
        ),
        _drawtext_layer(
            text_file,
            font_file,
            fontsize,
            _add_to_expr(x, 7),
            _add_to_expr(y, 7),
            overlay.start,
            overlay.end,
            f"{palette['accent']}@0.78",
            f"{palette['shadow']}@0.72",
            4,
            f"{palette['shadow']}@0.42",
            2,
            2,
        ),
        _drawtext_layer(
            text_file,
            font_file,
            fontsize,
            x,
            y,
            overlay.start,
            overlay.end,
            palette["fill"],
            f"{palette['stroke']}@0.98",
            main_border,
            f"{palette['shadow']}@0.50",
            3,
            4,
        ),
    ]


def build_keyword_overlay_filter_chain(
    overlays: list[ValidatedKeywordOverlay],
    temp_dir: Path,
    width: int,
    height: int,
    font_file: Path | None,
) -> str:
    if not overlays:
        return ""

    filters: list[str] = []
    for index, overlay in enumerate(overlays):
        text_file = temp_dir / f"keyword_overlay_{index:03d}.txt"
        text_file.write_text(overlay.text, encoding="utf-8")
        filters.extend(_preset_layers(overlay, text_file, font_file, width, height))
    return ",".join(filters)
