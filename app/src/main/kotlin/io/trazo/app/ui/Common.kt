package io.trazo.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.trazo.core.Bounds
import io.trazo.core.DEFAULT_INK_COLOR
import io.trazo.core.InkStroke
import io.trazo.core.bounds
import kotlin.math.max
import kotlin.math.min

private val Light = lightColorScheme(
    primary = Color(0xFF3F7D6B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE7DD),
    onPrimaryContainer = Color(0xFF0F2E25),
    secondary = Color(0xFF5B6560),
    background = Color(0xFFF4F1E8),
    surface = Color(0xFFFBFAF5),
    surfaceVariant = Color(0xFFE6E3DA),
    onSurface = Color(0xFF263B34),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8CC9B4),
    onPrimary = Color(0xFF0F2E25),
    primaryContainer = Color(0xFF285446),
    background = Color(0xFF141615),
    surface = Color(0xFF1C1F1E),
    surfaceVariant = Color(0xFF2B2F2E),
    onSurface = Color(0xFFE8EEEA),
)

@Composable
fun TrazoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Paleta de tintas del lápiz (ARGB). */
val InkColors = listOf(DEFAULT_INK_COLOR, 0xFF1E4FA0.toInt(), 0xFFB3261E.toInt(), 0xFF6A3FA0.toInt())

/**
 * Vista previa de tinta: dibuja los trazos como líneas vectoriales (nunca texto ni imagen).
 * @param frame si se indica, encaja ese rectángulo (p. ej. la hoja completa) en lugar del de los trazos.
 */
@Composable
fun InkPreview(strokes: List<InkStroke>, modifier: Modifier = Modifier, frame: Bounds? = null) {
    val dark = isSystemInDarkTheme()
    val fallback = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        if (strokes.isEmpty() && frame == null) return@Canvas
        val b = frame ?: strokes.bounds()
        val pad = 6.dp.toPx()
        val s = min((size.width - 2 * pad) / max(b.width, 1f), (size.height - 2 * pad) / max(b.height, 1f))
        val ox = (size.width - b.width * s) / 2f - b.left * s
        val oy = (size.height - b.height * s) / 2f - b.top * s
        for (st in strokes) {
            val color = if (dark && st.color == DEFAULT_INK_COLOR) fallback else Color(st.color)
            val w = (st.width * s).coerceIn(1.2f, 3.5.dp.toPx())
            if (st.points.size == 1) {
                drawCircle(color, w / 2f, Offset(ox + st.points[0].x * s, oy + st.points[0].y * s))
                continue
            }
            val path = Path()
            st.points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(ox + p.x * s, oy + p.y * s) else path.lineTo(ox + p.x * s, oy + p.y * s)
            }
            drawPath(path, color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** ¿El dispositivo tiene lápiz? Solo informativo para los ajustes. */
fun hasStylusDevice(): Boolean = android.view.InputDevice.getDeviceIds().any { id ->
    android.view.InputDevice.getDevice(id)?.supportsSource(android.view.InputDevice.SOURCE_STYLUS) == true
}
