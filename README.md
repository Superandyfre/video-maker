# Video Maker

竖版营销视频生成器：Android 客户端 + FastAPI 后端 + 外部 worker。

## 目录结构

```
video-maker/
├── android/   Kotlin / Jetpack Compose 客户端
└── server/    FastAPI 后端 + 独立 worker
```

## 后端

详见 `server/README.md`。关键依赖：FFmpeg/FFprobe、Python 3.10+、可选 DeepSeek API（智能脚本）。

启动两个 systemd 服务：
- `video-maker.service` —— FastAPI（uvicorn）
- `video-maker-worker.service` —— 渲染 worker（`python -m app.worker`）

部署模板见 `server/deploy/`，复制 `env.example` 为 `env.production` 并填入 token 后使用。

### 资源准备

`server/assets/bgm/` 是渲染时自动 BGM 用的音乐库，体积较大，**未纳入仓库**。运行前需要自行准备，或参考 `server/app/bgm_selector.py` 里的逻辑跳过。

`server/assets/fonts/` 同理，需要一个 CJK 字体（NotoSansCJK / WQY 等）。

## Android

详见 `android/README.md`。开发环境需要 JDK 17 + Android SDK（compileSdk 35、minSdk 26）。

### 构建

```bash
cd android
./gradlew assembleDebug
# 产物在 app/build/outputs/apk/debug/app-debug.apk
```

## 更新检测

后端在 `downloads/android/latest.json` 提供 manifest，App 内"关于"页会检查。新版本流程：
1. 改 `android/app/build.gradle.kts` 的 `versionCode` / `versionName`
2. `./gradlew assembleDebug`
3. 把 APK 复制到 `server/downloads/android/video-maker-android-<version>.apk`
4. 更新 `latest.json`（versionCode / versionName / apkUrl / sha256 / releaseNotes）
