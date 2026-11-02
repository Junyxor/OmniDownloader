package com.omni.downloader.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Experimental, original Compose glass styling inspired by FlClash's visual language.
 * No FlClash Flutter/GLSL source is copied: this is a lightweight translucent/rim
 * treatment, NOT a backdrop-sampling or refractive shader.
 *
 * The backdrop is drawn once behind the scaffold. List cards only draw a translucent
 * fill and a gradient border, avoiding per-item blur layers during progress updates.
 */
val LocalGlassEnabled = staticCompositionLocalOf { false }

@Composable
fun isDarkGlass(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.4f

fun glassPanelColor(dark: Boolean): Color =
    if (dark) Color(0xC1263044) else Color(0xD6FFFFFF)

fun glassRimBrush(dark: Boolean): Brush =
    if (dark) Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.56f), Color.White.copy(alpha = 0.10f), Color(0xFFA8BEFF).copy(alpha = 0.33f))
    ) else Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.98f), Color.White.copy(alpha = 0.33f), Color(0xFF7D9FC9).copy(alpha = 0.28f))
    )

/** Low-cost ambient background; the glass panels inherit this light instead of blurring lists. */
@Composable
fun OmniGlassBackdrop(content: @Composable BoxScope.() -> Unit) {
    val enabled = LocalGlassEnabled.current
    val dark = isDarkGlass()
    val ordinaryBackground = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier.fillMaxSize().drawWithCache {
            val base = if (dark) {
                Brush.linearGradient(
                    listOf(Color(0xFF111A2C), Color(0xFF161E31), Color(0xFF10131D)),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
            } else {
                Brush.linearGradient(
                    listOf(Color(0xFFF3F8FE), Color(0xFFF0F0FF), Color(0xFFFAF8FC)),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
            }
            val topGlow = Brush.radialGradient(
                colors = if (dark) listOf(Color(0xFF637FDD).copy(alpha = 0.20f), Color.Transparent)
                         else listOf(Color(0xFF8CBDF1).copy(alpha = 0.34f), Color.Transparent),
                center = Offset(size.width * 0.06f, size.height * 0.06f),
                radius = (size.width * 1.0f).coerceAtLeast(1f)
            )
            val sideGlow = Brush.radialGradient(
                colors = if (dark) listOf(Color(0xFF8A6CC7).copy(alpha = 0.17f), Color.Transparent)
                         else listOf(Color(0xFFBDA7F4).copy(alpha = 0.26f), Color.Transparent),
                center = Offset(size.width * 0.96f, size.height * 0.66f),
                radius = (size.width * 0.88f).coerceAtLeast(1f)
            )
            onDrawBehind {
                if (enabled) {
                    drawRect(brush = base)
                    drawRect(brush = topGlow)
                    drawRect(brush = sideGlow)
                } else {
                    drawRect(color = ordinaryBackground)
                }
            }
        },
        content = content
    )
}

/**
 * Floating three-tab glass dock with a single sliding lens.
 * Content remains an accessible selectable tab row; selection never rebuilds the pages.
 */
@Composable
fun OmniGlassDock(
    selectedIndex: Int,
    icons: List<ImageVector>,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    require(icons.isNotEmpty() && icons.size == labels.size && selectedIndex in icons.indices)
    val dark = isDarkGlass()
    val shell = if (dark) Color(0xD8273146) else Color(0xC9FFFFFF)
    val shape = RoundedCornerShape(34.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(12.dp, shape, clip = false),
        shape = shape,
        color = shell,
        border = BorderStroke(1.dp, glassRimBrush(dark)),
        tonalElevation = 0.dp
    ) {
        BoxWithConstraints(Modifier.height(68.dp).padding(5.dp)) {
            val widthPerTab = maxWidth / icons.size
            val indicatorOffset by androidx.compose.animation.core.animateDpAsState(
                targetValue = widthPerTab * selectedIndex,
                animationSpec = tween(270, easing = OmniMotion.Emphasized),
                label = "glassDockLens"
            )
            Box(
                Modifier
                    .offset(x = indicatorOffset)
                    .width(widthPerTab)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.verticalGradient(
                            if (dark) listOf(Color(0x806D84B6), Color(0x573C496B))
                            else listOf(Color(0xE9FFFFFF), Color(0x9CC9DDF6))
                        )
                    )
                    .border(1.dp, glassRimBrush(dark), RoundedCornerShape(28.dp))
                    .testTag("glass-nav-indicator")
            )

            Row(
                modifier = Modifier.fillMaxSize().selectableGroup(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                icons.forEachIndexed { index, icon ->
                    val selected = selectedIndex == index
                    val color by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primary
                                      else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(200),
                        label = "glassDockTabColor"
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(28.dp))
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { if (!selected) onSelect(index) }
                            )
                            .testTag("glass-nav-${index}"),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(23.dp),
                            tint = color
                        )
                        Text(
                            text = labels[index],
                            color = color,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
