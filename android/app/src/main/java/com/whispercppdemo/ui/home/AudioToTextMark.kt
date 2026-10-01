package com.whispercppdemo.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whispercppdemo.ui.theme.LocalAppColors
import com.whispercppdemo.ui.theme.LocalSurfaceTokens
import com.whispercppdemo.ui.theme.TABULAR

/**
 * The Home mark: a short audio clip becoming a written document.
 *
 * Geometry is the approved reference measured in dp (a 193×188 box): a ghost
 * card, the clip card with seven level bars and a "0:24" duration, an arrow
 * that leaves the clip and drops onto the document, and the document with a
 * "T" and text lines, the first freshly written in the accent. Decorative, so
 * it is hidden from accessibility services.
 */
@Composable
fun AudioToTextMark(modifier: Modifier = Modifier) {
    val surfaces = LocalSurfaceTokens.current
    val app = LocalAppColors.current
    val cardShadow = Color.Black.copy(alpha = 0.45f)

    Box(modifier.size(193.dp, 188.dp).clearAndSetSemantics { }) {
        // ghost card behind the pair
        Box(Modifier.offset(40.dp, 40.dp).size(118.dp, 107.dp).clip(RoundedCornerShape(15.dp))
                .background(surfaces.containerLow))

        // audio clip
        Box(
            Modifier
                .size(118.dp, 88.dp)
                .shadow(12.dp, RoundedCornerShape(16.dp), ambientColor = cardShadow, spotColor = cardShadow)
                .background(surfaces.container, RoundedCornerShape(16.dp))
                .border(1.dp, surfaces.containerHigh, RoundedCornerShape(16.dp))
        ) {
            Row(
                Modifier.padding(start = 16.dp).fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(4.2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(14, 25, 42, 27, 36, 22, 13).forEach { h ->
                    Box(Modifier.width(3.6.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(app.textTertiary))
                }
            }
            Box(
                Modifier.offset(74.dp, 36.dp).height(16.dp).clip(RoundedCornerShape(8.dp))
                    .background(surfaces.containerHigh).padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("0:24", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 16.sp,
                     fontWeight = FontWeight.Normal, fontFeatureSettings = TABULAR),
                     color = app.textTertiary)
            }
        }

        // arrow: leaves the clip's top-right edge, sweeps right, drops onto the document
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 193f
            val stroke = Stroke(width = 2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val curve = Path().apply {
                moveTo(120f * u, 22.5f * u)
                cubicTo(141f * u, 19.5f * u, 154.5f * u, 30f * u, 154f * u, 55f * u)
            }
            drawPath(curve, app.accentLine, style = stroke)
            val head = Path().apply {
                moveTo(147f * u, 49.5f * u)
                lineTo(154f * u, 57f * u)
                lineTo(161f * u, 49.5f * u)
            }
            drawPath(head, app.accentLine, style = stroke)
        }

        // document
        Box(
            Modifier
                .offset(74.dp, 64.dp)
                .size(119.dp, 124.dp)
                .shadow(12.dp, RoundedCornerShape(16.dp), ambientColor = cardShadow, spotColor = cardShadow)
                .background(surfaces.container, RoundedCornerShape(16.dp))
                .border(1.dp, surfaces.containerHighest, RoundedCornerShape(16.dp))
        ) {
            Text(
                "T",
                style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 19.sp, lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(17.dp, 15.dp)
            )
            Line(17.dp, 45.dp, 83.dp, surfaces.containerHighest)
            Line(17.dp, 45.dp, 74.dp, app.accentFill)
            Line(17.dp, 62.dp, 45.dp, surfaces.containerHighest)
            Line(65.dp, 62.dp, 35.dp, surfaces.containerHighest)
            Line(17.dp, 77.dp, 68.dp, surfaces.containerHighest)
            Line(17.dp, 93.dp, 54.dp, surfaces.containerHighest)
        }
    }
}

@Composable
private fun Line(x: Dp, y: Dp, width: Dp, color: Color) {
    Box(Modifier.offset(x, y).size(width, 6.dp).clip(RoundedCornerShape(3.dp)).background(color))
}
