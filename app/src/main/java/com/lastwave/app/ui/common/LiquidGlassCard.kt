package com.lastwave.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lastwave.app.ui.theme.isLiquidGlassEnabled
import com.lastwave.app.ui.theme.liquidGlassChrome

/**
 * Reusable liquid-glass card with a protected, crisp foreground layer.
 *
 * The optical substrate/reflection is painted before content and the thin
 * Fresnel edge after it. Text and icons are never placed in a RenderEffect,
 * blurred, dimmed, or covered by a translucent sheen.
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    enabled: Boolean = isLiquidGlassEnabled(),
    tintColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedContentColor = if (contentColor.isSpecified) {
        contentColor
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    if (!enabled) {
        val baseModifier = if (onClick != null) {
            modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
        } else {
            modifier
        }
        Card(
            modifier = baseModifier,
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = if (tintColor.isSpecified) tintColor else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = resolvedContentColor,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        ) {
            Column(Modifier.padding(16.dp), content = content)
        }
        return
    }

    val glassTint = (if (tintColor.isSpecified) tintColor else MaterialTheme.colorScheme.surfaceContainerHigh)
        .let { it.copy(alpha = it.alpha.coerceAtLeast(0.90f)) }
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(shape)
            .liquidGlassChrome(shape, enabled = true)
            .background(glassTint, shape)
            .then(clickModifier)
            .padding(16.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        CompositionLocalProvider(LocalContentColor provides resolvedContentColor) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}
