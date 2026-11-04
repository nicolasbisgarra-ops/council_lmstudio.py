package io.trazo.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import io.trazo.core.ImagePreset
import io.trazo.core.ImageProcessing
import io.trazo.core.PageImages
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Lectura y procesado de imágenes para pegar en una hoja: se enderezan según su EXIF, se reducen a
 * [PageImages.MAX_SIDE] px y el recorte con perspectiva y el ajuste se calculan en el núcleo
 * ([ImageProcessing]). Todo corre fuera del hilo principal.
 */
object ImageImport {
    /** Lee la imagen; [open] se llama más de una vez. null si no es una imagen legible. */
    fun decode(open: () -> InputStream, maxSide: Int = PageImages.MAX_SIDE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val orientation = runCatching {
            open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return orient(decoded, orientation, maxSide)
    }

    /** Lee un archivo ya guardado (sin EXIF: se guardó derecho). */
    fun decodeFile(path: String): Bitmap? =
        runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) }.getOrNull()

    private fun orient(decoded: Bitmap, orientation: Int, maxSide: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
        if (scale < 1f) m.postScale(scale, scale)
        val out = if (m.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        if (out !== decoded) decoded.recycle()
        return if (out.config == Bitmap.Config.ARGB_8888) out else out.copy(Bitmap.Config.ARGB_8888, false).also { out.recycle() }
    }

    /** Copia reducida para la vista previa del editor. */
    fun scaledDown(src: Bitmap, maxSide: Int): Bitmap {
        val s = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        if (s >= 1f) return src
        return Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
    }

    /**
     * Recorta [quad] (esquinas sup-izq, sup-der, inf-der, inf-izq, de 0 a 1) enderezando la perspectiva
     * y aplica [preset].
     */
    fun process(src: Bitmap, quad: List<Float>, preset: ImagePreset, maxSide: Int = PageImages.MAX_SIDE): Bitmap {
        val w = src.width
        val h = src.height
        val px = FloatArray(8) { i -> quad[i] * if (i % 2 == 0) w else h }
        val full = quad == PageImages.FULL_QUAD
        val pixels = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val (ow, oh) = if (full) {
            val s = minOf(1f, maxSide.toFloat() / maxOf(w, h))
            (w * s).toInt().coerceAtLeast(1) to (h * s).toInt().coerceAtLeast(1)
        } else ImageProcessing.outputSize(px, maxSide)
        val warped = if (full && ow == w && oh == h) pixels else ImageProcessing.warp(pixels, w, h, px, ow, oh)
        val out = ImageProcessing.apply(warped, ow, oh, preset)
        return Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
    }

    /** WEBP de hasta [PageImages.MAX_BYTES]; null si no entra. */
    fun encode(bmp: Bitmap): ByteArray? {
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        for (quality in intArrayOf(92, 85, 75, 60, 45)) {
            val out = ByteArrayOutputStream()
            bmp.compress(format, quality, out)
            val bytes = out.toByteArray()
            if (bytes.size <= PageImages.MAX_BYTES && PageImages.extensionOf(bytes) == "webp") return bytes
        }
        return null
    }
}
