package com.lastwave.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.ripple
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ONE reusable grouped-container system for every screen with a list of
 * related settings/options/rows — Settings pioneered this look (see its
 * original private GroupPosition/groupShape), this is that same design
 * language promoted to a shared component so Generator, Playlist, Discover,
 * and any future grouped list all look like the same app instead of each
 * screen inventing its own card style.
 *
 * The look: a group of rows reads as ONE continuous premium surface rather
 * than a stack of separate cards — first row rounded on top, last row
 * rounded on the bottom, middle rows nearly square, all separated by a
 * hairline [GroupGap] instead of normal item spacing so the gap itself
 * reads as the row divider (a deliberate alternative to a literal
 * HorizontalDivider line, which would look heavier against the tonal
 * surface these rows already use to separate from the screen background).
 */
enum class GroupPosition { SINGLE, TOP, MIDDLE, BOTTOM }

val GroupOuterRadius = 28.dp
val GroupInnerRadius = 6.dp
val GroupGap = 3.dp
private val GroupIconBadgeShape = RoundedCornerShape(14.dp)

fun groupShape(
    position: GroupPosition,
    outerRadius: Dp = GroupOuterRadius,
    innerRadius: Dp = GroupInnerRadius,
): RoundedCornerShape = when (position) {
    GroupPosition.SINGLE -> RoundedCornerShape(outerRadius)
    GroupPosition.TOP -> RoundedCornerShape(
        topStart = outerRadius, topEnd = outerRadius,
        bottomStart = innerRadius, bottomEnd = innerRadius,
    )
    GroupPosition.MIDDLE -> RoundedCornerShape(innerRadius)
    GroupPosition.BOTTOM -> RoundedCornerShape(
        topStart = innerRadius, topEnd = innerRadius,
        bottomStart = outerRadius, bottomEnd = outerRadius,
    )
}

/** Which position a 0-indexed row sits at within a group of [count] rows. */
fun groupPositionFor(index: Int, count: Int): GroupPosition = when {
    count <= 1 -> GroupPosition.SINGLE
    index == 0 -> GroupPosition.TOP
    index == count - 1 -> GroupPosition.BOTTOM
    else -> GroupPosition.MIDDLE
}

/**
 * Wraps a fixed list of rows and assigns each one its [GroupPosition]
 * automatically — SINGLE for a lone row, TOP/BOTTOM for the ends of a
 * longer group, MIDDLE for everything between.
 */
@Composable
fun ExpressiveGroup(
    rowCount: Int,
    modifier: Modifier = Modifier,
    gap: Dp = GroupGap,
    content: @Composable (index: Int, position: GroupPosition) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        for (i in 0 until rowCount) {
            content(i, groupPositionFor(i, rowCount))
        }
    }
}

@Composable
fun rememberGroupPressScale(interactionSource: MutableInteractionSource): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "groupPressScale",
    )
    return scale
}

@Composable
fun GroupIconBadge(
    icon: ImageVector,
    container: Color,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(GroupIconBadgeShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** A tappable row inside an [ExpressiveGroup] that navigates or triggers an
 *  action — icon badge, title/subtitle, and a trailing chevron (or a custom
 *  [trailing] slot, e.g. a value label). */
@Composable
fun ExpressiveGroupRow(
    icon: ImageVector,
    title: String,
    position: GroupPosition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    iconTint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberGroupPressScale(interactionSource)
    val titleColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

    Card(
        onClick = onClick,
        shape = groupShape(position),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        // 0dp deliberately: Material3's Card blends a primary-tinted alpha
        // layer on top of containerColor above 0dp tonalElevation, which
        // read as a second, unintended layer behind these rows' own tonal
        // icon badges (the same bug fixed earlier in Settings/Generator).
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth().scale(scale),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupIconBadge(icon, iconContainer, iconTint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A track row (artwork + title/artist + overflow) shaped like the rest of
 *  an [ExpressiveGroup] — used to bring Discover's feed and Playlist's
 *  track lists into the same "one continuous premium surface" language
 *  Settings/Generator pioneered, instead of each screen using its own
 *  separate per-item Card. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ExpressiveGroupTrackRow(
    title: String,
    subtitle: String,
    position: GroupPosition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    leading: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberGroupPressScale(interactionSource)
    val haptics = LocalHapticFeedback.current

    // A single combinedClickable (not Card's own onClick + a second,
    // separate gesture detector layered on top) — two independent tap
    // recognizers on the same node race for the same pointer-down event,
    // which is exactly the kind of thing that silently breaks either the
    // click or the long-press. combinedClickable handles both from one
    // recognizer, the way Compose actually intends this to be done.
    Card(
        shape = groupShape(position),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick,
                onLongClick = onLongClick?.let {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                },
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing?.invoke()
        }
    }
}

/** A row inside an [ExpressiveGroup] with a trailing Switch instead of a
 *  chevron — the whole row toggles it, matching Settings' existing rows. */
@Composable
fun ExpressiveGroupToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    position: GroupPosition,
    modifier: Modifier = Modifier,
    iconContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    iconTint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    ExpressiveGroupRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        position = position,
        onClick = { onCheckedChange(!checked) },
        iconContainer = iconContainer,
        iconTint = iconTint,
        modifier = modifier,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                thumbContent = if (checked) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else null,
            )
        },
    )
}

/** A row inside an [ExpressiveGroup] representing one of several mutually
 *  exclusive choices (Generator's playlist-source templates, and anywhere
 *  else picking one option from a grouped list makes sense) — selected
 *  state swaps the row and icon badge to the primary tone and shows a
 *  trailing checkmark, exactly like the pre-redesign ModeCard did, just now
 *  shape-aware so a run of these reads as one connected group. */
@Composable
fun ExpressiveGroupSelectRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    position: GroupPosition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeContainer: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    badgeTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    // Optional override for the leading badge — e.g. a real app icon
    // bitmap instead of the generic `icon` glyph. Defaults to null so
    // every existing call site (which only ever passed `icon`) is
    // unaffected.
    leadingContent: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberGroupPressScale(interactionSource)
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "groupSelectRowBg",
    )

    Card(
        onClick = onClick,
        shape = groupShape(position),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth().scale(scale),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingContent != null) {
                leadingContent()
            } else {
                GroupIconBadge(
                    icon = icon,
                    container = if (selected) MaterialTheme.colorScheme.primary else badgeContainer,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else badgeTint,
                    size = 48.dp,
                    iconSize = 24.dp,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
