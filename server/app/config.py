import os
from pathlib import Path


def _get_str(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def _get_int(name: str, default: int) -> int:
    value = _get_str(name)
    if not value:
        return default
    try:
        return int(value)
    except ValueError:
        return default


def _get_bool(name: str, default: bool = False) -> bool:
    value = _get_str(name)
    if not value:
        return default
    return value.lower() in {"1", "true", "yes", "on"}


def _get_csv(name: str, default: list[str]) -> list[str]:
    value = _get_str(name)
    if not value:
        return default
    items = [item.strip() for item in value.split(",")]
    return [item for item in items if item]


def _get_path(name: str, default: Path) -> Path:
    value = _get_str(name)
    return Path(value).expanduser() if value else default


VERSION = "0.1.0"

BASE_DIR = Path(__file__).resolve().parent.parent
UPLOAD_DIR = _get_path("UPLOAD_DIR", BASE_DIR / "uploads")
OUTPUT_DIR = _get_path("OUTPUT_DIR", BASE_DIR / "outputs")
TEMP_DIR = _get_path("TEMP_DIR", BASE_DIR / "temp")
DOWNLOAD_DIR = _get_path("DOWNLOAD_DIR", BASE_DIR / "downloads")
ANDROID_RELEASE_DIR = DOWNLOAD_DIR / "android"
_ANDROID_UPDATE_MANIFEST = _get_str("ANDROID_UPDATE_MANIFEST_PATH")
ANDROID_UPDATE_MANIFEST_PATH = Path(_ANDROID_UPDATE_MANIFEST) if _ANDROID_UPDATE_MANIFEST else ANDROID_RELEASE_DIR / "latest.json"
STATE_DIR = _get_path("STATE_DIR", BASE_DIR / "state")
_STATE_DB_PATH = _get_str("STATE_DB_PATH")
STATE_DB_PATH = Path(_STATE_DB_PATH) if _STATE_DB_PATH else STATE_DIR / "video_maker.sqlite3"
TEMPLATE_DIR = BASE_DIR / "app" / "templates"
ASSET_DIR = BASE_DIR / "assets"
FONT_DIR = ASSET_DIR / "fonts"
_BGM_DIR = _get_str("BGM_DIR")
BGM_DIR = Path(_BGM_DIR).expanduser() if _BGM_DIR else ASSET_DIR / "bgm"
_BGM_USAGE_HISTORY_PATH = _get_str("BGM_USAGE_HISTORY_PATH")
BGM_USAGE_HISTORY_PATH = Path(_BGM_USAGE_HISTORY_PATH).expanduser() if _BGM_USAGE_HISTORY_PATH else STATE_DIR / "bgm_usage_history.json"

FFMPEG_BIN = _get_str("FFMPEG_BIN")
FFPROBE_BIN = _get_str("FFPROBE_BIN")
HTTP_PROXY = _get_str("HTTP_PROXY") or _get_str("http_proxy")
HTTPS_PROXY = _get_str("HTTPS_PROXY") or _get_str("https_proxy")
API_TOKEN = _get_str("API_TOKEN")
PUBLIC_BASE_URL = _get_str("PUBLIC_BASE_URL")
CORS_ALLOW_ORIGINS = _get_csv("CORS_ALLOW_ORIGINS", ["*"])
DEEPSEEK_API_KEY = _get_str("DEEPSEEK_API_KEY")
DEEPSEEK_BASE_URL = _get_str("DEEPSEEK_BASE_URL", "https://api.deepseek.com").rstrip("/")
DEEPSEEK_MODEL = _get_str("DEEPSEEK_MODEL", "deepseek-chat")
LLM_PROVIDER = _get_str("LLM_PROVIDER", "deepseek").lower()
LLM_TIMEOUT_SECONDS = _get_int("LLM_TIMEOUT_SECONDS", 30)
BGM_ALLOW_ATTRIBUTION_REQUIRED = _get_bool("BGM_ALLOW_ATTRIBUTION_REQUIRED", False)

MAX_UPLOAD_SIZE_MB = _get_int("MAX_UPLOAD_SIZE_MB", 200)
MAX_UPLOAD_SIZE_BYTES = MAX_UPLOAD_SIZE_MB * 1024 * 1024
MAX_VIDEO_UPLOAD_DURATION_SECONDS = _get_int("MAX_VIDEO_UPLOAD_DURATION_SECONDS", 300)
MAX_JOB_ASSETS = _get_int("MAX_JOB_ASSETS", 12)
MAX_SCRIPT_ITEMS = _get_int("MAX_SCRIPT_ITEMS", 12)
MAX_SCRIPT_ITEM_CHARS = _get_int("MAX_SCRIPT_ITEM_CHARS", 180)
OUTPUT_RETENTION_HOURS = _get_int("OUTPUT_RETENTION_HOURS", 168)
UPLOAD_RETENTION_HOURS = _get_int("UPLOAD_RETENTION_HOURS", 168)
TEMP_RETENTION_HOURS = _get_int("TEMP_RETENTION_HOURS", 24)

ALLOWED_IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}
ALLOWED_VIDEO_EXTENSIONS = {".mp4", ".mov", ".m4v"}
ALLOWED_UPLOAD_EXTENSIONS = ALLOWED_IMAGE_EXTENSIONS | ALLOWED_VIDEO_EXTENSIONS

DEFAULT_RESOLUTION = "1080x1920"
SUPPORTED_RESOLUTIONS = {
    "720x1280": (720, 1280),
    "1080x1920": (1080, 1920),
}

DEFAULT_FPS = 30
JOB_TIMEOUT_SECONDS = 900
CLEANUP_TEMP_AFTER_SUCCESS = True
WORKER_LEASE_SECONDS = _get_int("WORKER_LEASE_SECONDS", 300)
WORKER_HEARTBEAT_INTERVAL_SECONDS = _get_int("WORKER_HEARTBEAT_INTERVAL_SECONDS", 10)
JOB_POLL_INTERVAL_SECONDS = _get_int("JOB_POLL_INTERVAL_SECONDS", 2)

DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"


def get_api_token() -> str:
    return _get_str("API_TOKEN")


def get_http_proxy() -> str:
    return _get_str("HTTP_PROXY") or _get_str("http_proxy")


def get_https_proxy() -> str:
    return _get_str("HTTPS_PROXY") or _get_str("https_proxy")


def get_tts_proxy_url() -> str | None:
    return get_https_proxy() or get_http_proxy() or None


def get_state_db_path() -> str:
    return str(Path(_get_str("STATE_DB_PATH") or STATE_DB_PATH).expanduser())


def get_deepseek_api_key() -> str:
    return _get_str("DEEPSEEK_API_KEY")


def get_llm_provider() -> str:
    return _get_str("LLM_PROVIDER", "deepseek").lower()
