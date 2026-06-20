# Video Maker Server

一个用于生成 9:16 竖屏营销视频的 Python FastAPI 后端。用户上传图片或视频素材，提交标题、口播文案、模板和 TTS 音色后，服务会在后台生成 MP4 视频。

当前版本是稳定 MVP：不包含 Android App、数据库、Redis、Celery、账号系统或复杂时间线编辑器，但支持公网域名后方部署、简单 Bearer Token 鉴权、TTS、字幕、BGM 混音和文件清理。

## 功能

- 图片/视频素材上传，使用 UUID 保存文件。
- 使用 edge-tts 生成中文 TTS 配音。
- 图片/视频自动转 1080x1920 或 720x1280 竖屏 H.264 片段。
- 顶部标题、底部中文字幕，FFmpeg 缺 `subtitles/libass` 时自动使用 `drawtext` 字幕 fallback。
- 可选背景音乐混音。
- 智能生成接口：用户只传 prompt 和素材，后端自动生成标题、口播字幕、BGM mood 和花字卖点。
- 输出 H.264、yuv420p、AAC、faststart MP4。
- 内存任务状态查询和公开视频下载。
- 可选 API Token 保护上传、创建任务和任务查询。
- 管理清理接口删除过期 temp/uploads/outputs 文件。

## 环境要求

- Ubuntu 20.04 / 22.04 / 24.04
- Python 3.10 或以上
- FFmpeg 和 ffprobe
- 可访问 edge-tts 所需网络服务

安装系统 FFmpeg 和中文字体：

```bash
sudo apt update
sudo apt install -y ffmpeg fonts-noto-cjk
```

如果系统 PATH 没有 FFmpeg，也可以在环境变量中指定：

```bash
export FFMPEG_BIN=/home/pinggu/conceptgraph_env/bin/ffmpeg
export FFPROBE_BIN=/home/pinggu/conceptgraph_env/bin/ffprobe
```

## 本地开发启动

```bash
cd /home/pinggu/personal/video-maker-server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

export FFMPEG_BIN=/home/pinggu/conceptgraph_env/bin/ffmpeg
export FFPROBE_BIN=/home/pinggu/conceptgraph_env/bin/ffprobe
export HTTPS_PROXY=http://127.0.0.1:7890
export HTTP_PROXY=http://127.0.0.1:7890

./run.sh
```

默认监听 `http://0.0.0.0:8000`，OpenAPI 文档在 `http://127.0.0.1:8000/docs`。

## 环境变量

```text
FFMPEG_BIN=/absolute/path/to/ffmpeg
FFPROBE_BIN=/absolute/path/to/ffprobe
HTTPS_PROXY=http://127.0.0.1:7890
HTTP_PROXY=http://127.0.0.1:7890
API_TOKEN=change-this-token
PUBLIC_BASE_URL=https://video-maker.andyscodexagent.cyou
BGM_DIR=/absolute/path/to/bgm
BGM_USAGE_HISTORY_PATH=/absolute/path/to/bgm_usage_history.json
DEEPSEEK_API_KEY=
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat
LLM_PROVIDER=deepseek
LLM_TIMEOUT_SECONDS=30
MAX_UPLOAD_SIZE_MB=200
OUTPUT_RETENTION_HOURS=168
UPLOAD_RETENTION_HOURS=168
TEMP_RETENTION_HOURS=24
```

- `FFMPEG_BIN` / `FFPROBE_BIN`：设置后优先使用指定路径，否则从 PATH 查找。
- `HTTPS_PROXY` / `HTTP_PROXY`：edge-tts 会读取这些代理变量；很多网络环境下 edge-tts websocket 需要代理才能连通。
- `API_TOKEN`：为空时是本地开发模式，不强制鉴权；非空时受保护接口必须带 `Authorization: Bearer <token>`。
- `PUBLIC_BASE_URL`：预留给公网 URL 拼接；当前 API 仍返回相对路径。
- `DEEPSEEK_API_KEY`：智能生成可选使用 DeepSeek；不要写入源码或 Android 客户端。
- `BGM_DIR`：可选覆盖默认的 `assets/bgm/`，适合把 BGM 挂到单独磁盘或 WebDAV/rclone 挂载目录。
- `BGM_USAGE_HISTORY_PATH`：可选覆盖 BGM 使用历史文件位置；当 `BGM_DIR` 指向只读挂载时，建议保留到本地 `state/` 目录。
- `LLM_PROVIDER`：`deepseek` 时优先调用 DeepSeek，失败或未配置 key 会自动规则 fallback；`none` 时完全使用规则生成。
- retention 变量用于清理过期文件。

## API Token

公开接口：

- `GET /api/health`
- `GET /api/templates`
- `GET /api/voices`
- `GET /outputs/{filename}`

当 `API_TOKEN` 非空时，以下接口必须带 Bearer Token：

- `POST /api/upload`
- `POST /api/jobs`
- `POST /api/smart-jobs`
- `GET /api/jobs/{job_id}`
- `POST /api/admin/cleanup`

示例：

```bash
curl -H "Authorization: Bearer change-this-token" \
  http://127.0.0.1:8000/api/jobs/JOB_ID
```

## API 示例

健康检查：

```bash
curl http://127.0.0.1:8000/api/health
```

上传素材：

```bash
curl -X POST http://127.0.0.1:8000/api/upload \
  -H "Authorization: Bearer change-this-token" \
  -F "file=@/path/to/product.jpg"
```

创建任务：

```bash
curl -X POST http://127.0.0.1:8000/api/jobs \
  -H "Authorization: Bearer change-this-token" \
  -H "Content-Type: application/json" \
  -d '{
    "template": "product_basic",
    "title": "夏季防滑拖鞋推荐",
    "script": ["这款拖鞋主打柔软踩感。", "EVA材质，穿起来比较轻。"],
    "assets": ["替换为上传接口返回的file_id"],
    "voice": {"speaker": "zh-CN-XiaoxiaoNeural", "rate": "+0%", "volume": "+0%"},
    "ratio": "9:16",
    "resolution": "1080x1920",
    "fps": 30,
    "bgm": {"enabled": false, "filename": null, "volume": 0.15},
    "options": {"subtitle_enabled": true, "title_enabled": true, "image_motion": "slow_zoom", "transition": "fade"}
  }'
```

智能创建任务：

```bash
curl -X POST http://127.0.0.1:8000/api/smart-jobs \
  -H "Authorization: Bearer change-this-token" \
  -H "Content-Type: application/json" \
  -d '{
    "template": "product_basic",
    "prompt": "帮我做一个适合小红书的夏季拖鞋种草视频，突出柔软、防滑、适合宿舍和浴室",
    "assets": ["替换为上传接口返回的file_id"],
    "voice": {"speaker": "zh-CN-XiaoxiaoNeural", "rate": "+0%", "volume": "+0%"},
    "ratio": "9:16",
    "resolution": "1080x1920",
    "fps": 30,
    "auto_bgm": true
  }'
```

`/api/smart-jobs` 会自动生成标题、口播脚本、BGM mood 和 `keyword_overlays`，再复用 `/api/jobs` 的渲染流程。旧的 `/api/jobs` 手动模式保持兼容。

查询任务：

```bash
curl -H "Authorization: Bearer change-this-token" \
  http://127.0.0.1:8000/api/jobs/替换为job_id
```

下载视频：

```bash
curl -L -o result.mp4 http://127.0.0.1:8000/outputs/替换为job_id.mp4
```

## BGM 文件

把背景音乐放在 `assets/bgm/`，或通过环境变量 `BGM_DIR` 指向其他目录。支持 `mp3`、`wav`、`m4a`、`aac`、`ogg`、`flac`。

生成一个测试 BGM：

```bash
ffmpeg -f lavfi -i sine=frequency=440:duration=5 assets/bgm/test_bgm.wav
```

请求中启用：

```json
{"bgm": {"enabled": true, "filename": "test_bgm.wav", "volume": 0.08}}
```

智能模式通过 `assets/bgm/manifest.yaml` 自动选择 BGM，只允许选择 manifest 中列出的普通文件名，防止路径穿越。没有可用 BGM 文件时会自动降级为无 BGM，不报错。

示例：

```yaml
tracks:
  - filename: test_bgm.wav
    mood: warm_lifestyle
    tags: ["家居", "生活", "拖鞋", "宿舍", "日用"]
    volume: 0.08
```

## 智能文案与花字

智能模式会调用 `app.smart_script.generate_smart_script()` 生成：

- `title`：顶部标题。
- `script`：TTS 和底部字幕使用的口播文案。
- `bgm_mood`：用于自动匹配 BGM manifest。
- `product_keywords`：卖点关键词。
- `keyword_overlays`：2 到 4 个花字卖点，使用 FFmpeg `drawtext` 叠加在画面中上部或中部。

如果 DeepSeek 不可用、超时、未配置 key 或返回非法 JSON，服务会自动使用规则 fallback。花字渲染失败不会让整个任务失败，会记录 warning 并继续输出视频。

## 字体

`assets/fonts/` 可放中文字体，例如 `SourceHanSansSC-Regular.otf` 或 `NotoSansCJK-Regular.ttc`。服务优先使用该目录，其次尝试 Ubuntu 常见系统字体。若 FFmpeg 没有 `subtitles/libass`，会使用 `drawtext` fallback 烧录字幕。

## 清理策略

启动时只清理过期 `temp/` 任务目录，不自动删除 outputs，避免误删刚生成的视频。

手动清理接口会按 retention 变量清理 `temp/`、`uploads/`、`outputs/`：

```bash
curl -X POST http://127.0.0.1:8000/api/admin/cleanup \
  -H "Authorization: Bearer change-this-token"
```

生产环境建议用 cron 定期调用该接口，或复用 `app.cleanup` 中的清理逻辑写独立运维脚本。

## systemd 部署

编辑 `deploy/env.example`，至少设置：

```text
FFMPEG_BIN=/usr/bin/ffmpeg
FFPROBE_BIN=/usr/bin/ffprobe
API_TOKEN=换成强随机token
PUBLIC_BASE_URL=https://video-maker.andyscodexagent.cyou
```

安装服务：

```bash
sudo cp deploy/video-maker.service.example /etc/systemd/system/video-maker.service
sudo systemctl daemon-reload
sudo systemctl enable --now video-maker.service
sudo systemctl status video-maker.service
```

查看日志：

```bash
journalctl -u video-maker.service -f
```

## Nginx 部署

参考 `deploy/nginx.conf.example`。关键点：

- `/api/` 反向代理到 `127.0.0.1:8000`。
- `/outputs/` 可以反向代理，也可以用 `alias` 直接由 Nginx 托管。
- `client_max_body_size 200M`。
- `proxy_read_timeout 600s`。
- HTTPS 证书路径替换为真实域名证书。

## Caddy 部署

参考 `deploy/caddyfile.example`。Caddy 会自动申请和续期 HTTPS 证书：

```bash
sudo caddy reload --config /etc/caddy/Caddyfile
```

按 `MAX_UPLOAD_SIZE_MB` 调整 `request_body max_size`。

## 运行测试

```bash
cd /home/pinggu/personal/video-maker-server
PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 .venv/bin/python -m pytest -q
```

## 当前限制

- jobs 和 uploads 映射仍在内存中，服务重启后任务状态和旧 file_id 映射会丢失。
- 不做数据库、Redis、Celery、账号系统或 Android App。
- edge-tts 依赖外部网络，生产建议替换为正式云 TTS，并加入供应商限流和降级策略。
- DeepSeek 智能文案是可选能力，未配置时使用规则 fallback；规则生成质量有限。
- 花字第一版只用 FFmpeg `drawtext`，没有复杂动画、轨道编辑或逐字高亮。
- 后台任务仍由 FastAPI 进程执行，高并发或长视频需要迁移到独立 worker/队列。
- `POST /api/admin/cleanup` 会删除过期 outputs，生产前应根据业务保留期谨慎配置。
