from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml

from app.config import TEMPLATE_DIR


class TemplateError(ValueError):
    pass


def validate_template_name(name: str) -> str:
    if not name or "/" in name or "\\" in name or name.startswith("."):
        raise TemplateError("Template name is invalid")
    path = TEMPLATE_DIR / f"{name}.yaml"
    if not path.exists() or not path.is_file():
        raise TemplateError(f"Template does not exist: {name}")
    return name


def load_template(name: str) -> dict[str, Any]:
    validate_template_name(name)
    path = TEMPLATE_DIR / f"{name}.yaml"
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except yaml.YAMLError as exc:
        raise TemplateError(f"Failed to parse template: {name}") from exc
    if data.get("name") != name:
        raise TemplateError(f"Template name mismatch: {name}")
    return data


def list_templates() -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for path in sorted(Path(TEMPLATE_DIR).glob("*.yaml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        canvas = data.get("canvas", {})
        width = canvas.get("width", 1080)
        height = canvas.get("height", 1920)
        result.append(
            {
                "name": data.get("name", path.stem),
                "display_name": data.get("display_name", path.stem),
                "description": data.get("description", ""),
                "ratio": data.get("ratio", f"{width}:{height}"),
            }
        )
    return result

