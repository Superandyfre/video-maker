package com.example.videomaker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.videomaker.ui.components.AppScreenScaffold
import com.example.videomaker.ui.components.DropdownSelector
import com.example.videomaker.ui.components.ErrorCard
import com.example.videomaker.ui.components.IconActionButton
import com.example.videomaker.ui.components.InfoCard
import com.example.videomaker.ui.components.MediaAttachmentStrip
import com.example.videomaker.ui.components.PromptComposer
import com.example.videomaker.ui.components.SoftPrimaryButton
import com.example.videomaker.ui.components.SoftSecondaryButton
import com.example.videomaker.ui.components.SoftSurfaceCard
import com.example.videomaker.ui.components.StatusPill
import com.example.videomaker.ui.components.ToolChip
import com.example.videomaker.viewmodel.CreateVideoUiState
import com.example.videomaker.viewmodel.CreateVideoViewModel
import com.example.videomaker.viewmodel.GenerationInput
import com.example.videomaker.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settingsViewModel: SettingsViewModel,
    createVideoViewModel: CreateVideoViewModel,
    isGenerating: Boolean,
    onGenerate: (GenerationInput) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit
) {
    val settingsState by settingsViewModel.uiState.collectAsState()
    val createState by createVideoViewModel.uiState.collectAsState()
    var showTools by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 30),
        onResult = createVideoViewModel::addMedia
    )

    LaunchedEffect(settingsState.baseUrl, settingsState.apiToken) {
        if (settingsState.baseUrl.isNotBlank()) {
            createVideoViewModel.loadOptions()
        }
    }

    if (showTools) {
        ModalBottomSheet(
            onDismissRequest = { showTools = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            ToolSheetContent(
                createState = createState,
                isTesting = settingsState.isTesting,
                connectionMessage = settingsState.message,
                connectionError = settingsState.error,
                onClose = { showTools = false },
                onPickMedia = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onRemoveMedia = createVideoViewModel::removeMedia,
                onTemplateChange = createVideoViewModel::updateTemplate,
                onVoiceChange = createVideoViewModel::updateVoice,
                onResolutionChange = createVideoViewModel::updateResolution,
                onAutoBgmChange = createVideoViewModel::updateAutoBgm,
                onTestConnection = settingsViewModel::testConnection,
                canTestConnection = settingsState.baseUrl.isNotBlank() && !settingsState.isTesting
            )
        }
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
            HomeTopBar(
                connectionLabel = connectionPillLabel(
                    baseUrl = settingsState.baseUrl,
                    connected = settingsState.health != null,
                    isTesting = settingsState.isTesting
                ),
                connected = settingsState.health != null,
                canTestConnection = settingsState.baseUrl.isNotBlank() && !settingsState.isTesting,
                onTestConnection = settingsViewModel::testConnection,
                onHistory = onHistory,
                onSettings = onSettings
            )
            Spacer(modifier = Modifier.height(26.dp))
            Text(
                text = "今天想做什么视频？",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            SummaryChips(
                state = createState,
                onOpenTools = { showTools = true }
            )
            createState.error?.takeIf { settingsState.baseUrl.isNotBlank() }?.let { ErrorCard(it) }
            if (settingsState.baseUrl.isBlank()) {
                InfoCard("先在设置中填写后端 Base URL 和 API Token，再开始生成。")
            }
            PromptComposer(
                prompt = createState.prompt,
                selectedMedia = createState.selectedMedia,
                canGenerate = createVideoViewModel.buildGenerationInput() != null && settingsState.baseUrl.isNotBlank(),
                isBusy = isGenerating,
                onPromptChange = createVideoViewModel::updatePrompt,
                onAddMedia = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onRemoveMedia = createVideoViewModel::removeMedia,
                onOpenTools = { showTools = true },
                onGenerate = {
                    createVideoViewModel.buildGenerationInput()?.let(onGenerate)
                }
            )
            settingsState.error?.let { ErrorCard(it) }
        }
    }
}

@Composable
private fun HomeTopBar(
    connectionLabel: String,
    connected: Boolean,
    canTestConnection: Boolean,
    onTestConnection: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusPill(
            text = connectionLabel,
            modifier = Modifier.clickable(
                enabled = canTestConnection,
                onClick = onTestConnection
            ),
            containerColor = if (connected) {
                colors.tertiaryContainer.copy(alpha = 0.84f)
            } else {
                colors.primaryContainer.copy(alpha = 0.72f)
            },
            contentColor = if (connected) colors.onTertiaryContainer else colors.onPrimaryContainer
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconActionButton(
                icon = Icons.Rounded.History,
                contentDescription = "历史记录",
                onClick = onHistory
            )
            IconActionButton(
                icon = Icons.Rounded.Settings,
                contentDescription = "设置",
                onClick = onSettings
            )
        }
    }
}

@Composable
private fun SummaryChips(
    state: CreateVideoUiState,
    onOpenTools: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ToolChip(label = "模板", value = templateLabel(state), onClick = onOpenTools)
        ToolChip(label = "声音", value = voiceLabel(state), onClick = onOpenTools)
        ToolChip(label = "画幅", value = state.resolution, onClick = onOpenTools)
        ToolChip(label = "BGM", value = if (state.autoBgm) "自动" else "关闭", onClick = onOpenTools)
    }
}

@Composable
private fun ToolSheetContent(
    createState: CreateVideoUiState,
    isTesting: Boolean,
    connectionMessage: String?,
    connectionError: String?,
    onClose: () -> Unit,
    onPickMedia: () -> Unit,
    onRemoveMedia: (Int) -> Unit,
    onTemplateChange: (String) -> Unit,
    onVoiceChange: (String) -> Unit,
    onResolutionChange: (String) -> Unit,
    onAutoBgmChange: (Boolean) -> Unit,
    onTestConnection: () -> Unit,
    canTestConnection: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "创作工具",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconActionButton(
                icon = Icons.Rounded.Close,
                contentDescription = "关闭工具",
                onClick = onClose
            )
        }
        SoftPrimaryButton(
            text = "选择图片或视频素材",
            onClick = onPickMedia,
            modifier = Modifier.fillMaxWidth()
        )
        if (createState.selectedMedia.isNotEmpty()) {
            MediaAttachmentStrip(
                media = createState.selectedMedia,
                onRemoveMedia = onRemoveMedia
            )
        }
        SoftSurfaceCard(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(text = "参数")
                IconActionButton(
                    icon = Icons.Rounded.Tune,
                    contentDescription = "生成参数",
                    onClick = {},
                    enabled = false
                )
            }
            DropdownSelector(
                label = "模板",
                selectedText = templateLabel(createState),
                options = createState.templates.map { it.name to "${it.displayName} - ${it.description}" }
                    .ifEmpty { listOf(createState.selectedTemplate to createState.selectedTemplate) },
                onSelect = onTemplateChange,
                modifier = Modifier.padding(top = 14.dp)
            )
            DropdownSelector(
                label = "TTS 声音",
                selectedText = voiceLabel(createState),
                options = createState.voices.map { it.id to "${it.name} / ${it.locale} / ${it.gender}" }
                    .ifEmpty { listOf(createState.selectedVoice to createState.selectedVoice) },
                onSelect = onVoiceChange,
                modifier = Modifier.padding(top = 14.dp)
            )
            DropdownSelector(
                label = "分辨率",
                selectedText = createState.resolution,
                options = listOf("1080x1920" to "1080x1920", "720x1280" to "720x1280"),
                onSelect = onResolutionChange,
                modifier = Modifier.padding(top = 14.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Checkbox(
                    checked = createState.autoBgm,
                    onCheckedChange = onAutoBgmChange,
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动 BGM", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "由后端根据 prompt 自动挑选背景音乐。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SoftSecondaryButton(
                text = if (isTesting) "测试中..." else "测试连接",
                onClick = onTestConnection,
                enabled = canTestConnection,
                modifier = Modifier.weight(1f)
            )
            SoftSecondaryButton(
                text = "完成",
                onClick = onClose,
                modifier = Modifier.weight(1f)
            )
        }
        connectionMessage?.let { InfoCard(it) }
        connectionError?.let { ErrorCard(it) }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

private fun templateLabel(state: CreateVideoUiState): String {
    return state.templates.firstOrNull { it.name == state.selectedTemplate }?.displayName ?: state.selectedTemplate
}

private fun voiceLabel(state: CreateVideoUiState): String {
    return state.voices.firstOrNull { it.id == state.selectedVoice }?.let { "${it.name} (${it.gender})" }
        ?: state.selectedVoice
}

private fun connectionPillLabel(baseUrl: String, connected: Boolean, isTesting: Boolean): String {
    return when {
        isTesting -> "测试中..."
        connected -> "已连接 · 复测"
        baseUrl.isBlank() -> "未配置"
        else -> "待测试 · 测试"
    }
}
