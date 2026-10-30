package io.trazo.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.trazo.app.ink.StrokePainter
import io.trazo.core.AiNotes
import io.trazo.core.Bounds
import io.trazo.core.Brush
import io.trazo.core.InkStroke
import io.trazo.core.bounds
import java.io.ByteArrayOutputStream

/**
 * La captura que se envía (39): tinta negra sobre blanco, sin cuadrícula ni resaltador, recortada a lo que
 * se revisa. A la izquierda va el número de cada renglón, el mismo del texto, para que el modelo los ubique.
 */
object AiCapture {
    private const val MARGIN = 16f
    private const val NUMBERS = 44f

    fun png(strokes: List<InkStroke>, lines: List<AiNotes.ReviewLine>, longEdgePx: Int): ByteArray {
        val ink = strokes.filter { it.brush != Brush.HIGHLIGHTER }.map { it.copy(color = Color.BLACK) }
        val b: Bounds = (ink.takeIf { it.isNotEmpty() }?.bounds() ?: Bounds(0f, 0f, 100f, 100f))
        val w = b.width + MARGIN * 2 + NUMBERS
        val h = b.height + MARGIN * 2
        val scale = longEdgePx / maxOf(w, h)
        val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            canvas.scale(scale, scale)
            canvas.translate(MARGIN + NUMBERS - b.left, MARGIN - b.top)
            StrokePainter.drawAll(canvas, ink)
            val number = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 18f; textAlign = Paint.Align.RIGHT }
            for (l in lines) canvas.drawText("${l.number}", b.left - 10f, l.bounds.centerY + 6f, number)
            return ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        } finally {
            bmp.recycle()
        }
    }
}

/** Avisa cuando vuelve la conexión, para mandar lo que quedó en cola (37). */
class AiConnectivity(context: Context, private val onOnline: () -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onOnline()
    }
    private var registered = false

    val online: Boolean
        get() = runCatching {
            cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(true)

    fun start() {
        if (registered || cm == null) return
        registered = runCatching { cm.registerDefaultNetworkCallback(callback); true }.getOrDefault(false)
    }

    fun stop() {
        if (registered) runCatching { cm?.unregisterNetworkCallback(callback) }
        registered = false
    }
}
