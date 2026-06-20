# Video Maker Android MVP

这是连接 FastAPI 视频生成后端的 Android 客户端 MVP。第一版只做客户端闭环：配置后端地址和 Token、选择素材、输入描述 prompt、创建智能视频任务、轮询任务状态、播放结果视频、下载到系统 Movies 目录和系统分享。

## 技术栈

- Kotlin
- Jetpack Compose + Material 3
- Retrofit + OkHttp
- Kotlin Coroutines
- Android Photo Picker
- Media3 ExoPlayer
- DataStore Preferences
- minSdk 26，支持 Android 8.0+

## 项目结构

```text
app/src/main/java/com/example/videomaker/
├── data/
│   ├── ApiService.kt
│   ├── ApiClient.kt
│   ├── Models.kt
│   └── SettingsRepository.kt
├── ui/
│   ├── MainActivity.kt
│   ├── screens/
│   │   ├── HomeScreen.kt
│   │   ├── SettingsScreen.kt
│   │   ├── CreateVideoScreen.kt
│   │   ├── GenerateScreen.kt
│   │   └── ResultScreen.kt
│   └── components/
│       └── AppComponents.kt
├── viewmodel/
│   ├── SettingsViewModel.kt
│   ├── CreateVideoViewModel.kt
│   └── GenerateViewModel.kt
└── util/
    ├── FileUtils.kt
    └── DownloadUtils.kt
```

## Gradle 依赖

主要依赖在 `app/build.gradle.kts`：

- `androidx.compose.material3:material3`
- `androidx.navigation:navigation-compose`
- `androidx.datastore:datastore-preferences`
- `com.squareup.retrofit2:retrofit`
- `com.squareup.retrofit2:converter-gson`
- `com.squareup.okhttp3:logging-interceptor`
- `androidx.media3:media3-exoplayer`
- `androidx.media3:media3-ui`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android`

## 后端配置

进入 App 后打开“设置”：

1. 真机 Base URL 填 `https://video-maker.andyscodexagent.cyou`。
2. API Token 填后端 `API_TOKEN`。
3. 点击“保存”。
4. 点击“测试连接”，App 会调用 `GET /api/health` 并展示版本和 FFmpeg 状态。

如果用 Android 模拟器连接本机后端，Base URL 通常是：

```text
http://10.0.2.2:8000
```

真机连接局域网电脑时，用电脑局域网 IP，例如：

```text
http://192.168.1.20:8000
```

生产环境建议使用 HTTPS 域名。本项目默认使用子域名，不直接使用根域名；当前推荐：

```text
https://video-maker.andyscodexagent.cyou
```

## 傻瓜模式

“新建营销视频”默认使用后端 `/api/smart-jobs`：

- App 逐个上传图片/视频素材到 `/api/upload`。
- App 把素材 `file_id`、模板、声音、分辨率、prompt 和自动 BGM 开关提交到 `/api/smart-jobs`。
- 后端自动生成标题、口播字幕、BGM mood 和花字卖点。
- App 继续轮询 `/api/jobs/{job_id}`，完成后播放 `/outputs/{job_id}.mp4`。

Prompt 示例：

```text
帮我做一个适合小红书的夏季拖鞋种草视频，突出柔软、防滑、适合宿舍和浴室
```

高级手动模式相关模型仍保留在代码中，但第一版默认入口不展示标题、逐句文案和手动 BGM 文件名。

## 网络安全配置

- `main` / release manifest 默认 `usesCleartextTraffic=false`，生产环境应使用 HTTPS。
- `debug` manifest 使用 `app/src/debug/res/xml/debug_network_security_config.xml`，允许 `10.0.2.2`、`localhost` 和局域网 HTTP，便于模拟器和真机调试。
- 发布 APK 前不要把生产 Base URL 配成 HTTP。

## 手动测试流程

1. 启动后端，确保 `/api/health` 正常。
2. 打开 App 设置页，填写 Base URL 和 API Token。
3. 点击“测试连接”，确认显示连接成功。
4. 返回首页，点击“新建营销视频”。
5. 通过 Photo Picker 选择一张图片或一个短视频。
6. 输入 prompt。
7. 选择模板、TTS 声音、分辨率，按需开启自动 BGM。
8. 点击“生成视频”。
9. 生成页会逐个上传素材、创建智能任务并每 2 秒轮询状态。
10. 任务完成后自动进入结果页。
11. 使用 ExoPlayer 播放视频。
12. 点击“下载到本地”，视频保存到系统 `Movies/VideoMaker`。
13. 点击“分享”，调用 Android 系统分享面板。

## 错误处理

App 会把常见错误转换为用户可读提示：

- 401：API Token 无效或缺失。
- 413：上传文件过大。
- 400：请求参数错误。
- 500：后端生成失败。
- 网络超时：检查后端地址、网络和 HTTPS 证书。
- 任务 failed：展示后端返回的任务错误。

## 已知限制

- 不做登录注册、会员系统、本地剪辑器或复杂时间线。
- 生成流程在当前 Activity ViewModel 内维护，进程被系统杀死后不会恢复正在轮询的任务。
- BGM 由后端根据 `assets/bgm/manifest.yaml` 自动选择；没有可用 BGM 时后端会降级为无 BGM。
- 标题、字幕和花字由后端生成，App 第一版不提供本地编辑。
- 下载到 Android 8/9 的公共 Movies 目录需要写入权限；Android 10+ 使用 MediaStore。
- `video_url` 由客户端用 `baseUrl + video_url` 拼接；后端返回完整 URL 后可简化这一逻辑。
