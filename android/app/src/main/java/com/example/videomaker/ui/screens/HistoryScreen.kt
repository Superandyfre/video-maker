package com.example.videomaker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.videomaker.ui.components.AppScreenScaffold
import com.example.videomaker.ui.components.BackTopBar
import com.example.videomaker.ui.components.ErrorCard
import com.example.videomaker.ui.components.IconActionButton
import com.example.videomaker.ui.components.InfoCard
import com.example.videomaker.ui.components.SoftSurfaceCard
import com.example.videomaker.ui.components.StatusPill
import com.example.videomaker.viewmodel.HistoryRecordUi
import com.example.videomaker.viewmodel.HistoryViewModel

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadHistory()
    }

    AppScreenScaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BackTopBar(
                onBack = onBack,
                title = "历史记录",
                subtitle = "继续查看已生成的视频。",
                actions = {
                    IconActionButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = "刷新历史",
                        onClick = { viewModel.loadHistory(force = true) },
                        enabled = !state.isLoading
                    )
                }
            )
            state.error?.let { ErrorCard(it) }
            if (state.records.isEmpty() && !state.isLoading && state.error == null) {
                InfoCard("还没有历史生成记录。完成一个视频任务后，这里会显示 prompt 和视频。")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.records, key = { it.jobId }) { item ->
                        HistoryRecordCard(
                            item = item,
                            onOpen = { onOpenRecord(item.jobId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRecordCard(
    item: HistoryRecordUi,
    onOpen: () -> Unit
) {
    SoftSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.prompt,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = formatTimestamp(item.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            StatusPill(
                text = "打开",
                modifier = Modifier.padding(start = 12.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

private fun formatTimestamp(value: String): String {
    return value.substringBefore(".").replace("T", " ")
}
