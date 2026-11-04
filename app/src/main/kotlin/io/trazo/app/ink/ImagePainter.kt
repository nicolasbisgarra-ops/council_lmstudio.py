package io.trazo.app.ink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.util.LruCache
import io.trazo.core.ImagePreset
import io.trazo.core.PageImage
import io.trazo.core.PageImages
import java.io.File

/**
 * Dibuja las imágenes de una hoja, debajo de la tinta. Los archivos viven en `images/` y se
 * guardan decodificados en una caché acotada por bytes. Una imagen con el ajuste «Modo oscuro» es
 * blanca con la tinta en el alfa: se tiñe clara sobre papel oscuro y casi negra sobre papel claro.
 */
object ImagePainter {
    @Volatile
    var dir: File? = null

    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<String>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val lightTint = PorterDuffColorFilter(0xFFE6E6E6.toInt(), PorterDuff.Mode.SRC_IN)
    private val darkTint = PorterDuffColorFilter(0xFF1A1A1A.toInt(), PorterDuff.Mode.SRC_IN)
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(110, 128, 128, 128)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val rect = RectF()

    fun file(name: String): File? = dir?.takeIf { PageImages.isValidName(name) }?.let { File(it, name) }

    /** El mapa de bits del archivo [name]; null si no existe o no se pudo leer. */
    fun bitmap(name: String): Bitmap? {
        cache.get(name)?.let { return it }
        synchronized(missing) { if (name in missing) return null }
        val f = file(name)?.takeIf { it.isFile }
        val bmp = f?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
        if (bmp == null) {
            // Se vuelve a intentar cuando el archivo aparezca (una importación que termina después).
            if (f != null) synchronized(missing) { missing += name }
            return null
        }
        cache.put(name, bmp)
        return bmp
    }

    /** Olvida un fallo previo: el archivo acaba de escribirse. */
    fun stored(name: String) = synchronized(missing) { missing -= name }

    fun draw(canvas: Canvas, images: List<PageImage>, darkPaper: Boolean) {
        for (img in images) {
            rect.set(img.left, img.top, img.right, img.bottom)
            val bmp = bitmap(img.asset)
            if (bmp == null || bmp.isRecycled) {
                canvas.drawRect(rect, placeholder)
                continue
            }
            paint.colorFilter = when (img.preset) {
                ImagePreset.DARK -> if (darkPaper) lightTint else darkTint
                else -> null
            }
            canvas.drawBitmap(bmp, null, rect, paint)
        }
        paint.colorFilter = null
    }
}
