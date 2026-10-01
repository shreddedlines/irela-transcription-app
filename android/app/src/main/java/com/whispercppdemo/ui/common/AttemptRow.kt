package com.whispercppdemo.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.whispercppdemo.jobs.JobAttempt
import com.whispercppdemo.ui.theme.LocalAppColors
import com.whispercppdemo.ui.theme.LocalSurfaceTokens

/**
 * A failed or cancelled attempt in History.
 *
 * It carries no inline buttons. Tapping it opens the same outcome screen the
 * app shows when a job ends (Try again / Choose another file / Back to Home);
 * press-and-hold selects it, and Try again / Remove live in the ⋮ menu.
 * Retry is only ever offered when [JobAttempt.retryable] is true.
 */
@Composable
fun AttemptRow(
    attempt: JobAttempt,
    title: String,
    fullTitle: String,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = if (attempt.cancelled) "Cancelled" else "Failed"
    val reason = attempt.reason ?: "Cancelled before it finished."
    HistoryRowShell(
        key = SelectionKeys.attempt(attempt.jobId),
        accessibilityLabel = "$status: $fullTitle. $reason",
        selected = selected,
        selecting = selecting,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        lead = {
            IconCircle(
                if (attempt.cancelled) Icons.Filled.Block else Icons.Filled.ErrorOutline,
                container = if (attempt.cancelled) LocalSurfaceTokens.current.containerHigh else LocalAppColors.current.errorTint,
                tint = if (attempt.cancelled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )
        }
    ) {
        // Cancellation is a normal outcome and is not painted as an error.
        Text(status, style = MaterialTheme.typography.labelMedium,
             color = if (attempt.cancelled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
             color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
             maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}
