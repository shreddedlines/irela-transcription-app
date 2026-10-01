package com.whispercppdemo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whispercppdemo.ui.theme.LocalAppColors
import com.whispercppdemo.ui.theme.LocalSurfaceTokens
import com.whispercppdemo.ui.theme.Sizes
import com.whispercppdemo.ui.theme.Spacing

/**
 * The Concept A top app bar: 64dp on the background, optional back or close,
 * a 20sp title that truncates, and a trailing action slot.
 *
 * Edge-to-edge is forced at targetSdk 35+, so the bar paints behind the status
 * bar and pads its content below it (and clear of a side cutout in landscape).
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    navigationIcon: ImageVector = Icons.Filled.ArrowBack,
    navigationLabel: String = "Back",
    /** False when the scaffold above already reserves the status bar (Edit). */
    applyStatusInset: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Background first, so the bar colour also fills the status-bar area.
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(
                if (applyStatusInset) WindowInsets.statusBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                else WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)
            )
            .height(Sizes.topBar)
            .padding(start = if (onBack != null) Spacing.unit else Spacing.edgeMargin, end = Spacing.unit),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            TopBarIcon(navigationIcon, navigationLabel, onBack, tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(Spacing.unit))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = true)
        )
        actions()
    }
}

/** A 48dp circular icon target for top bars. */
@Composable
fun TopBarIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Box(
        Modifier
            .size(Spacing.touchTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/**
 * For destinations that draw their own header (Home, History, Edit): only the
 * status-bar inset, painted in the background colour.
 */
@Composable
fun StatusBarInset(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(
                WindowInsets.statusBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
    )
}

/** The two destinations the bottom bar offers. */
enum class NavTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Home", Icons.Outlined.Home, Icons.Filled.Home),
    HISTORY("History", Icons.Outlined.History, Icons.Filled.History)
}

/**
 * The Concept A navigation bar: 76dp, container surface with 24dp top corners,
 * two items each with a 70×36 pill that fills with the primary tint when
 * current.
 *
 * [enabled] is false while a transcription is running (the bar is normally not
 * shown then; the guard is kept so it can never be used to walk away from a
 * job in flight).
 */
@Composable
fun AppBottomNav(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            // Background first so the bar colour extends under the gesture
            // handle, then the inset, so the tabs sit above it.
            .background(LocalSurfaceTokens.current.container)
            .windowInsetsPadding(
                WindowInsets.navigationBars
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
            )
            .height(Sizes.navBar)
            .padding(bottom = Spacing.unit),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavTab.values().forEach { tab ->
            val isSelected = tab == selected
            val tint = if (isSelected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .alpha(if (enabled) 1f else 0.38f)
                    .selectable(selected = isSelected, enabled = enabled, role = Role.Tab) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier
                        .size(width = 70.dp, height = 36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (isSelected) LocalAppColors.current.primaryTint
                                    else androidx.compose.ui.graphics.Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (isSelected) tab.selectedIcon else tab.icon, contentDescription = null,
                         tint = tint, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(Spacing.unit))
                Text(tab.label, style = MaterialTheme.typography.labelLarge, color = tint)
            }
        }
    }
}
