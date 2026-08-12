package com.paisalens.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AppThemeConfiguration
import com.paisalens.app.data.model.AppThemeMode
import com.paisalens.app.data.model.AppThemePalette
import com.paisalens.app.data.model.AppThemeStyle

/**
 * Full-app appearance picker. Every selection is emitted immediately so the surrounding app can
 * persist it and update the live Material theme without a separate Save action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeStudioSheet(
    configuration: AppThemeConfiguration,
    systemInDarkTheme: Boolean,
    onConfigurationChange: (AppThemeConfiguration) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val paletteRows = remember { AppThemePalette.entries.chunked(2) }
    val fontScale = LocalDensity.current.fontScale
    val effectiveMode = if (configuration.style == AppThemeStyle.AMOLED) {
        AppThemeMode.DARK
    } else {
        configuration.mode
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        scrimColor = Color.Black.copy(alpha = 0.60f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .navigationBarsPadding(),
        ) {
            ThemeStudioHeader(onDismiss = onDismiss)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    ThemePreview(
                        configuration = configuration,
                        systemInDarkTheme = systemInDarkTheme,
                    )
                }

                item {
                    ThemeStudioSection(
                        title = "Theme style",
                        supportingText = "Choose how surfaces and color are presented throughout PaisaLens.",
                    ) {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val stackChoices = maxWidth < 320.dp || fontScale >= 1.3f
                            if (stackChoices) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AppThemeStyle.entries.forEach { style ->
                                        ThemeStyleChoice(
                                            style = style,
                                            selected = configuration.style == style,
                                            onClick = {
                                                onConfigurationChange(
                                                    configuration.copy(
                                                        style = style,
                                                        mode = if (style == AppThemeStyle.AMOLED) {
                                                            AppThemeMode.DARK
                                                        } else {
                                                            configuration.mode
                                                        },
                                                    ),
                                                )
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    AppThemeStyle.entries.forEach { style ->
                                        ThemeStyleChoice(
                                            style = style,
                                            selected = configuration.style == style,
                                            onClick = {
                                                onConfigurationChange(
                                                    configuration.copy(
                                                        style = style,
                                                        mode = if (style == AppThemeStyle.AMOLED) {
                                                            AppThemeMode.DARK
                                                        } else {
                                                            configuration.mode
                                                        },
                                                    ),
                                                )
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    ThemeStudioSection(
                        title = "Appearance",
                        supportingText = if (configuration.style == AppThemeStyle.AMOLED) {
                            "AMOLED uses Dark appearance so eligible screen pixels remain fully off."
                        } else {
                            "Follow your phone or keep one appearance all the time."
                        },
                    ) {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val stackChoices = maxWidth < 320.dp || fontScale >= 1.3f
                            if (stackChoices) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AppThemeMode.entries.forEach { mode ->
                                        AppearanceChoice(
                                            mode = mode,
                                            selected = effectiveMode == mode,
                                            enabled = configuration.style != AppThemeStyle.AMOLED,
                                            locked = configuration.style == AppThemeStyle.AMOLED && mode == AppThemeMode.DARK,
                                            onClick = {
                                                onConfigurationChange(configuration.copy(mode = mode))
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    AppThemeMode.entries.forEach { mode ->
                                        AppearanceChoice(
                                            mode = mode,
                                            selected = effectiveMode == mode,
                                            enabled = configuration.style != AppThemeStyle.AMOLED,
                                            locked = configuration.style == AppThemeStyle.AMOLED && mode == AppThemeMode.DARK,
                                            onClick = {
                                                onConfigurationChange(configuration.copy(mode = mode))
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                        if (configuration.style == AppThemeStyle.AMOLED) {
                            Row(
                                modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Dark is locked while AMOLED black is selected.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item {
                    ThemeStudioSection(
                        title = "Color variation",
                        supportingText = "Pick a palette. Every option includes coordinated primary, accent, and chart colors.",
                    ) {}
                }

                items(
                    items = paletteRows,
                    key = { row -> row.joinToString(separator = ":") { it.storageId } },
                ) { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { palette ->
                            PaletteChoice(
                                palette = palette,
                                selected = configuration.palette == palette,
                                onClick = {
                                    onConfigurationChange(configuration.copy(palette = palette))
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Changes apply instantly and are stored only on this device.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeStudioHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Theme Studio",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Make PaisaLens feel like yours",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.Close, contentDescription = "Close Theme Studio")
        }
    }
}

@Composable
private fun ThemeStudioSection(
    title: String,
    supportingText: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}

@Composable
private fun AppearanceChoice(
    mode: AppThemeMode,
    selected: Boolean,
    enabled: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                text = mode.label,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                stateDescription = when {
                    locked -> "Selected and locked"
                    selected -> "Selected"
                    else -> "Not selected"
                }
            },
    )
}

@Composable
private fun ThemeStyleChoice(
    style: AppThemeStyle,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = when (style) {
        AppThemeStyle.MATERIAL -> Icons.Rounded.Layers
        AppThemeStyle.AMOLED -> Icons.Rounded.DarkMode
        AppThemeStyle.GRADIENT -> Icons.Rounded.Gradient
    }
    val detail = when (style) {
        AppThemeStyle.MATERIAL -> "Balanced"
        AppThemeStyle.AMOLED -> "Pure black"
        AppThemeStyle.GRADIENT -> "Layered"
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 112.dp)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
                contentDescription = "${style.label}. ${style.description}"
            },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ThemeStyleIcon(icon = icon, selected = selected)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (style == AppThemeStyle.AMOLED) "AMOLED" else style.label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ThemeStyleIcon(icon: ImageVector, selected: Boolean) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PaletteChoice(
    palette: AppThemePalette,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 128.dp)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
                contentDescription = "${palette.label} palette. ${palette.description}"
            },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaletteSwatches(palette = palette)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = palette.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Spacer(Modifier.size(20.dp))
                }
            }
            Text(
                text = palette.description,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun PaletteSwatches(palette: AppThemePalette) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(9.dp))
            .semantics {
                contentDescription = "${palette.label} color preview"
            },
    ) {
        palette.swatches.forEach { argb ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(argb.toInt())),
            )
        }
    }
}

@Composable
private fun ThemePreview(
    configuration: AppThemeConfiguration,
    systemInDarkTheme: Boolean,
) {
    val preview = remember(configuration, systemInDarkTheme) {
        previewColors(configuration, systemInDarkTheme)
    }
    val modeLabel = if (configuration.style == AppThemeStyle.AMOLED) {
        "Dark · locked"
    } else {
        configuration.mode.label
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(preview.background)
            .then(
                if (configuration.style == AppThemeStyle.GRADIENT) {
                    Modifier.background(
                        Brush.linearGradient(
                            colors = listOf(
                                preview.primary.copy(alpha = if (preview.isDark) 0.32f else 0.20f),
                                Color.Transparent,
                                preview.secondary.copy(alpha = if (preview.isDark) 0.26f else 0.18f),
                            ),
                        ),
                    )
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "Live preview. ${configuration.style.label} style, $modeLabel appearance, ${configuration.palette.label} palette"
            }
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LIVE PREVIEW",
                        style = MaterialTheme.typography.labelMedium,
                        color = preview.onBackground,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${configuration.style.label} · $modeLabel",
                        style = MaterialTheme.typography.titleMedium,
                        color = preview.onBackground,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    configuration.palette.swatches.forEach { argb ->
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .background(Color(argb.toInt()), CircleShape),
                        )
                    }
                }
            }
            Surface(
                color = preview.surface,
                contentColor = preview.onBackground,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, preview.outline),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "This month",
                                style = MaterialTheme.typography.bodySmall,
                                color = preview.onBackground.copy(alpha = 0.72f),
                            )
                            Text(
                                text = "₹24,680",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = preview.onBackground,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(preview.primary.copy(alpha = 0.18f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Palette,
                                contentDescription = null,
                                tint = preview.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PreviewBar(preview.primary, 0.44f, Modifier.weight(0.44f))
                        PreviewBar(preview.secondary, 0.32f, Modifier.weight(0.32f))
                        PreviewBar(preview.tertiary, 0.24f, Modifier.weight(0.24f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewBar(color: Color, progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.22f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(8.dp)
                .background(color, CircleShape),
        )
    }
}

private data class PreviewColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val onBackground: Color,
    val outline: Color,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
)

private fun previewColors(
    configuration: AppThemeConfiguration,
    systemInDarkTheme: Boolean,
): PreviewColors {
    val isDark = configuration.isDark(systemInDarkTheme)
    val isAmoled = configuration.style == AppThemeStyle.AMOLED
    val palette = configuration.palette
    return PreviewColors(
        isDark = isDark,
        background = when {
            isAmoled -> Color.Black
            isDark -> Color(0xFF07111F)
            else -> Color(0xFFF4F7FC)
        },
        surface = when {
            isAmoled -> Color.Black
            isDark -> Color(0xFF132136)
            else -> Color.White
        },
        onBackground = if (isDark) Color(0xFFF7F8FF) else Color(0xFF111827),
        outline = if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.10f),
        primary = Color(palette.primaryArgb.toInt()),
        secondary = Color(palette.secondaryArgb.toInt()),
        tertiary = Color(palette.tertiaryArgb.toInt()),
    )
}
