import asyncio
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.keyword_overlay import validate_keyword_overlays
from app.main import app
from app.schemas import BgmCandidate, BgmDecision, BgmProfile, KeywordOverlay
from app.smart_script import _normalize_result, generate_smart_script
from app.subtitle import subtitle_max_chars_per_line, wrap_subtitle_text
import app.bgm_selector as bgm_selector
import app.jobs as jobs_module
import app.main as main_module
import app.storage as storage_module
import app.updates as updates


client = TestClient(app)


@pytest.fixture(autouse=True)
def clear_api_token(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("API_TOKEN", raising=False)


def test_health() -> None:
    response = client.get("/api/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert "ffmpeg_available" in data
    assert "ffmpeg_path" in data
    assert "ffprobe_available" in data
    assert "ffprobe_path" in data
    assert data["version"] == "0.1.0"


def test_templates() -> None:
    response = client.get("/api/templates")
    assert response.status_code == 200
    data = response.json()
    assert "templates" in data
    assert len(data["templates"]) >= 3
    names = {item["name"] for item in data["templates"]}
    assert {"product_basic", "product_review", "promo_sale"}.issubset(names)


def test_voices() -> None:
    response = client.get("/api/voices")
    assert response.status_code == 200
    data = response.json()
    ids = {item["id"] for item in data}
    assert "zh-CN-XiaoxiaoNeural" in ids
    assert "zh-CN-YunxiNeural" in ids
    assert "zh-CN-YunjianNeural" in ids


def test_capabilities() -> None:
    response = client.get("/api/capabilities")
    assert response.status_code == 200
    data = response.json()
    assert data["max_job_assets"] >= 1
    assert data["max_upload_size_bytes"] == data["max_upload_size_mb"] * 1024 * 1024
    assert "image/jpeg" in data["supported_image_mime_types"]
    assert "video/mp4" in data["supported_video_mime_types"]
    assert "1080x1920" in data["supported_resolutions"]


def test_capabilities_requires_token_when_configured(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("API_TOKEN", "test-token")

    assert client.get("/api/capabilities").status_code == 401
    response = client.get("/api/capabilities", headers={"Authorization": "Bearer test-token"})

    assert response.status_code == 200


def test_android_latest_manifest(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    manifest = tmp_path / "latest.json"
    manifest.write_text(
        """
{
  "versionCode": 11,
  "versionName": "0.1.10",
  "apkUrl": "/downloads/android/video-maker-android-0.1.10.apk",
  "releaseNotes": "新增关于与更新安装功能",
  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
""".strip(),
        encoding="utf-8",
    )
    monkeypatch.setattr(updates, "ANDROID_UPDATE_MANIFEST_PATH", manifest)

    response = client.get("/app/android/latest.json")

    assert response.status_code == 200
    data = response.json()
    assert data["versionCode"] == 11
    assert data["versionName"] == "0.1.10"
    assert data["apkUrl"] == "/downloads/android/video-maker-android-0.1.10.apk"
    assert data["releaseNotes"] == "新增关于与更新安装功能"
    assert data["sha256"] == "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"


def test_android_latest_manifest_missing(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setattr(updates, "ANDROID_UPDATE_MANIFEST_PATH", tmp_path / "missing.json")

    response = client.get("/app/android/latest.json")

    assert response.status_code == 404
    assert response.json()["detail"] == "Android update manifest not found"


def test_android_latest_manifest_requires_token_when_configured(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    manifest = tmp_path / "latest.json"
    manifest.write_text(
        """
{
  "versionCode": 11,
  "versionName": "0.1.10",
  "apkUrl": "/downloads/android/video-maker-android-0.1.10.apk",
  "releaseNotes": "token protected",
  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
""".strip(),
        encoding="utf-8",
    )
    monkeypatch.setattr(updates, "ANDROID_UPDATE_MANIFEST_PATH", manifest)
    monkeypatch.setenv("API_TOKEN", "test-token")

    assert client.get("/app/android/latest.json").status_code == 401
    response = client.get("/app/android/latest.json", headers={"Authorization": "Bearer test-token"})

    assert response.status_code == 200
    assert response.json()["versionCode"] == 11


def test_protected_output_requires_token(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    output_dir = tmp_path / "outputs"
    output_dir.mkdir()
    (output_dir / "sample.mp4").write_bytes(b"video")
    monkeypatch.setattr(main_module, "OUTPUT_DIR", output_dir)
    monkeypatch.setenv("API_TOKEN", "test-token")

    assert client.get("/outputs/sample.mp4").status_code == 401
    response = client.get("/outputs/sample.mp4", headers={"Authorization": "Bearer test-token"})

    assert response.status_code == 200
    assert response.content == b"video"


def test_invalid_job_id_returns_404() -> None:
    response = client.get("/api/jobs/not-a-real-job")
    assert response.status_code == 404


def test_invalid_template_create_job_returns_error() -> None:
    response = client.post(
        "/api/jobs",
        json={
            "template": "missing_template",
            "title": "测试标题",
            "script": ["这是一句测试文案。"],
            "assets": ["missing-file-id"],
            "voice": {"speaker": "zh-CN-XiaoxiaoNeural", "rate": "+0%", "volume": "+0%"},
            "ratio": "9:16",
            "resolution": "1080x1920",
            "fps": 30,
            "bgm": {"enabled": False, "filename": None, "volume": 0.15},
            "options": {
                "subtitle_enabled": True,
                "title_enabled": True,
                "image_motion": "slow_zoom",
                "transition": "fade",
            },
        },
    )
    assert response.status_code in {400, 422}


def test_upload_requires_token_when_configured(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("API_TOKEN", "test-token")
    response = client.post(
        "/api/upload",
        files={"file": ("test.jpg", b"fake-image", "image/jpeg")},
    )
    assert response.status_code == 401


def test_upload_rejects_fake_image_content(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir()
    monkeypatch.setattr(storage_module, "UPLOAD_DIR", upload_dir)

    response = client.post(
        "/api/upload",
        files={"file": ("test.jpg", b"fake-image", "image/jpeg")},
    )

    assert response.status_code == 400
    assert "image content" in response.json()["detail"]


def test_smart_jobs_requires_token_when_configured(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("API_TOKEN", "test-token")
    response = client.post(
        "/api/smart-jobs",
        json={
            "template": "product_basic",
            "prompt": "帮我做一个夏季拖鞋种草视频，突出柔软防滑",
            "assets": ["missing-file-id"],
            "voice": {"speaker": "zh-CN-XiaoxiaoNeural", "rate": "+0%", "volume": "+0%"},
            "ratio": "9:16",
            "resolution": "1080x1920",
            "fps": 30,
            "auto_bgm": True,
        },
    )
    assert response.status_code == 401


def test_smart_jobs_rejects_short_prompt(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("API_TOKEN", "test-token")
    response = client.post(
        "/api/smart-jobs",
        headers={"Authorization": "Bearer test-token"},
        json={
            "template": "product_basic",
            "prompt": "短",
            "assets": ["missing-file-id"],
            "voice": {"speaker": "zh-CN-XiaoxiaoNeural", "rate": "+0%", "volume": "+0%"},
            "ratio": "9:16",
            "resolution": "1080x1920",
            "fps": 30,
            "auto_bgm": True,
        },
    )
    assert response.status_code == 422


def test_worker_owned_update_rejects_wrong_worker(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setenv("STATE_DB_PATH", str(tmp_path / "jobs.sqlite3"))
    job = jobs_module.create_job({"template": "product_basic"})
    claimed = jobs_module.claim_next_queued_job("worker-a")

    assert claimed is not None
    assert claimed.job_id == job.job_id
    assert jobs_module.update_job(job.job_id, phase="bad", expected_worker_id="worker-b") is None

    updated = jobs_module.update_job(job.job_id, phase="good", expected_worker_id="worker-a")

    assert updated is not None
    assert updated.phase == "good"


def test_smart_script_fallback_generates_complete_result(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("LLM_PROVIDER", "none")
    result = generate_smart_script("帮我做一个夏季拖鞋种草视频，突出柔软防滑，适合宿舍浴室", "product_basic", 2)
    assert result.title
    assert len(result.script) >= 3
    assert result.bgm_mood == "warm_lifestyle"
    assert len(result.product_keywords) >= 3
    assert 2 <= len(result.keyword_overlays) <= 4
    assert all(0 <= overlay.segment_index <= 1 for overlay in result.keyword_overlays)



def test_deepseek_overlay_normalization_falls_back_invalid_effect() -> None:
    result = _normalize_result(
        {
            "title": "测试标题",
            "script": ["这是一句测试文案。", "这是第二句测试文案。", "这是第三句测试文案。"],
            "bgm_mood": "warm_lifestyle",
            "product_keywords": ["防滑纹理", "柔软踩感", "宿舍浴室"],
            "keyword_overlays": [
                {
                    "text": "防滑纹理",
                    "segment_index": 99,
                    "start_ratio": 1.3,
                    "end_ratio": -0.2,
                    "keyword_type": "function",
                    "style": "not_style",
                    "effect": "not_effect",
                    "position": "not_position",
                    "intensity": "not_intensity",
                    "reason": "非法字段应被后端收敛",
                }
            ],
        },
        asset_count=2,
    )
    overlay = result.keyword_overlays[0]
    assert overlay.segment_index == 1
    assert overlay.keyword_type == "function"
    assert overlay.effect == "strong_badge"
    assert overlay.position == "center"
    assert overlay.intensity == "medium"


def test_keyword_overlay_validation_sanitizes_and_clamps() -> None:
    overlay = KeywordOverlay.model_validate(
        {
            "text": "防滑纹理https://example.com🚀超长文字",
            "segment_index": 99,
            "start_ratio": 0.0,
            "end_ratio": 1.0,
            "keyword_type": "function",
            "style": "pop",
            "effect": "promo_flash",
            "position": "corner_left",
            "intensity": "high",
            "reason": "测试原因",
        }
    )
    validated = validate_keyword_overlays([overlay], segment_count=2, segment_durations=[5.0, 5.0], total_duration=10.0)
    assert len(validated) == 1
    item = validated[0]
    assert item.segment_index == 1
    assert "http" not in item.text
    assert "🚀" not in item.text
    assert len(item.text) <= 8
    assert item.keyword_type == "function"
    assert item.effect == "strong_badge"
    assert item.style == "badge"
    assert item.end - item.start <= 3.0
    assert item.end - item.start >= 1.2


def test_keyword_overlay_validation_keeps_compatible_llm_art_style() -> None:
    overlay = KeywordOverlay.model_validate(
        {
            "text": "防滑",
            "segment_index": 0,
            "start_ratio": 0.2,
            "end_ratio": 0.6,
            "keyword_type": "function",
            "style": "badge",
            "effect": "spec_corner",
            "position": "corner_right",
            "intensity": "medium",
            "reason": "LLM 认为该功能卖点适合参数角标风格",
        }
    )
    validated = validate_keyword_overlays([overlay], segment_count=1, segment_durations=[6.0], total_duration=6.0)
    assert len(validated) == 1
    assert validated[0].effect == "spec_corner"
    assert validated[0].position == "corner_right"


def test_keyword_overlay_filter_uses_layered_art_text_without_dark_box(tmp_path) -> None:
    overlay = KeywordOverlay(text="柔软", keyword_type="experience", effect="soft_float", position="center_upper")
    validated = validate_keyword_overlays([overlay], segment_count=1, segment_durations=[6.0], total_duration=6.0)
    from app.keyword_overlay import build_keyword_overlay_filter_chain

    vf = build_keyword_overlay_filter_chain(validated, tmp_path, 1080, 1920, None)
    assert vf.count("drawtext=") == 3
    assert "box=0" in vf
    assert "boxcolor=black" not in vf
    assert "0xFF63B8" in vf



def _write_bgm_manifest(tmp_path: Path, body: str) -> None:
    (tmp_path / "manifest.yaml").write_text(body.strip() + "\n", encoding="utf-8")


def test_deepseek_result_normalizes_bgm_profile() -> None:
    result = _normalize_result(
        {
            "title": "测试标题",
            "script": ["这是一句测试文案。", "这是第二句测试文案。", "这是第三句测试文案。"],
            "bgm_mood": "light_commercial",
            "bgm_profile": {
                "scene": "家居种草",
                "emotion": "温和轻快",
                "pace": "medium",
                "bgm_mood": "warm_lifestyle",
                "preferred_tags": ["家居", "生活", "清爽", "种草"],
                "avoid_tags": ["dark", "sad", "epic", "vocal"],
                "volume_hint": 0.08,
            },
            "product_keywords": ["柔软踩感", "防滑纹理", "宿舍浴室"],
        },
        asset_count=1,
    )
    assert result.bgm_mood == "warm_lifestyle"
    assert result.bgm_profile is not None
    assert result.bgm_profile.scene == "家居种草"
    assert result.bgm_profile.preferred_tags[:2] == ["家居", "生活"]
    assert result.bgm_profile.volume_hint == 0.08


def test_bgm_candidates_filter_manifest_fields(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    for filename in ["ok.mp3", "vocal.mp3", "attr.mp3", "bad_license.mp3"]:
        (tmp_path / filename).write_bytes(b"fake")
    _write_bgm_manifest(
        tmp_path,
        """
tracks:
- filename: ok.mp3
  mood: warm_lifestyle
  tags: ["家居", "生活", "种草"]
  duration: 92.3
  vocal: false
  volume: 0.08
  commercial_use: true
  derivative_allowed: true
- filename: vocal.mp3
  mood: warm_lifestyle
  tags: ["家居", "人声"]
  duration: 80
  vocal: true
  commercial_use: true
  derivative_allowed: true
- filename: attr.mp3
  mood: warm_lifestyle
  tags: ["家居"]
  duration: 80
  vocal: false
  attribution_required: true
  commercial_use: true
  derivative_allowed: true
- filename: bad_license.mp3
  mood: warm_lifestyle
  tags: ["家居"]
  duration: 80
  vocal: false
  license_checked: false
  commercial_use: true
  derivative_allowed: true
- filename: missing.mp3
  mood: warm_lifestyle
  tags: ["家居"]
  duration: 80
  vocal: false
  commercial_use: true
  derivative_allowed: true
""",
    )
    monkeypatch.setattr(bgm_selector, "BGM_DIR", tmp_path)
    monkeypatch.setattr(bgm_selector, "MANIFEST_PATH", tmp_path / "manifest.yaml")
    monkeypatch.setattr(bgm_selector, "USAGE_HISTORY_PATH", tmp_path / "usage_history.json")
    monkeypatch.setattr(bgm_selector, "BGM_ALLOW_ATTRIBUTION_REQUIRED", False)

    candidates = bgm_selector.build_bgm_candidates(
        "帮我做家居生活种草视频",
        BgmProfile(bgm_mood="warm_lifestyle", preferred_tags=["家居", "生活"], avoid_tags=["vocal"]),
        video_duration=20,
    )
    assert [candidate.filename for candidate in candidates] == ["ok.mp3"]
    assert candidates[0].id == "bgm_001"
    assert "mood_match" in candidates[0].score_reasons


def test_bgm_manifest_empty_returns_disabled(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    _write_bgm_manifest(tmp_path, "tracks: []")
    monkeypatch.setattr(bgm_selector, "BGM_DIR", tmp_path)
    monkeypatch.setattr(bgm_selector, "MANIFEST_PATH", tmp_path / "manifest.yaml")
    monkeypatch.setattr(bgm_selector, "USAGE_HISTORY_PATH", tmp_path / "usage_history.json")
    config = bgm_selector.select_bgm("测试", "warm_lifestyle", auto_bgm=True)
    assert config.enabled is False


def _candidate(candidate_id: str, filename: str, score: float) -> BgmCandidate:
    return BgmCandidate(
        id=candidate_id,
        filename=filename,
        mood="warm_lifestyle",
        tags=["家居", "生活"],
        duration=60.0,
        vocal=False,
        volume=0.08,
        score=score,
    )


def _smart_script_stub() -> SimpleNamespace:
    return SimpleNamespace(
        title="夏季防滑拖鞋",
        script=["这款拖鞋脚感柔软。", "防滑纹理适合浴室。", "宿舍日常穿也方便。"],
        bgm_mood="warm_lifestyle",
        bgm_profile=BgmProfile(bgm_mood="warm_lifestyle", preferred_tags=["家居", "生活"], avoid_tags=["vocal"]),
    )


def test_bgm_less_than_three_candidates_skips_deepseek(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(main_module, "build_bgm_candidates", lambda **_: [_candidate("bgm_001", "a.mp3", 100), _candidate("bgm_002", "b.mp3", 90)])
    monkeypatch.setattr(bgm_selector, "_safe_track_path", lambda filename: Path("/tmp") / filename)
    monkeypatch.setattr(bgm_selector, "record_bgm_usage", lambda filename: None)
    monkeypatch.setattr(bgm_selector, "run_ffprobe_duration", lambda path: 60.0)

    def fail_if_called(*args, **kwargs):
        raise AssertionError("DeepSeek should not be called for fewer than 3 candidates")

    monkeypatch.setattr(main_module, "select_bgm_from_candidates", fail_if_called)
    config = asyncio.run(main_module._select_smart_bgm("家居种草", _smart_script_stub(), auto_bgm=True))
    assert config.enabled is True
    assert config.filename == "a.mp3"


def test_bgm_llm_invalid_candidate_id_falls_back(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        main_module,
        "build_bgm_candidates",
        lambda **_: [
            _candidate("bgm_001", "top.mp3", 120),
            _candidate("bgm_002", "middle.mp3", 100),
            _candidate("bgm_003", "low.mp3", 80),
        ],
    )
    monkeypatch.setattr(bgm_selector, "_safe_track_path", lambda filename: Path("/tmp") / filename)
    monkeypatch.setattr(bgm_selector, "record_bgm_usage", lambda filename: None)
    monkeypatch.setattr(bgm_selector, "run_ffprobe_duration", lambda path: 60.0)
    monkeypatch.setattr(
        main_module,
        "select_bgm_from_candidates",
        lambda *args, **kwargs: BgmDecision(selected_bgm_id="bgm_999", reason="非法 id 测试", confidence=0.8),
    )
    config = asyncio.run(main_module._select_smart_bgm("家居种草", _smart_script_stub(), auto_bgm=True))
    assert config.enabled is True
    assert config.filename == "top.mp3"


def test_bgm_auto_false_disables_without_candidates(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(main_module, "build_bgm_candidates", lambda **_: (_ for _ in ()).throw(AssertionError("should not build candidates")))
    config = asyncio.run(main_module._select_smart_bgm("家居种草", _smart_script_stub(), auto_bgm=False))
    assert config.enabled is False



def test_subtitle_wrap_uses_safe_line_width() -> None:
    max_chars = subtitle_max_chars_per_line(width=1080, font_size=83)
    assert max_chars <= 12
    wrapped = wrap_subtitle_text("这款拖鞋柔软防滑适合宿舍和浴室日常穿着夏天也很清爽", max_chars)
    assert all(len(line) <= max_chars for line in wrapped.splitlines())
    assert len(wrapped.splitlines()) >= 3


def test_bgm_final_volume_gain_is_applied() -> None:
    candidate = BgmCandidate(
        id="bgm_001",
        filename="test.mp3",
        mood="warm_lifestyle",
        tags=["生活"],
        duration=60.0,
        vocal=False,
        volume=0.08,
        score=100.0,
    )
    volume = bgm_selector._final_volume(candidate, BgmProfile(bgm_mood="warm_lifestyle"))
    assert volume == 0.104
