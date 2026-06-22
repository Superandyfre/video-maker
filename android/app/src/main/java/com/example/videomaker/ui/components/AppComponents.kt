package com.example.videomaker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun AppScreenScaffold(
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit
) {
    AppGradientBackground(modifier = modifier) {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content
        )
    }
}

@Composable
fun AppGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val base = colors.background
    val surfaceBlend = lerp(base, colors.surface, 0.62f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        surfaceBlend,
                        base,
                        lerp(base, colors.primaryContainer, 0.10f)
                    )
                )
            )
            .drawWithCache {
                val topAurora = Brush.linearGradient(
                    colors = listOf(
                        colors.primary.copy(alpha = 0.16f),
                        colors.secondary.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width * 0.95f, size.height * 0.45f)
                )
                val lowerAurora = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        colors.tertiary.copy(alpha = 0.08f),
                        colors.primary.copy(alpha = 0.06f)
                    ),
                    start = Offset(size.width, size.height * 0.35f),
                    end = Offset(0f, size.height)
                )
                onDrawBehind {
                    drawRect(topAurora)
                    drawRect(lowerAurora)
                }
            }
    ) {
        content()
    }
}

@Composable
fun IconActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    containerColor: Color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
    contentColor: Color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
) {
    Box(
        modifier = modifier
            .size(46.dp)
            .alpha(if (enabled) 1f else 0.48f)
            .clip(RoundedCornerShape(999.dp))
            .background(containerColor)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                shape = RoundedCornerShape(999.dp)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
fun BackTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconActionButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                onClick = onBack
            )
            if (!title.isNullOrBlank()) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onBackground
                    )
                    subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = actions
        )
    }
}

@Composable
fun SoftSurfaceCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f),
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(999.dp)
    val clickModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .border(1.dp, contentColor.copy(alpha = 0.08f), shape)
            .then(clickModifier)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun InfoCard(message: String, modifier: Modifier = Modifier) {
    SoftSurfaceCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun ErrorCard(message: String, modifier: Modifier = Modifier) {
    SoftSurfaceCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.88f)
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun SoftPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 54.dp),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
        )
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SoftSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        shape = RoundedCornerShape(24.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
        )
    )
}

@Composable
fun DropdownSelector(
    label: String,
    selectedText: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Text(
                    text = selectedText.ifBlank { "请选择" },
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(min = 220.dp),
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                options.forEach { (value, title) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MediaThumbCard(
    title: String,
    tag: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    trailingContent: @Composable () -> Unit
) {
    SoftSurfaceCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                StatusPill(
                    text = tag,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.84f),
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            trailingContent()
        }
    }
}
