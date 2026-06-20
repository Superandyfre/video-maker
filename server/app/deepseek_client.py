from __future__ import annotations

import json
import logging
from typing import Any

import httpx

from app.config import (
    DEEPSEEK_BASE_URL,
    DEEPSEEK_MODEL,
    LLM_TIMEOUT_SECONDS,
    get_deepseek_api_key,
)

from app.schemas import BgmCandidate, BgmDecision, BgmProfile

logger = logging.getLogger(__name__)


class DeepSeekError(RuntimeError):
    pass


def _extract_json_object(text: str) -> dict[str, Any]:
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = cleaned.strip("`").strip()
        if cleaned.lower().startswith("json"):
            cleaned = cleaned[4:].strip()
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start < 0 or end < start:
        raise DeepSeekError("DeepSeek response did not contain a JSON object")
    try:
        data = json.loads(cleaned[start : end + 1])
    except json.JSONDecodeError as exc:
        raise DeepSeekError(f"DeepSeek response JSON parse failed: {exc}") from exc
    if not isinstance(data, dict):
        raise DeepSeekError("DeepSeek response JSON root must be an object")
    return data


def generate_marketing_json(prompt: str, template: str, asset_count: int) -> dict[str, Any]:
    api_key = get_deepseek_api_key()
    if not api_key:
        raise DeepSeekError("DeepSeek API key is not configured")

    system_prompt = (
        "你是一个短视频营销文案、BGM 氛围和艺术花字风格分析器。根据用户描述、模板类型和素材数量，生成安全、克制、"
        "不夸大的中文短视频标题、口播字幕、BGM profile、卖点关键词，并为每个花字从后端白名单中选择艺术花字风格。"
        "你只做语义和风格选择，不输出任何 filename、路径、URL、FFmpeg 参数或音量表达式。"
        "必须输出合法 JSON，不要输出 Markdown，不要输出解释文字。"
    )
    user_prompt = f"""
用户描述：{prompt}
template：{template}
asset_count：{asset_count}
目标平台：通用短视频/电商种草
限制：
- title 不超过 16 个中文字符左右
- script 最少 3 句，最多 8 句
- 每句 8 到 28 个中文字符左右
- product_keywords 3 到 6 个
- bgm_mood 只能是 warm_lifestyle / light_commercial / tech_clean / energetic_promo / calm_clean
- bgm_profile 必须包含 scene, emotion, pace, bgm_mood, preferred_tags, avoid_tags, volume_hint
- bgm_profile.pace 只能是 slow / medium / fast
- bgm_profile.bgm_mood 只能是 warm_lifestyle / light_commercial / tech_clean / energetic_promo / calm_clean
- bgm_profile.preferred_tags 根据营销场景给 3 到 6 个标签，例如 家居、生活、清爽、种草、科技、促销
- bgm_profile.avoid_tags 给 3 到 8 个不适合标签，例如 dark、sad、epic、vocal、恐怖、悲伤
- bgm_profile.volume_hint 只能是 0.04 到 0.12 的数字建议，常用 0.06 到 0.08
- 不允许输出任何 BGM filename、路径、URL、FFmpeg 参数、音量表达式
- keyword_overlays 2 到 4 个
- keyword_overlays 每项包含 text, segment_index, start_ratio, end_ratio, keyword_type, style, effect, position, intensity, reason
- keyword_overlays.text 必须来自 product_keywords，或是 product_keywords 的短化版本，建议 2 到 8 个中文字符
- keyword_type 只能是 experience / function / scene / promo / spec / generic
- style 只能是 pop / clean / badge
- effect 是艺术花字风格，只能是 soft_float / strong_badge / clean_scene / promo_flash / spec_corner / default_pop
- effect 由你根据花字内容、语气和画面场景选择，不要机械地只按 keyword_type 固定映射
- position 只能是 center_upper / center / right_upper / left_middle / corner_right / corner_left
- intensity 只能是 low / medium / high
- reason 只解释为什么这个花字适合该艺术风格，不参与渲染
- 不允许输出 FFmpeg filter、字体路径、颜色代码、x/y 坐标、drawtext 参数、shell 命令、文件路径
- 不使用“最强”“第一”“100%”“永久”“彻底”等绝对化表达
- 不生成医疗功效、虚假承诺、夸大功效
- 不要编造具体认证、价格、销量、品牌授权
- 如果信息不足，生成通用但安全的营销文案
艺术花字风格说明：
- soft_float：粉色糖果描边、柔和发光、轻微漂浮，适合柔软、舒适、好穿、可爱、温柔体验
- strong_badge：黄红高对比冲击描边，适合防滑、防水、耐磨、核心功能和强卖点
- clean_scene：薄荷绿色清爽描边，适合宿舍、浴室、厨房、办公等生活场景
- promo_flash：白黄红促销闪光感，适合优惠、限时、新品、活动，只有 promo 类使用
- spec_corner：蓝色科技角标感，适合迷你、大容量、小巧、尺寸、材质、参数规格
- default_pop：彩虹流行描边，适合通用好物词或语义不明显的卖点
兼容规则：
- experience 可选 soft_float / default_pop / clean_scene
- function 可选 strong_badge / default_pop / spec_corner
- scene 可选 clean_scene / soft_float / spec_corner
- promo 可选 promo_flash / strong_badge / default_pop
- spec 可选 spec_corner / strong_badge / clean_scene
- generic 可选 default_pop / soft_float / clean_scene
只输出 JSON。
""".strip()

    payload = {
        "model": DEEPSEEK_MODEL,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "temperature": 0.7,
        "response_format": {"type": "json_object"},
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    url = f"{DEEPSEEK_BASE_URL}/chat/completions"

    try:
        with httpx.Client(timeout=LLM_TIMEOUT_SECONDS) as client:
            response = client.post(url, headers=headers, json=payload)
            response.raise_for_status()
            body = response.json()
    except Exception as exc:
        logger.warning("DeepSeek request failed: %s", exc)
        raise DeepSeekError(f"DeepSeek request failed: {exc}") from exc

    try:
        content = body["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as exc:
        raise DeepSeekError("DeepSeek response shape is invalid") from exc
    if not isinstance(content, str):
        raise DeepSeekError("DeepSeek response content must be text")
    return _extract_json_object(content)



def _candidate_energy(candidate: BgmCandidate) -> str:
    if candidate.mood == "energetic_promo":
        return "fast"
    if candidate.mood in {"warm_lifestyle", "light_commercial"}:
        return "medium"
    return "slow" if candidate.mood == "calm_clean" else "medium"


def select_bgm_from_candidates(
    prompt: str,
    title: str,
    script: list[str],
    bgm_profile: BgmProfile,
    candidates: list[BgmCandidate],
) -> BgmDecision:
    api_key = get_deepseek_api_key()
    if not api_key:
        raise DeepSeekError("DeepSeek API key is not configured")
    if not candidates:
        raise DeepSeekError("BGM candidates are empty")

    candidate_payload = [
        {
            "id": candidate.id,
            "mood": candidate.mood,
            "tags": candidate.tags[:8],
            "duration": round(candidate.duration, 1),
            "vocal": candidate.vocal,
            "energy": _candidate_energy(candidate),
            "score": round(candidate.score, 2),
        }
        for candidate in candidates
    ]
    script_preview = " ".join(script)[:500]
    system_prompt = (
        "你是短视频营销 BGM 候选选择器。你只能从后端给出的候选 id 中选择 1 个最合适的 BGM。"
        "你不能输出 filename、路径、URL、FFmpeg 参数、音量表达式或任何命令。"
        "必须输出合法 JSON，不要输出 Markdown，不要输出解释文字。"
    )
    user_prompt = f"""
用户描述：{prompt}
标题：{title}
口播文案：{script_preview}
BGM profile：{json.dumps(bgm_profile.model_dump(), ensure_ascii=False)}
候选列表：{json.dumps(candidate_payload, ensure_ascii=False)}

选择要求：
- 只能从候选列表中的 id 里选择
- 如果没有完全合适的，也必须选择最接近的候选
- 只返回 selected_bgm_id、reason、confidence
- selected_bgm_id 必须是候选 id
- reason 只说明语义选择理由，不要包含 filename、路径、URL、授权文本或技术参数
- confidence 是 0 到 1 的数字
""".strip()

    payload = {
        "model": DEEPSEEK_MODEL,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "temperature": 0.2,
        "response_format": {"type": "json_object"},
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    url = f"{DEEPSEEK_BASE_URL}/chat/completions"

    try:
        with httpx.Client(timeout=LLM_TIMEOUT_SECONDS) as client:
            response = client.post(url, headers=headers, json=payload)
            response.raise_for_status()
            body = response.json()
    except Exception as exc:
        logger.warning("DeepSeek BGM selection request failed: %s", exc)
        raise DeepSeekError(f"DeepSeek BGM selection request failed: {exc}") from exc

    try:
        content = body["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as exc:
        raise DeepSeekError("DeepSeek BGM selection response shape is invalid") from exc
    if not isinstance(content, str):
        raise DeepSeekError("DeepSeek BGM selection response content must be text")
    data = _extract_json_object(content)
    try:
        return BgmDecision.model_validate(data)
    except Exception as exc:
        raise DeepSeekError(f"DeepSeek BGM selection JSON is invalid: {exc}") from exc
