package com.example.videomaker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.videomaker.ui.components.AppScreenScaffold
import com.example.videomaker.ui.components.AppTextField
import com.example.videomaker.ui.components.BackTopBar
import com.example.videomaker.ui.components.DropdownSelector
import com.example.videomaker.ui.components.ErrorCard
import com.example.videomaker.ui.components.MediaThumbCard
import com.example.videomaker.ui.components.SoftPrimaryButton
import com.example.videomaker.ui.components.SoftSecondaryButton
import com.example.videomaker.ui.components.SoftSurfaceCard
import com.example.videomaker.ui.components.StatusPill
import com.example.videomaker.util.SelectedMedia
import com.example.videomaker.viewmodel.CreateVideoViewModel
import com.example.videomaker.viewmodel.GenerationInput
import kotlin.math.ln
import kotlin.math.pow

@Composable
fun CreateVideoScreen(
    viewModel: CreateVideoViewModel,
    onBack: () -> Unit,
    onGenerate: (GenerationInput) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 30),
        onResult = viewModel::addMedia
    )

    LaunchedEffect(Unit) {
        viewModel.loadOptions()
    }

    AppScreenScaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            BackTopBar(
                onBack = onBack,
                title = "新建视频",
                subtitle = "上传素材并填写需求，一键生成竖版营销视频。"
            )
            state.error?.let { ErrorCard(it) }
            SoftSurfaceCard {
                StatusPill(text = "素材")
                Text(
                    text = "支持图片和视频，最多可选择 30 个素材。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 14.dp)
                )
                SoftSecondaryButton(
                    text = "选择图片或视频素材",
                    onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                state.selectedMedia.forEachIndexed { index, media ->
                    MediaThumbCard(
                        title = media.displayName,
                        tag = mediaTag(media),
                        supportingText = mediaSupportingText(media),
                        modifier = Modifier.padding(top = 12.dp),
                        trailingContent = {
                            SoftSecondaryButton(
                                text = "删除",
                                onClick = { viewModel.removeMedia(index) }
                            )
                        }
                    )
                }
            }
            SoftSurfaceCard {
                StatusPill(text = "Prompt")
                AppTextField(
                    value = state.prompt,
                    onValueChange = viewModel::updatePrompt,
                    label = "描述 prompt",
                    placeholder = "例如：做一个夏季拖鞋种草视频，突出柔软、防滑、适合宿舍浴室",
                    minLines = 4,
                    maxLines = 6,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
            SoftSurfaceCard {
                StatusPill(text = "生成参数")
                DropdownSelector(
                    label = "模板",
                    selectedText = state.templates.firstOrNull { it.name == state.selectedTemplate }?.displayName
                        ?: state.selectedTemplate,
                    options = state.templates.map { it.name to "${it.displayName} - ${it.description}" },
                    onSelect = viewModel::updateTemplate,
                    modifier = Modifier.padding(top = 14.dp)
                )
                DropdownSelector(
                    label = "TTS 声音",
                    selectedText = state.voices.firstOrNull { it.id == state.selectedVoice }?.let {
                        "${it.name} (${it.gender})"
                    } ?: state.selectedVoice,
                    options = state.voices.map { it.id to "${it.name} / ${it.locale} / ${it.gender}" },
                    onSelect = viewModel::updateVoice,
                    modifier = Modifier.padding(top = 14.dp)
                )
                DropdownSelector(
                    label = "分辨率",
                    selectedText = state.resolution,
                    options = listOf("1080x1920" to "1080x1920", "720x1280" to "720x1280"),
                    onSelect = viewModel::updateResolution,
                    modifier = Modifier.padding(top = 14.dp)
                )
                SoftSurfaceCard(
                    modifier = Modifier.padding(top = 14.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Checkbox(
                            checked = state.autoBgm,
                            onCheckedChange = viewModel::updateAutoBgm,
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "自动 BGM",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "开启后会自动配上背景音乐。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SoftPrimaryButton(
                    text = "生成视频",
                    onClick = {
                        viewModel.buildGenerationInput()?.let(onGenerate)
                    },
                    enabled = viewModel.buildGenerationInput() != null,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun mediaTag(media: SelectedMedia): String {
    return when {
        media.mimeType.startsWith("video/") -> "视频"
        media.mimeType.startsWith("image/") -> "图片"
        else -> "素材"
    }
}

private fun mediaSupportingText(media: SelectedMedia): String {
    val size = media.sizeBytes?.let { formatFileSize(it) } ?: "大小未知"
    return "${media.mimeType} · $size"
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val units = listOf("KB", "MB", "GB", "TB")
    val digitGroup = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(units.size)
    val value = bytes / 1024.0.pow(digitGroup.toDouble())
    return String.format("%.1f %s", value, units[digitGroup - 1])
}
