package com.omni.downloader.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.FilledTonalButton as MaterialTonalButton
import androidx.compose.material3.IconButton as MaterialIconButton
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.SuggestionChip as MaterialSuggestionChip
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Shared V2 visual treatment for real Material3 controls.
 * Press/keyboard/semantics/ripple keep using the framework controls.
 * Blur is intentionally confined to major overlay surfaces (navigation
 * dock) rather than creating a full backdrop capture for each list button.
 */
@Composable
fun Modifier.glassChrome(
    shape: Shape = RoundedCornerShape(24.dp),
    tint: Color = Color.Unspecified
): Modifier {
    if (!LocalGlassEnabled.current) return this
    val dark = isDarkGlass()
    val top = if (dark) Color(0xA05A6480) else Color(0xF2FFFFFF)
    val bottom = if (dark) Color(0x863A405A) else Color(0xB6DCE8FC)
    val colors = if (tint != Color.Unspecified) {
        listOf(tint.copy(alpha = 0.40f), tint.copy(alpha = 0.20f))
    } else listOf(top, bottom)
    val brush = remember(colors[0], colors[1]) { Brush.verticalGradient(colors) }
    val rim = remember(dark) { glassRimBrush(dark) }
    return this
        .clip(shape)
        .background(brush, shape)
        .border(1.dp, rim, shape)
}

/** Creates a pressed, physically subtle control without a perpetual animation. */
@Composable
private fun rememberChromePress(): Pair<MutableInteractionSource, Float> {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 590f),
        label = "glassControlPress"
    )
    return source to scale
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(24.dp),
    colors: ButtonColors? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val glass = LocalGlassEnabled.current
    val (source, scale) = rememberChromePress()
    val effectiveColors = if (glass && colors == null) ButtonDefaults.buttonColors(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        disabledContainerColor = Color.Transparent,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) else colors ?: ButtonDefaults.buttonColors()

    MaterialButton(
        onClick = onClick,
        modifier = modifier
            .then(if (glass) Modifier.graphicsLayer { scaleX = scale; scaleY = scale } else Modifier)
            .glassChrome(shape),
        enabled = enabled,
        shape = shape,
        colors = effectiveColors,
        elevation = null,
        border = null,
        contentPadding = contentPadding,
        interactionSource = source,
        content = content
    )
}

@Composable
fun GlassOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(24.dp),
    colors: ButtonColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val glass = LocalGlassEnabled.current
    val (source, scale) = rememberChromePress()
    MaterialOutlinedButton(
        onClick = onClick,
        modifier = modifier
            .then(if (glass) Modifier.graphicsLayer { scaleX = scale; scaleY = scale } else Modifier)
            .glassChrome(shape),
        enabled = enabled,
        shape = shape,
        colors = if (glass && colors == null) ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary
        ) else colors ?: ButtonDefaults.outlinedButtonColors(),
        border = if (glass) null else border ?: ButtonDefaults.outlinedButtonBorder,
        contentPadding = contentPadding,
        interactionSource = source,
        content = content
    )
}

@Composable
fun GlassFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(24.dp),
    colors: ButtonColors? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val glass = LocalGlassEnabled.current
    val (source, scale) = rememberChromePress()
    MaterialTonalButton(
        onClick = onClick,
        modifier = modifier
            .then(if (glass) Modifier.graphicsLayer { scaleX = scale; scaleY = scale } else Modifier)
            .glassChrome(shape),
        enabled = enabled,
        shape = shape,
        colors = if (glass && colors == null) ButtonDefaults.filledTonalButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary
        ) else colors ?: ButtonDefaults.filledTonalButtonColors(),
        elevation = null,
        border = null,
        contentPadding = contentPadding,
        interactionSource = source,
        content = content
    )
}

@Composable
fun GlassTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(20.dp),
    colors: ButtonColors? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit
) {
    val glass = LocalGlassEnabled.current
    val (source, scale) = rememberChromePress()
    MaterialTextButton(
        onClick = onClick,
        modifier = modifier
            .then(if (glass) Modifier.graphicsLayer { scaleX = scale; scaleY = scale } else Modifier)
            .glassChrome(shape),
        enabled = enabled,
        shape = shape,
        colors = colors ?: ButtonDefaults.textButtonColors(),
        contentPadding = contentPadding,
        interactionSource = source,
        content = content
    )
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val glass = LocalGlassEnabled.current
    val shape = RoundedCornerShape(14.dp)
    MaterialIconButton(
        onClick = onClick,
        modifier = modifier.then(if (glass) Modifier.glassChrome(shape) else Modifier),
        enabled = enabled,
        content = content
    )
}

@Composable
fun GlassSuggestionChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val glass = LocalGlassEnabled.current
    val shape = RoundedCornerShape(18.dp)
    MaterialSuggestionChip(
        onClick = onClick,
        label = label,
        modifier = modifier.glassChrome(shape),
        enabled = enabled,
        shape = shape,
        border = if (glass) null else androidx.compose.material3.SuggestionChipDefaults.suggestionChipBorder(enabled),
        colors = androidx.compose.material3.SuggestionChipDefaults.suggestionChipColors(
            containerColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.surface,
        )
    )
}
