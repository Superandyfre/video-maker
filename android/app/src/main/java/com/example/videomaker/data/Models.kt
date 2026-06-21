package com.example.videomaker.data

import com.google.gson.annotations.SerializedName

data class HealthResponse(
    val status: String,
    @SerializedName("ffmpeg_available") val ffmpegAvailable: Boolean,
    @SerializedName("ffmpeg_path") val ffmpegPath: String?,
    @SerializedName("ffprobe_available") val ffprobeAvailable: Boolean,
    @SerializedName("ffprobe_path") val ffprobePath: String?,
    val version: String
)

data class UploadResponse(
    @SerializedName("file_id") val fileId: String,
    val filename: String,
    @SerializedName("media_type") val mediaType: String,
    val url: String,
    @SerializedName("size_bytes") val sizeBytes: Long
)

data class VoiceConfig(
    val speaker: String = "zh-CN-XiaoxiaoNeural",
    val rate: String = "+0%",
    val volume: String = "+0%"
)

data class BgmConfig(
    val enabled: Boolean = false,
    val filename: String? = null,
    val volume: Double = 0.15
)

data class RenderOptions(
    @SerializedName("subtitle_enabled") val subtitleEnabled: Boolean = true,
    @SerializedName("title_enabled") val titleEnabled: Boolean = true,
    @SerializedName("image_motion") val imageMotion: String = "slow_zoom",
    val transition: String = "fade"
)

data class CreateJobRequest(
    val template: String,
    val title: String,
    val script: List<String>,
    val assets: List<String>,
    val voice: VoiceConfig,
    val ratio: String = "9:16",
    val resolution: String = "1080x1920",
    val fps: Int = 30,
    val bgm: BgmConfig = BgmConfig(),
    val options: RenderOptions = RenderOptions()
)

data class SmartJobRequest(
    val template: String,
    val prompt: String,
    val assets: List<String>,
    val voice: VoiceConfig,
    val ratio: String = "9:16",
    val resolution: String = "1080x1920",
    val fps: Int = 30,
    @SerializedName("auto_bgm") val autoBgm: Boolean = true
)

data class CreateJobResponse(
    @SerializedName("job_id") val jobId: String,
    val status: String
)

data class GenerationHistoryItem(
    @SerializedName("job_id") val jobId: String,
    val prompt: String,
    @SerializedName("video_url") val videoUrl: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)

data class GenerationHistoryResponse(
    val items: List<GenerationHistoryItem>
)

data class JobStatusResponse(
    @SerializedName("job_id") val jobId: String,
    val status: String,
    val phase: String? = null,
    val progress: Int,
    val message: String,
    @SerializedName("video_url") val videoUrl: String?,
    val error: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("updated_at") val updatedAt: String?
)

data class TemplateInfo(
    val name: String,
    @SerializedName("display_name") val displayName: String,
    val description: String,
    val ratio: String
)

data class TemplatesResponse(
    val templates: List<TemplateInfo>
)

data class VoiceInfo(
    val id: String,
    val name: String,
    val locale: String,
    val gender: String
)

data class CapabilitiesResponse(
    @SerializedName("max_upload_size_mb") val maxUploadSizeMb: Int = 200,
    @SerializedName("max_upload_size_bytes") val maxUploadSizeBytes: Long = 200L * 1024L * 1024L,
    @SerializedName("max_video_upload_duration_seconds") val maxVideoUploadDurationSeconds: Int = 300,
    @SerializedName("max_job_assets") val maxJobAssets: Int = 12,
    @SerializedName("max_script_items") val maxScriptItems: Int = 12,
    @SerializedName("max_script_item_chars") val maxScriptItemChars: Int = 180,
    @SerializedName("supported_image_mime_types") val supportedImageMimeTypes: List<String> = listOf(
        "image/jpeg",
        "image/png",
        "image/webp"
    ),
    @SerializedName("supported_video_mime_types") val supportedVideoMimeTypes: List<String> = listOf(
        "video/mp4",
        "video/quicktime",
        "video/x-m4v"
    ),
    @SerializedName("supported_resolutions") val supportedResolutions: List<String> = listOf("720x1280", "1080x1920"),
    @SerializedName("default_resolution") val defaultResolution: String = "1080x1920"
)

data class AppUpdateResponse(
    val versionCode: Int,
    val versionName: String?,
    val apkUrl: String?,
    val releaseNotes: String?,
    val sha256: String?
)

data class AppSettings(
    val baseUrl: String = "",
    val apiToken: String = "",
    val themeMode: String = "system"
)
