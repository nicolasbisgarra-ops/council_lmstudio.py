package io.trazo.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ajuste aplicado a una imagen de la hoja.
 * - [DOCUMENT]: blanco y negro limpio (fondo blanco, texto negro, sin sombras, más definición).
 * - [DARK]: lo mismo pero guardado como máscara de tinta: el texto toma un color claro sobre una hoja
 *   oscura (o negro al exportar sobre papel claro) y el fondo deja ver la hoja.
 */
@Serializable
enum class ImagePreset { NONE, DOCUMENT, DARK }

/**
 * Imagen pegada o importada en una hoja; va debajo de la tinta. [asset] es la imagen ya recortada y
 * ajustada; [source] (opcional) es la original para volver a recortarla, con [quad] las cuatro esquinas
 * elegidas (sup-izq, sup-der, inf-der, inf-izq) normalizadas 0..1 sobre la original.
 */
@Serializable
data class PageImage(
    val id: String,
    val asset: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val preset: ImagePreset = ImagePreset.NONE,
    val source: String? = null,
    val quad: List<Float>? = null,
    /** Fondo fijo (hoja de un PDF importado): no se selecciona, mueve ni borra al vaciar la hoja. */
    val locked: Boolean = false,
) {
    val bounds: Bounds get() = Bounds(left, top, right, bottom)

    /** Mover y escalar conservan la proporción; una rotación toma el rectángulo que la contiene. */
    fun transformed(t: Affine): PageImage {
        val xs = floatArrayOf(t.mapX(left, top), t.mapX(right, top), t.mapX(right, bottom), t.mapX(left, bottom))
        val ys = floatArrayOf(t.mapY(left, top), t.mapY(right, top), t.mapY(right, bottom), t.mapY(left, bottom))
        return copy(left = xs.min(), top = ys.min(), right = xs.max(), bottom = ys.max())
    }

    /** Assets que usa en disco. */
    val assets: List<String> get() = listOfNotNull(asset, source)
}

/** Imágenes de las hojas: archivos `images/<sha256>.webp` (o `.png`) referidos por nombre. */
object PageImages {
    const val MAX_BYTES = 4 * 1024 * 1024
    /** Lado máximo en píxeles de lo que se guarda. */
    const val MAX_SIDE = 2400
    /** Ancho que ocupa por defecto en la hoja (fracción del ancho). */
    const val DEFAULT_WIDTH_FRACTION = 0.8f

    private val NAME = Regex("[0-9a-f]{64}\\.(webp|png)")

    fun isValidName(name: String?): Boolean = name != null && NAME.matches(name)

    /** Extensión de la imagen o null si no es aceptable (tamaño o formato). */
    fun extensionOf(b: ByteArray): String? = when {
        b.isEmpty() || b.size > MAX_BYTES -> null
        Covers.isWebp(b) -> "webp"
        Covers.isPng(b) -> "png"
        else -> null
    }

    /** Rectángulo en la hoja para una imagen de [w]×[h] px centrada en (cx, cy), dentro de la hoja. */
    fun placement(w: Int, h: Int, cx: Float, cy: Float, maxWidth: Float = Notebook.PAGE_WIDTH * DEFAULT_WIDTH_FRACTION): Bounds {
        require(w > 0 && h > 0)
        val maxH = Notebook.PAGE_HEIGHT * 0.8f
        val s = min(maxWidth / w, maxH / h)
        val bw = w * s
        val bh = h * s
        val x = cx.coerceIn(bw / 2f, Notebook.PAGE_WIDTH - bw / 2f)
        val y = cy.coerceIn(bh / 2f, Notebook.PAGE_HEIGHT - bh / 2f)
        return Bounds(x - bw / 2f, y - bh / 2f, x + bw / 2f, y + bh / 2f)
    }

    /** Esquinas por defecto: la imagen entera. */
    val FULL_QUAD: List<Float> = listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    /** Un cuadrilátero válido: 8 valores finitos dentro de 0..1, convexo y no degenerado. */
    fun isValidQuad(q: List<Float>?): Boolean {
        if (q == null || q.size != 8 || q.any { !it.isFinite() || it < -0.001f || it > 1.001f }) return false
        var sign = 0
        for (i in 0 until 4) {
            val ax = q[2 * i]; val ay = q[2 * i + 1]
            val bx = q[2 * ((i + 1) % 4)]; val by = q[2 * ((i + 1) % 4) + 1]
            val cx = q[2 * ((i + 2) % 4)]; val cy = q[2 * ((i + 2) % 4) + 1]
            val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
            if (abs(cross) < 1e-6f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Las imágenes cuyo archivo falta se quitan; si falta la original, ya no se puede recortar de nuevo. */
    fun Page.withAvailableImages(available: (String) -> Boolean): Page {
        if (images.isEmpty()) return this
        val kept = images.mapNotNull { img ->
            when {
                !isValidName(img.asset) || !available(img.asset) -> null
                img.source != null && (!isValidName(img.source) || !available(img.source)) -> img.copy(source = null, quad = null)
                else -> img
            }
        }
        return if (kept == images) this else copy(images = kept)
    }
}

/** Todos los archivos de imagen que usa el cuaderno. */
fun Notebook.imageAssets(): Set<String> = pages.flatMapTo(LinkedHashSet()) { p -> p.images.flatMap { it.assets } }

fun Notebook.withAvailableImages(available: (String) -> Boolean): Notebook {
    var changed = false
    val out = pages.map { p -> with(PageImages) { p.withAvailableImages(available) }.also { if (it !== p) changed = true } }
    return if (changed) copy(pages = out) else this
}

/**
 * Procesamiento de imágenes en Kotlin puro sobre píxeles ARGB (`0xAARRGGBB`), para poder probarlo sin
 * Android: enderezar con perspectiva y aplicar los presets.
 */
object ImageProcessing {

    /** Tamaño de salida del recorte: el promedio de los lados opuestos, con el lado mayor ≤ [maxSide]. */
    fun outputSize(quad: FloatArray, maxSide: Int = PageImages.MAX_SIDE): Pair<Int, Int> {
        require(quad.size == 8)
        fun d(i: Int, j: Int) = hypot(quad[2 * i] - quad[2 * j], quad[2 * i + 1] - quad[2 * j + 1])
        var w = (d(0, 1) + d(3, 2)) / 2f
        var h = (d(0, 3) + d(1, 2)) / 2f
        val s = min(1f, maxSide / max(w, h).coerceAtLeast(1f))
        w *= s; h *= s
        return max(1, w.roundToInt()) to max(1, h.roundToInt())
    }

    /**
     * Endereza el cuadrilátero [quad] (8 coordenadas en píxeles de la imagen: sup-izq, sup-der, inf-der,
     * inf-izq) a un rectángulo de [ow]×[oh], con interpolación bilineal.
     */
    fun warp(src: IntArray, sw: Int, sh: Int, quad: FloatArray, ow: Int, oh: Int): IntArray {
        require(src.size == sw * sh && quad.size == 8 && ow > 0 && oh > 0)
        val m = squareToQuad(quad)
        val out = IntArray(ow * oh)
        for (y in 0 until oh) {
            val v = (y + 0.5) / oh
            for (x in 0 until ow) {
                val u = (x + 0.5) / ow
                val den = m[6] * u + m[7] * v + 1.0
                val px = (m[0] * u + m[1] * v + m[2]) / den - 0.5
                val py = (m[3] * u + m[4] * v + m[5]) / den - 0.5
                out[y * ow + x] = sample(src, sw, sh, px, py)
            }
        }
        return out
    }

    /** Homografía del cuadrado unidad al cuadrilátero (Heckbert): x = (a u + b v + c) / (g u + h v + 1). */
    internal fun squareToQuad(q: FloatArray): DoubleArray {
        val x0 = q[0].toDouble(); val y0 = q[1].toDouble()
        val x1 = q[2].toDouble(); val y1 = q[3].toDouble()
        val x2 = q[4].toDouble(); val y2 = q[5].toDouble()
        val x3 = q[6].toDouble(); val y3 = q[7].toDouble()
        val dx3 = x0 - x1 + x2 - x3
        val dy3 = y0 - y1 + y2 - y3
        if (abs(dx3) < 1e-9 && abs(dy3) < 1e-9) {
            return doubleArrayOf(x1 - x0, x3 - x0, x0, y1 - y0, y3 - y0, y0, 0.0, 0.0)
        }
        val dx1 = x1 - x2; val dx2 = x3 - x2
        val dy1 = y1 - y2; val dy2 = y3 - y2
        val den = dx1 * dy2 - dx2 * dy1
        val g = (dx3 * dy2 - dx2 * dy3) / den
        val h = (dx1 * dy3 - dx3 * dy1) / den
        return doubleArrayOf(x1 - x0 + g * x1, x3 - x0 + h * x3, x0, y1 - y0 + g * y1, y3 - y0 + h * y3, y0, g, h)
    }

    private fun sample(src: IntArray, w: Int, h: Int, x: Double, y: Double): Int {
        val cx = x.coerceIn(0.0, (w - 1).toDouble())
        val cy = y.coerceIn(0.0, (h - 1).toDouble())
        val x0 = cx.toInt(); val y0 = cy.toInt()
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val fx = cx - x0; val fy = cy - y0
        val p00 = src[y0 * w + x0]; val p10 = src[y0 * w + x1]
        val p01 = src[y1 * w + x0]; val p11 = src[y1 * w + x1]
        var out = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val a = (p00 ushr shift) and 0xFF; val b = (p10 ushr shift) and 0xFF
            val c = (p01 ushr shift) and 0xFF; val d = (p11 ushr shift) and 0xFF
            val top = a + (b - a) * fx
            val bottom = c + (d - c) * fx
            val v = (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
            out = out or (v shl shift)
        }
        return out
    }

    /** Aplica [preset] y devuelve píxeles nuevos (el original no cambia). */
    fun apply(px: IntArray, w: Int, h: Int, preset: ImagePreset): IntArray = when (preset) {
        ImagePreset.NONE -> px.copyOf()
        ImagePreset.DOCUMENT -> document(px, w, h).let { g -> IntArray(g.size) { gray(g[it]) } }
        // Máscara: blanco con alfa = cuánta tinta hay; la app la tiñe según el color de la hoja.
        ImagePreset.DARK -> document(px, w, h).let { g -> IntArray(g.size) { ((255 - g[it]) shl 24) or 0xFFFFFF } }
    }

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /**
     * Documento limpio en grises (0 = tinta, 255 = papel): divide por una estimación del papel (quita
     * sombras y luz despareja), da definición con una máscara de enfoque y estira el contraste.
     */
    internal fun document(px: IntArray, w: Int, h: Int): IntArray {
        require(px.size == w * h && w > 0 && h > 0)
        val lum = IntArray(px.size) { i ->
            val p = px[i]
            val a = (p ushr 24) and 0xFF
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val l = (r * 299 + g * 587 + b * 114) / 1000
            // Lo transparente cuenta como papel.
            (l * a + 255 * (255 - a)) / 255
        }
        val bg = background(lum, w, h)
        val norm = IntArray(lum.size) { i -> (lum[i] * 255 / max(bg[i], 16)).coerceIn(0, 255) }
        val sharp = sharpen(norm, w, h, amount = 1.2f)
        // Estirar: el trazo típico (la mediana de lo que es tinta) va a negro y el papel a blanco.
        val hist = IntArray(256).also { hst -> sharp.forEach { hst[it]++ } }
        val inkCount = (0 until INK_LEVEL).sumOf { hist[it] }
        val lo = if (inkCount < sharp.size / 500) 0 else {
            val inkHist = IntArray(256).also { for (v in 0 until INK_LEVEL) it[v] = hist[v] }
            percentile(inkHist, inkCount, 0.5f).coerceAtMost(140)
        }
        val hi = max(lo + 40, min(235, percentile(hist, sharp.size, 0.5f) - 12))
        val span = (hi - lo).toFloat()
        return IntArray(sharp.size) { i ->
            val t = ((sharp[i] - lo) / span).coerceIn(0f, 1f)
            // Curva suave hacia los extremos: texto más negro y fondo más blanco.
            val s = t * t * (3f - 2f * t)
            (s * 255f).roundToInt()
        }
    }

    /** Por debajo de esto (ya sin sombras, 0..255) un píxel cuenta como tinta. */
    private const val INK_LEVEL = 180

    private fun percentile(hist: IntArray, total: Int, p: Float): Int {
        val target = (total * p).toLong()
        var acc = 0L
        for (v in 0..255) { acc += hist[v]; if (acc > target) return v }
        return 255
    }

    /** Brillo del papel: el 90 % más claro de cada bloque, suavizado e interpolado. */
    private fun background(lum: IntArray, w: Int, h: Int): IntArray {
        val block = max(8, max(w, h) / 32)
        val bw = (w + block - 1) / block
        val bh = (h + block - 1) / block
        val grid = IntArray(bw * bh)
        val hist = IntArray(256)
        for (by in 0 until bh) for (bx in 0 until bw) {
            hist.fill(0)
            var n = 0
            for (y in by * block until min(h, (by + 1) * block)) for (x in bx * block until min(w, (bx + 1) * block)) {
                hist[lum[y * w + x]]++; n++
            }
            grid[by * bw + bx] = percentile(hist, n, 0.9f)
        }
        // Suavizado 3×3 sin bajar del máximo local menos un margen (evita oscurecer bloques de texto denso).
        val smooth = IntArray(grid.size)
        for (y in 0 until bh) for (x in 0 until bw) {
            var sum = 0; var n = 0; var mx = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until bw && yy in 0 until bh) { val v = grid[yy * bw + xx]; sum += v; n++; mx = max(mx, v) }
            }
            smooth[y * bw + x] = max(sum / n, mx - 24)
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val gy = ((y + 0.5f) / block - 0.5f).coerceIn(0f, (bh - 1).toFloat())
            val y0 = gy.toInt(); val y1 = min(y0 + 1, bh - 1); val fy = gy - y0
            for (x in 0 until w) {
                val gx = ((x + 0.5f) / block - 0.5f).coerceIn(0f, (bw - 1).toFloat())
                val x0 = gx.toInt(); val x1 = min(x0 + 1, bw - 1); val fx = gx - x0
                val top = smooth[y0 * bw + x0] + (smooth[y0 * bw + x1] - smooth[y0 * bw + x0]) * fx
                val bottom = smooth[y1 * bw + x0] + (smooth[y1 * bw + x1] - smooth[y1 * bw + x0]) * fx
                out[y * w + x] = (top + (bottom - top) * fy).roundToInt()
            }
        }
        return out
    }

    /** Máscara de enfoque con un desenfoque de caja 3×3. */
    private fun sharpen(v: IntArray, w: Int, h: Int, amount: Float): IntArray {
        val out = IntArray(v.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0; var n = 0
            for (dy in -1..1) {
                val yy = y + dy
                if (yy !in 0 until h) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx in 0 until w) { sum += v[yy * w + xx]; n++ }
                }
            }
            val c = v[y * w + x]
            out[y * w + x] = (c + amount * (c - sum.toFloat() / n)).roundToInt().coerceIn(0, 255)
        }
        return out
    }
}
