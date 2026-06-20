from __future__ import annotations

from pathlib import Path
from typing import Any


SUBTITLE_SAFE_WIDTH_RATIO = 0.82
SUBTITLE_FONT_SCALE = 0.80


def escape_ass_text(text: str) -> str:
    return (
        text.replace("\\", r"\\")
        .replace("{", r"\{")
        .replace("}", r"\}")
        .replace("\n", r"\N")
        .replace("\r", "")
    )


def _format_ass_time(seconds: float) -> str:
    seconds = max(0.0, seconds)
    hours = int(seconds // 3600)
    minutes = int((seconds % 3600) // 60)
    whole_seconds = int(seconds % 60)
    centiseconds = int(round((seconds - int(seconds)) * 100))
    if centiseconds >= 100:
        whole_seconds += 1
        centiseconds -= 100
    return f"{hours}:{minutes:02d}:{whole_seconds:02d}.{centiseconds:02d}"


def split_script_to_subtitle_events(
    script: list[str],
    total_duration: float,
) -> list[tuple[float, float, str]]:
    cleaned = [line.strip() for line in script if line.strip()]
    if not cleaned:
        return []

    total_duration = max(total_duration, 0.1)
    weights = [max(1, len(line)) for line in cleaned]
    total_weight = sum(weights)
    durations = [total_duration * weight / total_weight for weight in weights]
    durations = [min(6.0, max(1.2, duration)) for duration in durations]

    current_total = sum(durations)
    if current_total > 0 and abs(current_total - total_duration) > 0.01:
        scale = total_duration / current_total
        durations = [duration * scale for duration in durations]

    events: list[tuple[float, float, str]] = []
    cursor = 0.0
    for index, (duration, text) in enumerate(zip(durations, cleaned)):
        start = cursor
        if index == len(cleaned) - 1:
            end = total_duration
        else:
            end = min(total_duration, cursor + max(0.05, duration))
        events.append((start, end, text))
        cursor = end
    return events



def subtitle_max_chars_per_line(width: int, font_size: int) -> int:
    # CJK glyphs are close to one em wide; keep extra side margin for outlines and player cropping.
    safe_width = max(1, int(width * SUBTITLE_SAFE_WIDTH_RATIO))
    return max(6, min(18, int(safe_width / max(1, font_size * 0.96))))


def wrap_subtitle_text(text: str, max_chars_per_line: int) -> str:
    cleaned = text.strip()
    if not cleaned:
        return ""
    max_chars = max(6, max_chars_per_line)
    return "\n".join(cleaned[index : index + max_chars] for index in range(0, len(cleaned), max_chars))



def _subtitle_style(width: int, height: int, template_config: dict[str, Any]) -> tuple[str, int]:
    subtitle_config = template_config.get("subtitle", {})
    font_size = int(subtitle_config.get("font_size", 83))
    if width <= 720:
        font_size = max(32, int(font_size * 0.72))
    bottom_margin = int(height - int(subtitle_config.get("y", int(height * 0.82))))
    bottom_margin = max(80, bottom_margin)
    font_name = subtitle_config.get("font", "Source Han Sans SC")
    outline = int(subtitle_config.get("outline", 3))
    shadow = int(subtitle_config.get("shadow", 1))
    style = (
        f"Style: Default,{font_name},{font_size},&H00FFFFFF,&H000000FF,&H00000000,"
        f"&H80000000,0,0,0,0,100,100,0,0,1,{outline},{shadow},2,60,60,{bottom_margin},1"
    )
    return style, font_size


def generate_ass_subtitle(
    script: list[str],
    total_duration: float,
    output_path: Path,
    width: int,
    height: int,
    template_config: dict[str, Any],
) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    style_line, font_size = _subtitle_style(width, height, template_config)
    events = split_script_to_subtitle_events(script, total_duration)

    lines = [
        "[Script Info]",
        "ScriptType: v4.00+",
        "Collisions: Normal",
        f"PlayResX: {width}",
        f"PlayResY: {height}",
        "WrapStyle: 0",
        "ScaledBorderAndShadow: yes",
        "",
        "[V4+ Styles]",
        (
            "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, "
            "OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, "
            "ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, "
            "MarginL, MarginR, MarginV, Encoding"
        ),
        style_line,
        "",
        "[Events]",
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    ]
    for start, end, text in events:
        lines.append(
            "Dialogue: 0,"
            f"{_format_ass_time(start)},{_format_ass_time(end)},Default,,0,0,0,,"
            f"{escape_ass_text(wrap_subtitle_text(text, subtitle_max_chars_per_line(width, font_size)))}"
        )

    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

