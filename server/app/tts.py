from __future__ import annotations

import asyncio
import logging
from pathlib import Path

import edge_tts

from app.config import get_tts_proxy_url
from app.ffmpeg_utils import run_ffprobe_duration
from app.schemas import VoiceConfig

logger = logging.getLogger(__name__)
TTS_RETRY_ATTEMPTS = 3


async def synthesize_speech(
    script: list[str],
    voice_config: VoiceConfig,
    output_path: Path,
) -> float:
    text = "\n".join(sentence.strip() for sentence in script if sentence.strip())
    if not text:
        raise RuntimeError("TTS script is empty")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    proxy = get_tts_proxy_url()
    if proxy:
        logger.debug("edge-tts proxy is configured through environment")
    last_error: Exception | None = None
    for attempt in range(1, TTS_RETRY_ATTEMPTS + 1):
        try:
            if output_path.exists():
                output_path.unlink()
            communicate = edge_tts.Communicate(
                text=text,
                voice=voice_config.speaker,
                rate=voice_config.rate,
                volume=voice_config.volume,
                connect_timeout=30,
                receive_timeout=120,
            )
            await communicate.save(str(output_path))
            break
        except Exception as exc:
            last_error = exc
            logger.warning(
                "edge-tts synthesis attempt %s/%s failed: %s",
                attempt,
                TTS_RETRY_ATTEMPTS,
                exc,
            )
            if attempt < TTS_RETRY_ATTEMPTS:
                await asyncio.sleep(1.5 * attempt)
    else:
        raise RuntimeError(f"edge-tts synthesis failed after retries: {last_error}") from last_error

    if not output_path.exists() or output_path.stat().st_size == 0:
        raise RuntimeError("edge-tts did not produce an audio file")

    try:
        return run_ffprobe_duration(output_path)
    except Exception as exc:
        raise RuntimeError(f"TTS audio was generated but duration probing failed: {exc}") from exc
