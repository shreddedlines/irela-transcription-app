package com.whispercppdemo.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.whispercppdemo.ui.theme.LocalAppColors
import com.whispercppdemo.ui.theme.LocalSurfaceTokens
import com.whispercppdemo.ui.theme.Sizes
import com.whispercppdemo.ui.theme.Spacing

/*
 * Concept A (Quiet Instrument) building blocks. Every screen is made from
 * these, so shapes, heights and colours cannot drift screen by screen.
 */

enum class ButtonKind { FILLED, TONAL, DANGER, TEXT, TEXT_NEUTRAL, TEXT_DANGER }

/**
 * The app button: 56dp minimum (it grows with large text instead of clipping),
 * 16dp corners, 16sp semibold label. Text variants are 48dp and 14sp.
 */
@Composable
fun AppButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.FILLED,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    fillWidth: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    val app = LocalAppColors.current
    val surfaces = LocalSurfaceTokens.current
    val (container, content) = when (kind) {
        ButtonKind.FILLED -> colors.primary to colors.onPrimary
        ButtonKind.TONAL -> surfaces.containerHigh to colors.onSurface
        ButtonKind.DANGER -> app.errorTint to colors.error
        ButtonKind.TEXT -> Color.Transparent to colors.primary
        ButtonKind.TEXT_NEUTRAL -> Color.Transparent to colors.onSurfaceVariant
        ButtonKind.TEXT_DANGER -> Color.Transparent to colors.error
    }
    val isText = kind == ButtonKind.TEXT || kind == ButtonKind.TEXT_NEUTRAL || kind == ButtonKind.TEXT_DANGER
    val shape = MaterialTheme.shapes.large
    Row(
        modifier = modifier
            .then(if (fillWidth && !isText) Modifier.fillMaxWidth() else Modifier)
            .alpha(if (enabled) 1f else 0.38f)
            .heightIn(min = if (isText) Spacing.touchTarget else Sizes.buttonHeight)
            .clip(shape)
            .background(container, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (isText) Spacing.stackMd else Spacing.stackLg, vertical = Spacing.stackSm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Spacing.stackSm))
        }
        Text(
            label,
            style = if (isText) MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            else MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = content,
            textAlign = TextAlign.Center
        )
        if (trailingIcon != null) {
            Spacer(Modifier.width(Spacing.stackSm))
            Icon(trailingIcon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        }
    }
}

/** A 40dp (or 64dp) circle carrying one icon. */
@Composable
fun IconCircle(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.iconCircle,
    container: Color = LocalSurfaceTokens.current.containerHighest,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    iconSize: Dp = if (size > Sizes.iconCircle) 30.dp else 22.dp
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

enum class BannerTone { INFO, WARNING, ERROR }

/** Inline status: icon plus text on a 12dp-corner tonal ground. */
@Composable
fun Banner(
    icon: ImageVector,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val app = LocalAppColors.current
    val colors = MaterialTheme.colorScheme
    val surfaces = LocalSurfaceTokens.current
    val (bg, iconTint) = when (tone) {
        BannerTone.INFO -> surfaces.containerLow to colors.primary
        BannerTone.WARNING -> app.warningContainer to app.warning
        BannerTone.ERROR -> colors.errorContainer to colors.onErrorContainer
    }
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .then(if (tone == BannerTone.INFO) Modifier.border(1.dp, app.primaryOutline, shape) else Modifier)
            .padding(horizontal = Spacing.stackMd, vertical = Spacing.gutter),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.gutter))
        content()
    }
}

/** Banner with plain text in the tone's text colour. */
@Composable
fun TextBanner(icon: ImageVector, tone: BannerTone, text: String, modifier: Modifier = Modifier) {
    Banner(icon, tone, modifier) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = when (tone) {
                BannerTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                BannerTone.WARNING -> MaterialTheme.colorScheme.onSurface
                BannerTone.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f)
        )
    }
}

/** Small read-only chip (History privacy note, transcript source). */
@Composable
fun InfoChip(text: String, icon: ImageVector?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .heightIn(min = 28.dp)
            .clip(MaterialTheme.shapes.small)
            .background(LocalSurfaceTokens.current.containerHigh)
            .padding(horizontal = 10.dp, vertical = Spacing.unit),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One entry of an overflow menu. */
data class MenuEntry(
    val label: String,
    val icon: ImageVector?,
    val destructive: Boolean = false,
    val dividerBefore: Boolean = false,
    val onClick: () -> Unit
)

/**
 * The overflow menu: 8dp corners, container-high ground, 48dp rows.
 *
 * The popup is NOT focusable. On Android 16 with targetSdk 36, Back arrives as
 * a predictive-back callback on the focused window; a focusable Compose 1.5
 * popup never receives it, so Back silently did nothing while a menu was open.
 * Non-focusable, Back reaches the activity and [BackHandler] closes the menu;
 * a tap outside still dismisses it.
 */
@Composable
fun AppMenu(expanded: Boolean, onDismiss: () -> Unit, entries: List<MenuEntry>) {
    BackHandler(enabled = expanded) { onDismiss() }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnBackPress = true, dismissOnClickOutside = true),
        modifier = Modifier
            .background(LocalSurfaceTokens.current.containerHigh)
            .width(224.dp)
    ) {
        entries.forEach { entry ->
            if (entry.dividerBefore) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.stackSm)
                        .heightIn(min = 1.dp, max = 1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            val tint = if (entry.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            DropdownMenuItem(
                text = { Text(entry.label, style = MaterialTheme.typography.bodyMedium, color = tint) },
                leadingIcon = entry.icon?.let { ic ->
                    {
                        Icon(ic, contentDescription = null,
                             tint = if (entry.destructive) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                             modifier = Modifier.size(20.dp))
                    }
                },
                onClick = { onDismiss(); entry.onClick() }
            )
        }
    }
}

/** A caption-sized group label ("Today", "Yesterday"). */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** Centered icon, headline and body -- the empty and permission states. */
@Composable
fun StatementBlock(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    body: String?,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconCircle(icon, size = Sizes.iconCircleLarge, container = LocalSurfaceTokens.current.containerHigh, tint = iconTint)
        Spacer(Modifier.size(Spacing.stackLg))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface,
             textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.size(Spacing.stackSm))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                 textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
        }
    }
}
