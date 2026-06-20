from __future__ import annotations

import logging
import re
from typing import Any

from app.config import get_deepseek_api_key, get_llm_provider
from app.deepseek_client import DeepSeekError, generate_marketing_json
from app.keyword_overlay import EFFECT_FALLBACK, EFFECT_POSITION, EFFECT_STYLE, EFFECTS, INTENSITIES, KEYWORD_TYPES, POSITIONS
from app.schemas import BgmProfile, KeywordOverlay, SmartScriptResult

logger = logging.getLogger(__name__)

BANNED_PHRASES = ("最强", "第一", "100%", "永久", "彻底", "保证", "治愈", "根治")
DEFAULT_SCRIPT = [
    "这款产品适合日常使用场景。",
    "整体设计简洁实用，上手比较轻松。",
    "如果你正在挑选同类产品，可以重点看看它。",
]


KEYWORD_HINTS = {
    "experience": ("柔软", "舒适", "舒服", "轻便", "透气", "顺滑", "好穿", "轻松", "不压脚"),
    "function": ("防滑", "防水", "耐磨", "加厚", "稳", "密封", "承重", "防漏"),
    "scene": ("宿舍", "浴室", "厨房", "办公", "桌面", "家居", "室内", "通勤"),
    "promo": ("优惠", "限时", "活动", "促销", "新品", "折扣"),
    "spec": ("迷你", "小巧", "大容量", "尺寸", "容量", "加大", "便携"),
}

DEFAULT_BGM_AVOID_TAGS = ["dark", "sad", "epic", "vocal", "人声", "悲伤", "恐怖", "战斗"]


def _default_bgm_profile(bgm_mood: str, keywords: list[str] | None = None) -> BgmProfile:
    return BgmProfile(
        bgm_mood=bgm_mood,
        preferred_tags=(keywords or [])[:6],
        avoid_tags=DEFAULT_BGM_AVOID_TAGS,
        volume_hint=0.08,
    )


def _keyword_profile(keyword: str) -> tuple[str, str, str, str, str]:
    for keyword_type, hints in KEYWORD_HINTS.items():
        if any(hint in keyword for hint in hints):
            effect = EFFECT_FALLBACK[keyword_type]
            position = EFFECT_POSITION[effect]
            style = EFFECT_STYLE[effect]
            intensity = "high" if effect == "promo_flash" else "medium"
            if keyword_type == "scene":
                intensity = "low"
            reason = f"{keyword_type} 类卖点，使用 {effect} 预设"
            return keyword_type, effect, position, style, intensity, reason
    effect = EFFECT_FALLBACK["generic"]
    return "generic", effect, EFFECT_POSITION[effect], EFFECT_STYLE[effect], "medium", "通用卖点，使用默认花字预设"


def _safe_enum(value: Any, allowed: set[str], default: str) -> str:
    text = str(value or "").strip()
    return text if text in allowed else default


def _clean_text(value: Any, max_len: int) -> str:
    text = str(value or "").strip()
    for phrase in BANNED_PHRASES:
        text = text.replace(phrase, "")
    text = re.sub(r"\s+", "", text)
    return text[:max_len]


def _detect_style(prompt: str) -> tuple[str, str, list[str]]:
    text = prompt.lower()
    if any(word in prompt for word in ("促销", "优惠", "限时", "活动")):
        return "限时好物推荐", "energetic_promo", ["活动价", "实用好物", "轻松入手", "日常适用"]
    if any(word in prompt for word in ("测评", "推荐", "真实", "体验")):
        return "真实体验分享", "light_commercial", ["真实体验", "上手轻松", "细节实用", "日常推荐"]
    if any(word in prompt for word in ("数码", "耳机", "手机", "电脑", "充电宝")) or "tech" in text:
        return "数码好物推荐", "tech_clean", ["简洁设计", "稳定好用", "轻便随身", "通勤适合"]
    if any(word in prompt for word in ("家居", "拖鞋", "收纳", "日用", "厨房", "宿舍")):
        return "居家好物推荐", "warm_lifestyle", ["居家实用", "轻松好穿", "宿舍适合", "日常方便"]
    return "产品卖点推荐", "light_commercial", ["实用设计", "日常适合", "轻松上手", "细节加分"]


def _fallback_generate(prompt: str, template: str, asset_count: int) -> SmartScriptResult:
    title, mood, keywords = _detect_style(prompt)
    prompt_text = _clean_text(prompt, 80)
    if "拖鞋" in prompt:
        script = [
            "夏天宿舍和浴室，拖鞋更要选得稳一点。",
            "这款主打柔软踩感，日常穿着更轻松。",
            "底部防滑纹理清晰，室内浴室都适合。",
            "轻便好穿不压脚，居家通勤都方便。",
            "想找实用拖鞋，可以重点看看这款。",
        ]
        keywords = ["柔软踩感", "防滑纹理", "轻便好穿", "宿舍浴室"]
        title = "夏季防滑拖鞋"
        mood = "warm_lifestyle"
    elif "促销" in prompt or "优惠" in prompt or template == "promo_sale":
        script = [
            "这款好物适合日常高频使用。",
            "现在关注重点卖点，挑选时更省心。",
            "整体实用性不错，适合近期入手参考。",
            "喜欢简单耐用的朋友，可以看看这款。",
        ]
    elif "数码" in prompt or "耳机" in prompt or "手机" in prompt:
        script = [
            "这款数码好物主打简洁实用。",
            "日常通勤和办公场景都比较合适。",
            "操作体验清爽，上手门槛不高。",
            "如果想找轻便设备，可以重点看看。",
        ]
    else:
        script = [
            f"这款产品适合{prompt_text[:14] or '日常'}场景。",
            "整体设计偏实用，日常使用更省心。",
            "细节处理比较清楚，上手体验轻松。",
            "如果你正在对比同类产品，可以看看它。",
        ]
    overlays = _build_default_overlays(keywords, max(1, asset_count))
    return SmartScriptResult(
        title=title,
        script=script[:8],
        bgm_mood=mood,
        bgm_profile=_default_bgm_profile(mood, keywords),
        style_notes="规则 fallback 生成，安全克制的营销文案",
        product_keywords=keywords[:6],
        keyword_overlays=overlays,
    )


def _build_default_overlays(keywords: list[str], asset_count: int) -> list[KeywordOverlay]:
    overlays: list[KeywordOverlay] = []
    for index, keyword in enumerate(keywords[:4]):
        text = _clean_text(keyword, 8) or f"卖点{index + 1}"
        keyword_type, effect, position, style, intensity, reason = _keyword_profile(text)
        overlays.append(
            KeywordOverlay(
                text=text,
                segment_index=min(index, max(0, asset_count - 1)),
                start_ratio=0.20,
                end_ratio=0.75,
                keyword_type=keyword_type,
                style=style,
                effect=effect,
                position=position,
                intensity=intensity,
                reason=reason,
            )
        )
    return overlays


def _normalize_script(items: Any) -> list[str]:
    if not isinstance(items, list):
        items = []
    script = [_clean_text(item, 80) for item in items]
    script = [item for item in script if item]
    if len(script) < 3:
        script.extend(DEFAULT_SCRIPT[len(script) :])
    return script[:8]


def _normalize_keywords(items: Any, script: list[str]) -> list[str]:
    if not isinstance(items, list):
        items = []
    keywords = [_clean_text(item, 10) for item in items]
    keywords = [item for item in keywords if item]
    if len(keywords) < 3:
        for sentence in script:
            candidate = _clean_text(sentence[:6], 10)
            if candidate and candidate not in keywords:
                keywords.append(candidate)
            if len(keywords) >= 3:
                break
    return keywords[:6]


def _normalize_bgm_profile(raw_profile: Any, bgm_mood: str, keywords: list[str]) -> BgmProfile:
    if isinstance(raw_profile, dict):
        payload = dict(raw_profile)
        payload.setdefault("bgm_mood", bgm_mood)
        payload.setdefault("preferred_tags", keywords[:6])
        payload.setdefault("avoid_tags", DEFAULT_BGM_AVOID_TAGS)
        try:
            return BgmProfile.model_validate(payload)
        except Exception as exc:
            logger.warning("BGM profile normalization failed, using default profile: %s", exc)
    return _default_bgm_profile(bgm_mood, keywords)


def _normalize_overlays(items: Any, keywords: list[str], asset_count: int) -> list[KeywordOverlay]:
    overlays: list[KeywordOverlay] = []
    keyword_set = {_clean_text(keyword, 8) for keyword in keywords}
    keyword_set = {keyword for keyword in keyword_set if keyword}
    if isinstance(items, list):
        for item in items:
            if not isinstance(item, dict):
                continue
            text = _clean_text(item.get("text"), 8)
            if not text:
                continue
            if keyword_set and text not in keyword_set and not any(keyword.startswith(text) or text.startswith(keyword) for keyword in keyword_set):
                text = next(iter(keyword_set))
            try:
                segment_index = int(item.get("segment_index", 0))
            except (TypeError, ValueError):
                segment_index = 0
            try:
                start_ratio = float(item.get("start_ratio", 0.20))
                end_ratio = float(item.get("end_ratio", 0.75))
            except (TypeError, ValueError):
                start_ratio, end_ratio = 0.20, 0.75
            if not 0.0 <= start_ratio <= 1.0 or not 0.0 <= end_ratio <= 1.0 or end_ratio <= start_ratio:
                start_ratio, end_ratio = 0.20, 0.75

            inferred_type, inferred_effect, inferred_position, inferred_style, inferred_intensity, inferred_reason = _keyword_profile(text)
            keyword_type = _safe_enum(item.get("keyword_type"), KEYWORD_TYPES, inferred_type)
            effect = _safe_enum(item.get("effect"), EFFECTS, EFFECT_FALLBACK[keyword_type])
            style = _safe_enum(item.get("style"), {"pop", "clean", "badge"}, EFFECT_STYLE.get(effect, inferred_style))
            position = _safe_enum(item.get("position"), POSITIONS, EFFECT_POSITION.get(effect, inferred_position))
            intensity = _safe_enum(item.get("intensity"), INTENSITIES, inferred_intensity)
            reason = str(item.get("reason") or inferred_reason).strip()[:200]
            overlays.append(
                KeywordOverlay(
                    text=text,
                    segment_index=max(0, min(max(0, asset_count - 1), segment_index)),
                    start_ratio=start_ratio,
                    end_ratio=end_ratio,
                    keyword_type=keyword_type,
                    style=style,
                    effect=effect,
                    position=position,
                    intensity=intensity,
                    reason=reason,
                )
            )
            if len(overlays) >= 4:
                break
    if not overlays:
        overlays = _build_default_overlays(keywords, asset_count)
    elif len(overlays) < 2:
        existing = {overlay.text for overlay in overlays}
        for fallback in _build_default_overlays(keywords, asset_count):
            if fallback.text not in existing:
                overlays.append(fallback)
                existing.add(fallback.text)
            if len(overlays) >= 2:
                break
    return overlays[:4]


def _normalize_result(data: dict[str, Any], asset_count: int) -> SmartScriptResult:
    script = _normalize_script(data.get("script"))
    keywords = _normalize_keywords(data.get("product_keywords"), script)
    overlays = _normalize_overlays(data.get("keyword_overlays"), keywords, max(1, asset_count))
    title = _clean_text(data.get("title"), 24) or "好物推荐"
    bgm_mood = _clean_text(data.get("bgm_mood"), 80) or "light_commercial"
    bgm_profile = _normalize_bgm_profile(data.get("bgm_profile"), bgm_mood, keywords)
    bgm_mood = bgm_profile.bgm_mood
    style_notes = str(data.get("style_notes") or "").strip()[:300]
    return SmartScriptResult(
        title=title,
        script=script,
        bgm_mood=bgm_mood,
        bgm_profile=bgm_profile,
        style_notes=style_notes,
        product_keywords=keywords,
        keyword_overlays=overlays,
    )


def generate_smart_script(prompt: str, template: str, asset_count: int) -> SmartScriptResult:
    provider = get_llm_provider()
    if provider == "none":
        return _fallback_generate(prompt, template, asset_count)
    if provider != "deepseek":
        logger.warning("Unsupported LLM_PROVIDER=%s; using fallback generator", provider)
        return _fallback_generate(prompt, template, asset_count)
    if not get_deepseek_api_key():
        logger.info("DeepSeek API key is not configured; using fallback generator")
        return _fallback_generate(prompt, template, asset_count)
    try:
        data = generate_marketing_json(prompt, template, asset_count)
        return _normalize_result(data, asset_count)
    except DeepSeekError as exc:
        logger.warning("DeepSeek generation failed, using fallback generator: %s", exc)
        return _fallback_generate(prompt, template, asset_count)
    except Exception as exc:
        logger.warning("Smart script normalization failed, using fallback generator: %s", exc)
        return _fallback_generate(prompt, template, asset_count)
