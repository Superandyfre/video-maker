package com.example.videomaker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.videomaker.ui.components.AppScreenScaffold
import com.example.videomaker.ui.components.AppTextField
import com.example.videomaker.ui.components.BackTopBar
import com.example.videomaker.ui.components.ErrorCard
import com.example.videomaker.ui.components.InfoCard
import com.example.videomaker.ui.components.SoftPrimaryButton
import com.example.videomaker.ui.components.SoftSecondaryButton
import com.example.videomaker.ui.components.SoftSurfaceCard
import com.example.videomaker.ui.components.StatusPill
import com.example.videomaker.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onAbout: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    AppScreenScaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BackTopBar(
                onBack = onBack,
                title = "设置",
                subtitle = "配置后端服务地址、访问令牌和应用外观。"
            )
            SoftSurfaceCard {
                StatusPill(text = "连接配置")
                AppTextField(
                    value = state.baseUrl,
                    onValueChange = viewModel::updateBaseUrl,
                    label = "后端 Base URL",
                    placeholder = "https://api.example.com",
                    singleLine = true,
                    modifier = Modifier.padding(top = 14.dp)
                )
                AppTextField(
                    value = state.apiToken,
                    onValueChange = viewModel::updateApiToken,
                    label = "API Token",
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
            SoftSurfaceCard {
                StatusPill(text = "外观")
                Text(
                    text = "主题模式",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    text = "切换会立即生效并保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ThemeOptionButton(
                        label = "跟随系统",
                        selected = state.themeMode == "system",
                        onClick = { viewModel.updateThemeMode("system") },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeOptionButton(
                        label = "明亮",
                        selected = state.themeMode == "light",
                        onClick = { viewModel.updateThemeMode("light") },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeOptionButton(
                        label = "暗黑",
                        selected = state.themeMode == "dark",
                        onClick = { viewModel.updateThemeMode("dark") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            SoftSurfaceCard {
                StatusPill(text = "应用")
                Text(
                    text = "关于与更新",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    text = "查看当前版本，并从当前后端检查最新 Android 安装包。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                SoftSecondaryButton(
                    text = "打开",
                    onClick = onAbout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SoftPrimaryButton(
                    text = "保存",
                    onClick = viewModel::save,
                    modifier = Modifier.weight(1f)
                )
                SoftSecondaryButton(
                    text = if (state.isTesting) "测试中..." else "测试连接",
                    onClick = viewModel::testConnection,
                    enabled = !state.isTesting && state.baseUrl.isNotBlank(),
                    modifier = Modifier.weight(1f)
                )
            }
            state.message?.let { InfoCard(it) }
            state.error?.let { ErrorCard(it) }
            state.health?.let { health ->
                SoftSurfaceCard(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                ) {
                    StatusPill(
                        text = health.status,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.84f),
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    androidx.compose.material3.Text(
                        text = "版本 ${health.version}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    androidx.compose.material3.Text(
                        text = "FFmpeg：${health.ffmpegAvailable}\nFFprobe：${health.ffprobeAvailable}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeOptionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        SoftPrimaryButton(text = label, onClick = onClick, modifier = modifier)
    } else {
        SoftSecondaryButton(text = label, onClick = onClick, modifier = modifier)
    }
}
