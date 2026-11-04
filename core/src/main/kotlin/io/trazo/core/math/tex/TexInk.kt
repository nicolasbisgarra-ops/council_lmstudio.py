package io.trazo.core.math.tex

import io.trazo.core.Affine
import io.trazo.core.AutoAlign
import io.trazo.core.DEFAULT_INK_WIDTH
import io.trazo.core.DefaultPatterns
import io.trazo.core.IdGenerator
import io.trazo.core.InkPattern
import io.trazo.core.InkPoint
import io.trazo.core.InkStroke
import io.trazo.core.InkStyle
import io.trazo.core.MathNotes
import io.trazo.core.PresetPatterns
import io.trazo.core.bounds
import io.trazo.core.math.layout.MathLayoutConverter
import io.trazo.core.transformed
import kotlin.math.max
import kotlin.math.min

/** Con qué letra va un texto: cursiva (las variables), derecha (cifras, signos, sen) o la de la prosa. */
enum class TexFont { ITALIC, UPRIGHT, PROSE }

/** Mide el ancho de un texto en la letra [font] de cuerpo [size]: lo pone la app, que es la que dibuja. */
fun interface TexMeasure {
    fun width(text: String, font: TexFont, size: Float): Float
}

/** Un texto que la app escribe con una letra del sistema; [size] es el cuerpo de la letra y [baseline] su línea. */
data class TexText(val text: String, val x: Float, val baseline: Float, val size: Float, val font: TexFont = TexFont.UPRIGHT)

/**
 * Una fórmula armada: trazos y textos sueltos alrededor de una línea de base en y = 0 (lo de arriba es
 * negativo). Mide [width] de ancho, sube [ascent] y baja [descent].
 */
data class TexBox(
    val strokes: List<InkStroke>,
    val texts: List<TexText>,
    val width: Float,
    val ascent: Float,
    val descent: Float,
) {
    val height: Float get() = ascent + descent

    fun moved(dx: Float, dy: Float): TexBox = if (dx == 0f && dy == 0f) this else copy(
        strokes = strokes.map { it.transformed(Affine.translate(dx, dy), scaleWidth = false) },
        texts = texts.map { it.copy(x = it.x + dx, baseline = it.baseline + dy) },
    )

    companion object {
        val EMPTY = TexBox(emptyList(), emptyList(), 0f, 0f, 0f)
    }
}

/**
 * Arma un [TexNode] con la tinta de los patrones: los del usuario cuando tiene uno con ese nombre o esa
 * letra (su «2», su «x»), y si no los de la biblioteca. Es el mismo criterio con que la hoja escribe los
 * resultados, para que el chat se vea con la letra del usuario.
 *
 * Las medidas van en múltiplos de [size], el alto de una cifra: el eje de los signos queda a media cifra.
 */
class TexInk(
    patterns: List<InkPattern>,
    private val style: InkStyle = InkStyle(),
    /** Con una medida, la fórmula sale tipográfica (letra de imprenta y trazos vectoriales), sin patrones. */
    private val typeset: TexMeasure? = null,
) {
    private val ids = IdGenerator.sequential("tex")

    private val user = patterns.filter { it.enabled && it.outputInk.isNotEmpty() && !PresetPatterns.isSign(it) }
    private val userByName = user.associateBy { it.name.trim() }
    private val userBySymbol: Map<String, InkPattern> = user.mapNotNull { p ->
        MathNotes.patternSymbol(p)?.let { MathLayoutConverter.spelling(it) ?: it }
            ?.takeIf { it.length == 1 && it[0].isLetterOrDigit() }?.let { it to p }
    }.distinctBy { it.first }.toMap()

    /** Biblioteca: si el usuario redibujó un signo se usa su versión. */
    private val library: Map<String, InkPattern> = run {
        val byId = patterns.associateBy { it.id }
        (PresetPatterns.signs() + DefaultPatterns.core()).map { byId[it.id]?.takeIf { p -> p.outputInk.isNotEmpty() } ?: it }
            .associateBy { it.name.trim() }
    }

    fun layout(node: TexNode, size: Float): TexBox = box(node, size)

    /** Lee y arma [source] de una vez. */
    fun layout(source: String, size: Float): TexBox = box(TexMath.parse(source), size)

    private fun pattern(key: String): InkPattern? = if (typeset != null) null else
        userByName[key] ?: (if (key.length == 1 && key[0].isLetterOrDigit()) userBySymbol[key] else null) ?: library[key]

    private fun box(node: TexNode, size: Float): TexBox = when (node) {
        is TexNode.Row -> row(node.items, size)
        is TexNode.Sym -> sym(node, size)
        is TexNode.Word -> word(node, size)
        is TexNode.Frac -> frac(node, size)
        is TexNode.Root -> root(node, size)
        is TexNode.Scripts -> scripts(node, size)
        is TexNode.Fenced -> fence(box(node.body, size), node.open, node.close, size)
        is TexNode.Boxed -> boxed(box(node.body, size), size)
        is TexNode.BigOp -> bigOp(node, size)
        is TexNode.Matrix -> fence(matrix(node, size), node.open, node.close, size)
        is TexNode.Accent -> accent(node, size)
        is TexNode.Space -> TexBox(emptyList(), emptyList(), node.em * size, 0f, 0f)
        is TexNode.Dot -> dot(node.center, size)
    }

    // ---- Renglón -------------------------------------------------------------------------------------

    private fun kindOf(n: TexNode): TexNode.Kind = when (n) {
        is TexNode.Sym -> n.kind
        is TexNode.Scripts -> kindOf(n.base)
        else -> TexNode.Kind.ORD
    }

    private fun row(items: List<TexNode>, size: Float): TexBox {
        val strokes = mutableListOf<InkStroke>()
        val texts = mutableListOf<TexText>()
        var x = 0f
        var ascent = 0f
        var descent = 0f
        var prev: TexNode.Kind? = null
        for (item in items) {
            var kind = kindOf(item)
            // Un «−» al principio o después de otro signo es el signo del número: va pegado.
            if (kind == TexNode.Kind.BIN && (prev == null || prev in setOf(TexNode.Kind.BIN, TexNode.Kind.REL, TexNode.Kind.OPEN, TexNode.Kind.PUNCT))) {
                kind = TexNode.Kind.ORD
            }
            if (prev != null && item !is TexNode.Space) x += gap(prev, kind, size)
            val b = box(item, size)
            val placed = b.moved(x, 0f)
            strokes += placed.strokes
            texts += placed.texts
            x += b.width
            ascent = max(ascent, b.ascent)
            descent = max(descent, b.descent)
            if (item !is TexNode.Space) prev = kind
        }
        return TexBox(strokes, texts, x, ascent, descent)
    }

    private fun gap(prev: TexNode.Kind, next: TexNode.Kind, size: Float): Float = size * when {
        prev == TexNode.Kind.REL || next == TexNode.Kind.REL -> 0.32f
        prev == TexNode.Kind.BIN || next == TexNode.Kind.BIN -> 0.24f
        prev == TexNode.Kind.PUNCT -> 0.25f
        next == TexNode.Kind.PUNCT -> 0.03f
        // La letra de imprenta ya trae su aire: dos letras seguidas van juntas.
        typeset != null -> 0.03f
        prev == TexNode.Kind.OPEN || next == TexNode.Kind.CLOSE -> 0.05f
        else -> 0.1f
    }

    // ---- Signos ----------------------------------------------------------------------------------------

    private fun sym(s: TexNode.Sym, size: Float): TexBox {
        if (typeset != null) return typesetSym(s, size)
        val p = pattern(s.key) ?: return text(s.key, size)
        val tall = s.kind == TexNode.Kind.OPEN || s.kind == TexNode.Kind.CLOSE || s.key in DELIMITERS
        return if (tall) stretched(p, size * 0.6f, size) ?: text(s.key, size) else glyph(p, s.kind, size) ?: text(s.key, size)
    }

    /** ¿La salida está en el marco de la biblioteca (alto 100, línea en y = 100)? */
    private fun framed(p: InkPattern): Boolean {
        if (!p.id.startsWith(PresetPatterns.PREFIX)) return false
        val src = p.outputInk.bounds()
        return src.height > 0.5f && src.top >= -10f && src.bottom in 95f..150f
    }

    private fun isSign(p: InkPattern, kind: TexNode.Kind) =
        kind == TexNode.Kind.BIN || kind == TexNode.Kind.REL || AutoAlign.isSignName(p.name)

    private fun glyph(p: InkPattern, kind: TexNode.Kind, size: Float): TexBox? {
        if (p.outputInk.isEmpty()) return null
        val src = p.outputInk.bounds()
        if (framed(p)) {
            val k = size / 100f
            return place(p, Affine.translate(-src.left, -PresetPatterns.BASELINE).then(Affine(a = k, d = k)), size)
        }
        if (isSign(p, kind)) {
            // Signo propio: del alto de media cifra (o de media cifra de ancho si es plano), centrado en el eje.
            val k = when {
                src.height > src.width * 0.4f && src.height > 0.5f -> size * 0.55f / src.height
                src.width > 0.5f -> size * 0.5f / src.width
                else -> 1f
            }
            return place(p, Affine.translate(-src.left, -src.centerY).then(Affine(a = k, d = k)).then(Affine.translate(0f, -size / 2f)), size)
        }
        // Letra o cifra propia: apoyada en la línea, las letras un poco más bajas que las cifras.
        val floor = AutoAlign.floor(p)
        val letter = MathNotes.patternSymbol(p)?.let { MathLayoutConverter.spelling(it) ?: it }?.any { it.isLetter() } == true
        val h = (if (letter) size * 0.8f else size) / floor
        if (src.height <= 0.5f) return null
        val k = h / src.height
        return place(p, Affine.translate(-src.left, -src.top).then(Affine(a = k, d = k)).then(Affine.translate(0f, -h * floor)), size)
    }

    /** Un paréntesis (o barra, llave) que cubre de [up] sobre el eje hasta [down] debajo. */
    private fun stretched(p: InkPattern, half: Float, size: Float): TexBox? {
        if (p.outputInk.isEmpty()) return null
        val src = p.outputInk.bounds()
        if (src.height <= 0.5f) return null
        val total = max(half, size * 0.6f) * 2f
        val ky = total / src.height
        // Los grandes no engordan: el ancho crece mucho menos que el alto.
        val natural = size * 1.15f / (if (framed(p)) 100f else src.height)
        val kx = if (ky <= natural) ky else natural * (1f + (ky / natural - 1f) * 0.25f)
        val t = Affine.translate(-src.left, -src.centerY).then(Affine(a = kx, d = ky)).then(Affine.translate(0f, -size / 2f))
        return place(p, t, size)
    }

    private fun place(p: InkPattern, t: Affine, size: Float): TexBox {
        val w = stroke(size) / DEFAULT_INK_WIDTH
        val strokes = p.outputInk.map { s ->
            s.transformed(t, scaleWidth = false).copy(
                id = ids.next(), groupId = null, patternId = p.id, color = style.color,
                width = (s.width * w).coerceIn(0.3f, 40f), brush = style.brush,
            )
        }
        val b = strokes.bounds()
        return TexBox(strokes, emptyList(), max(b.right, 0f), max(-b.top, 0f), max(b.bottom, 0f))
    }

    private fun text(s: String, size: Float, font: TexFont = TexFont.UPRIGHT): TexBox {
        if (s.isEmpty()) return TexBox.EMPTY
        val em = size / DIGIT
        val m = typeset
        if (m == null) {
            val per = if (font == TexFont.PROSE) 0.5f else 0.6f
            return TexBox(emptyList(), listOf(TexText(s, 0f, 0f, em, font)), s.length * per * size, size * 0.8f, size * 0.22f)
        }
        // La cursiva se inclina hacia la derecha: un poco de aire para que no pise lo que sigue.
        val w = m.width(s, font, em) + if (font == TexFont.ITALIC) em * 0.04f else 0f
        return TexBox(emptyList(), listOf(TexText(s, 0f, 0f, em, font)), w, size * 1.02f, size * 0.28f)
    }

    private fun stroke(size: Float) = size * (if (typeset != null) TYPESET_STROKE else STROKE)

    private fun line(vararg pts: Pair<Float, Float>, size: Float): InkStroke = poly(pts.toList(), size)

    private fun poly(pts: List<Pair<Float, Float>>, size: Float): InkStroke = InkStroke(
        ids.next(), pts.map { InkPoint(it.first, it.second) }, style.color, stroke(size), brush = style.brush,
    )

    // ---- Letra de imprenta -------------------------------------------------------------------------------

    private fun typesetSym(s: TexNode.Sym, size: Float): TexBox {
        val c = when {
            s.kind == TexNode.Kind.OPEN && s.key == "Menor" -> "⟨"
            s.kind == TexNode.Kind.CLOSE && s.key == "Mayor" -> "⟩"
            else -> TYPESET_CHARS[s.key] ?: s.key
        }
        val font = if (c.length == 1 && (c[0] in 'a'..'z' || c[0] in 'A'..'Z' || c[0] in 'α'..'ω')) TexFont.ITALIC else TexFont.UPRIGHT
        return text(c, size, font)
    }

    /** Un delimitador dibujado que va de [half] sobre el eje hasta [half] debajo. */
    private fun vectorFence(key: String, half: Float, size: Float): TexBox {
        val axis = -size / 2f
        val top = axis - half
        val bottom = axis + half
        val h = bottom - top
        val pts: List<List<Pair<Float, Float>>>
        val width: Float
        when (key) {
            "Abre paréntesis", "Cierra paréntesis" -> {
                val w = size * 0.22f + h * 0.04f
                val curve = (0..24).map { k ->
                    val t = k / 24f
                    w * (1f - 0.88f * kotlin.math.sin(Math.PI.toFloat() * t)) to top + h * t
                }
                pts = listOf(if (key.startsWith("Abre")) curve else curve.map { w - it.first to it.second })
                width = w
            }
            "Abre corchete", "Cierra corchete" -> {
                val w = size * 0.26f
                val l = listOf(w to top, 0f to top, 0f to bottom, w to bottom)
                pts = listOf(if (key.startsWith("Abre")) l else l.map { w - it.first to it.second })
                width = w
            }
            "Abre llave", "Cierra llave" -> {
                val w = size * 0.36f
                val mid = (top + bottom) / 2f
                val d = min(size * 0.18f, h * 0.1f)
                val l = smooth(listOf(
                    w to top, w * 0.55f to top + d, w * 0.5f to mid - d * 1.5f, w * 0.42f to mid - d * 0.4f, 0f to mid,
                    w * 0.42f to mid + d * 0.4f, w * 0.5f to mid + d * 1.5f, w * 0.55f to bottom - d, w to bottom,
                ))
                pts = listOf(if (key.startsWith("Abre")) l else l.map { w - it.first to it.second })
                width = w
            }
            "Menor", "Mayor" -> {
                val w = size * 0.3f + h * 0.03f
                val l = listOf(w to top, 0f to (top + bottom) / 2f, w to bottom)
                pts = listOf(if (key == "Menor") l else l.map { w - it.first to it.second })
                width = w
            }
            "Paralelo" -> {
                width = size * 0.34f
                pts = listOf(listOf(size * 0.08f to top, size * 0.08f to bottom), listOf(size * 0.26f to top, size * 0.26f to bottom))
            }
            else -> {
                width = size * 0.2f
                pts = listOf(listOf(size * 0.1f to top, size * 0.1f to bottom))
            }
        }
        val pad = stroke(size) / 2f
        return TexBox(pts.map { poly(it, size) }, emptyList(), width + pad * 2f, -top + pad, max(bottom + pad, 0f)).let {
            it.moved(pad, 0f).copy(width = width + pad * 2f)
        }
    }

    /** Integral, sumatoria y productoria dibujadas, de [half] sobre el eje a [half] debajo. */
    private fun vectorOp(key: String, half: Float, size: Float): TexBox {
        val axis = -size / 2f
        val top = axis - half
        val h = half * 2f
        fun integral(dx: Float): List<Pair<Float, Float>> {
            val w = size * 0.55f
            return smooth(listOf(
                0.98f to 0.07f, 0.88f to 0.0f, 0.74f to 0.01f, 0.64f to 0.1f, 0.58f to 0.26f, 0.5f to 0.5f,
                0.42f to 0.74f, 0.36f to 0.9f, 0.26f to 0.99f, 0.12f to 1.0f, 0.02f to 0.93f,
            ).map { (u, v) -> dx + u * w to top + v * h })
        }
        val strokes: List<List<Pair<Float, Float>>>
        val width: Float
        when (key) {
            "Integral doble" -> { strokes = listOf(integral(0f), integral(size * 0.32f)); width = size * 0.9f }
            "Integral de línea" -> {
                val r = size * 0.2f
                val cx = size * 0.28f
                val ring = (0..20).map { k -> val a = 2 * Math.PI.toFloat() * k / 20f; cx + r * kotlin.math.cos(a) to axis + r * kotlin.math.sin(a) }
                strokes = listOf(integral(0f), ring); width = size * 0.6f
            }
            "Sumatoria" -> {
                val w = size * 1.1f
                val b = top + h
                strokes = listOf(listOf(w to top + h * 0.12f, w * 0.96f to top, 0f to top, w * 0.55f to top + h / 2f, 0f to b, w * 0.96f to b, w to b - h * 0.12f))
                width = w
            }
            "Productoria" -> {
                val w = size * 1.1f
                val b = top + h
                strokes = listOf(listOf(0f to top, w to top), listOf(w * 0.18f to top, w * 0.18f to b), listOf(w * 0.82f to top, w * 0.82f to b))
                width = w
            }
            else -> { strokes = listOf(integral(0f)); width = size * 0.6f }
        }
        val pad = stroke(size)
        return TexBox(strokes.map { poly(it, size) }, emptyList(), width + pad, -top + pad, max(top + h + pad, 0f))
    }

    /** Pasa una curva por los puntos (Catmull-Rom): para que la llave y la integral no queden quebradas. */
    private fun smooth(p: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (p.size < 3) return p
        val out = mutableListOf(p.first())
        for (k in 0 until p.size - 1) {
            val p0 = p[max(k - 1, 0)]; val p1 = p[k]; val p2 = p[k + 1]; val p3 = p[min(k + 2, p.size - 1)]
            for (j in 1..6) {
                val t = j / 6f
                val t2 = t * t; val t3 = t2 * t
                fun f(a: Float, b: Float, c: Float, d: Float) = 0.5f * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
                out += f(p0.first, p1.first, p2.first, p3.first) to f(p0.second, p1.second, p2.second, p3.second)
            }
        }
        return out
    }

    private fun boxed(body: TexBox, size: Float): TexBox {
        val pad = size * 0.28f
        val top = -max(body.ascent, size) - pad
        val bottom = max(body.descent, size * 0.2f) + pad
        val right = body.width + pad * 2f
        val frame = line(0f to top, right to top, right to bottom, 0f to bottom, 0f to top, size = size)
        val inner = body.moved(pad, 0f)
        val m = stroke(size)
        return TexBox(inner.strokes + frame, inner.texts, right + m, -top + m, bottom + m).moved(m / 2f, 0f).copy(width = right + m * 2f)
    }

    // ---- Palabras -------------------------------------------------------------------------------------

    /** «sin», «lím»: con las letras de los patrones, juntas; la prosa (\text) va con la letra del sistema. */
    private fun word(w: TexNode.Word, size: Float): TexBox {
        if (w.prose) return text(w.text, size, TexFont.PROSE)
        if (typeset != null) return text(w.text, size, TexFont.UPRIGHT)
        if (w.text.any { !it.isLetter() }) return text(w.text, size, TexFont.PROSE)
        val letters = w.text.map { c ->
            val key = when (c) { 'í' -> "i"; 'á' -> "a"; 'é' -> "e"; 'ó' -> "o"; 'ú' -> "u"; else -> c.toString() }
            pattern(key)?.let { glyph(it, TexNode.Kind.ORD, size) } ?: text(c.toString(), size)
        }
        val strokes = mutableListOf<InkStroke>()
        val texts = mutableListOf<TexText>()
        var x = 0f
        for ((i, b) in letters.withIndex()) {
            if (i > 0) x += size * 0.04f
            b.moved(x, 0f).let { strokes += it.strokes; texts += it.texts }
            x += b.width
        }
        // La tilde de «lím» se agrega a mano sobre la i.
        val accent = w.text.indexOf('í')
        if (accent >= 0 && letters[accent].texts.isEmpty()) {
            val left = letters.take(accent).sumOf { it.width.toDouble() }.toFloat() + accent * size * 0.04f
            val b = letters[accent]
            val cx = left + b.width / 2f
            strokes += line(cx - size * 0.02f, -b.ascent - size * 0.08f, cx + size * 0.1f, -b.ascent - size * 0.24f, size = size)
        }
        return TexBox(strokes, texts, x, letters.maxOf { it.ascent }, letters.maxOf { it.descent })
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, size: Float) = line(x1 to y1, x2 to y2, size = size)

    // ---- Estructuras ----------------------------------------------------------------------------------

    private fun frac(f: TexNode.Frac, size: Float): TexBox {
        val small = max(size * 0.8f, MIN_SIZE)
        val num = box(f.num, small)
        val den = box(f.den, small)
        val gap = size * 0.15f
        val axis = -size / 2f
        val width = max(num.width, den.width) + size * 0.25f
        val numBase = axis - gap - num.descent
        val denBase = axis + gap + den.ascent
        val n = num.moved((width - num.width) / 2f, numBase)
        val d = den.moved((width - den.width) / 2f, denBase)
        val bar = line(0f, axis, width, axis, size = size)
        return TexBox(n.strokes + d.strokes + bar, n.texts + d.texts, width, -(numBase - num.ascent), denBase + den.descent)
    }

    private fun root(r: TexNode.Root, size: Float): TexBox {
        val body = box(r.body, size)
        val index = r.index?.let { box(it, max(size * 0.55f, MIN_SIZE)) }
        val shift = index?.let { max(0f, it.width - size * 0.22f) } ?: 0f
        val mark = size * 0.5f
        val ascent = max(body.ascent, size * 0.8f) + size * 0.15f
        val descent = max(body.descent, size * 0.05f)
        val x = shift
        val axis = -size / 2f
        val stroke = line(
            x to axis + size * 0.05f, x + size * 0.12f to axis - size * 0.02f, x + size * 0.26f to descent,
            x + size * 0.42f to -ascent, x + mark + body.width + size * 0.08f to -ascent, size = size,
        )
        val inner = body.moved(x + mark, 0f)
        val idx = index?.moved(0f, axis - size * 0.08f - index.descent)
        val width = x + mark + body.width + size * 0.12f
        return TexBox(
            inner.strokes + stroke + (idx?.strokes ?: emptyList()),
            inner.texts + (idx?.texts ?: emptyList()),
            width, max(ascent, idx?.let { -(axis - size * 0.08f - it.descent) + it.ascent } ?: 0f) + size * 0.04f, descent + size * 0.04f,
        )
    }

    private fun scripts(sc: TexNode.Scripts, size: Float): TexBox {
        val op = sc.base as? TexNode.BigOp
        if (op != null && op.below) return limits(op, sc.sup, sc.sub, size)
        val base = box(sc.base, size)
        val small = max(size * 0.65f, MIN_SIZE)
        val sup = sc.sup?.let { box(it, small) }
        val sub = sc.sub?.let { box(it, small) }
        val integral = op != null
        val x = base.width + size * (if (integral) -0.05f else 0.04f)
        val supBase = sup?.let { -max(size * 0.45f, base.ascent - it.ascent * (if (integral) 0.8f else 0.5f)) }
        val subBase = sub?.let { max(size * 0.22f, base.descent - (if (integral) it.descent else 0f)) }
        val parts = listOfNotNull(base, sup?.moved(x + (if (integral) size * 0.12f else 0f), supBase!!), sub?.moved(x, subBase!!))
        val width = max(x + (sup?.width ?: 0f) + (if (integral) size * 0.12f else 0f), x + (sub?.width ?: 0f))
        return TexBox(
            parts.flatMap { it.strokes }, parts.flatMap { it.texts }, max(width, base.width),
            max(base.ascent, sup?.let { -supBase!! + it.ascent } ?: 0f),
            max(base.descent, sub?.let { subBase!! + it.descent } ?: 0f),
        )
    }

    private fun bigOp(op: TexNode.BigOp, size: Float): TexBox {
        if (op.word != null) return word(TexNode.Word(op.word), size)
        if (typeset != null) return vectorOp(op.key, if (op.below) size * 0.8f else size * 1.1f, size)
        val p = pattern(op.key) ?: return text(op.key, size)
        val half = if (op.below) size * 0.75f else size * 1.0f
        return stretched(p, half, size) ?: text(op.key, size)
    }

    /** Sumatoria o límite: lo de abajo y lo de arriba, centrados. */
    private fun limits(op: TexNode.BigOp, sup: TexNode?, sub: TexNode?, size: Float): TexBox {
        val base = bigOp(op, size)
        val small = max(size * 0.65f, MIN_SIZE)
        val top = sup?.let { box(it, small) }
        val bottom = sub?.let { box(it, small) }
        val width = maxOf(base.width, top?.width ?: 0f, bottom?.width ?: 0f)
        val gap = size * 0.12f
        val b = base.moved((width - base.width) / 2f, 0f)
        val topBase = top?.let { -base.ascent - gap - it.descent }
        val bottomBase = bottom?.let { base.descent + gap + it.ascent }
        val t = top?.moved((width - top.width) / 2f, topBase!!)
        val u = bottom?.moved((width - bottom.width) / 2f, bottomBase!!)
        val parts = listOfNotNull(b, t, u)
        return TexBox(
            parts.flatMap { it.strokes }, parts.flatMap { it.texts }, width,
            top?.let { -topBase!! + it.ascent } ?: base.ascent,
            bottom?.let { bottomBase!! + it.descent } ?: base.descent,
        )
    }

    private fun matrix(m: TexNode.Matrix, size: Float): TexBox {
        if (m.rows.isEmpty()) return TexBox.EMPTY
        val cells = m.rows.map { r -> r.map { box(it, size) } }
        val columns = cells.maxOf { it.size }
        val widths = (0 until columns).map { j -> cells.maxOf { it.getOrNull(j)?.width ?: 0f } }
        val pairs = m.align == TexNode.Align.PAIRS
        // En un aligned la columna de la izquierda va contra el «=» y la de la derecha sale de él.
        fun colGap(j: Int) = size * when {
            pairs -> if (j % 2 == 0) 0.32f else 1.5f
            m.align == TexNode.Align.LEFT -> 0.5f
            else -> 0.8f
        }
        val rowGap = size * (if (pairs) 0.6f else 0.35f)
        val rowAscent = cells.map { r -> max(r.maxOfOrNull { it.ascent } ?: 0f, size * 0.8f) }
        val rowDescent = cells.map { r -> max(r.maxOfOrNull { it.descent } ?: 0f, size * 0.2f) }
        val total = rowAscent.indices.sumOf { (rowAscent[it] + rowDescent[it]).toDouble() }.toFloat() + rowGap * (cells.size - 1)
        // Todo el bloque centrado en el eje.
        var y = -size / 2f - total / 2f
        val strokes = mutableListOf<InkStroke>()
        val texts = mutableListOf<TexText>()
        for ((i, r) in cells.withIndex()) {
            val baseline = y + rowAscent[i]
            var x = 0f
            for (j in 0 until columns) {
                r.getOrNull(j)?.let { c ->
                    val dx = when {
                        pairs && j % 2 == 0 -> x + widths[j] - c.width
                        pairs || m.align == TexNode.Align.LEFT -> x
                        else -> x + (widths[j] - c.width) / 2f
                    }
                    c.moved(dx, baseline).let { strokes += it.strokes; texts += it.texts }
                }
                x += widths[j] + if (j < columns - 1) colGap(j) else 0f
            }
            y = baseline + rowDescent[i] + rowGap
        }
        val width = widths.sum() + (0 until columns - 1).sumOf { colGap(it).toDouble() }.toFloat()
        val top = -size / 2f - total / 2f
        return TexBox(strokes, texts, width, -top, top + total)
    }

    /** Envuelve [body] con delimitadores que lo cubren de punta a punta. */
    private fun fence(body: TexBox, open: String?, close: String?, size: Float): TexBox {
        if (open == null && close == null) return body
        val axis = size / 2f
        val half = max(body.ascent - axis, body.descent + axis) + size * 0.08f
        val l = open?.let { delimiterBox(it, true, body, half, size) }
        val r = close?.let { delimiterBox(it, false, body, half, size) }
        val pad = size * 0.06f
        val parts = mutableListOf<TexBox>()
        var x = 0f
        if (l != null) { parts += l; x += l.width + pad }
        parts += body.moved(x, 0f)
        x += body.width
        if (r != null) { x += pad; parts += r.moved(x, 0f); x += r.width }
        return TexBox(parts.flatMap { it.strokes }, parts.flatMap { it.texts }, x, parts.maxOf { it.ascent }, parts.maxOf { it.descent })
    }

    private fun delimiterBox(key: String, open: Boolean, body: TexBox, half: Float, size: Float): TexBox {
        if (typeset == null) return pattern(key)?.let { p -> stretched(p, half, size) } ?: text(key, size)
        // Lo que entra en un renglón va con la letra; lo más alto se dibuja.
        if (body.ascent <= size * 1.15f && body.descent <= size * 0.4f) {
            return typesetSym(TexNode.Sym(key, if (open) TexNode.Kind.OPEN else TexNode.Kind.CLOSE), size)
        }
        return vectorFence(key, half, size)
    }

    private fun accent(a: TexNode.Accent, size: Float): TexBox {
        val body = box(a.body, size)
        val y = -max(body.ascent, size * 0.5f) - size * 0.12f
        val w = max(body.width, size * 0.4f)
        val mark = when (a.kind) {
            TexNode.AccentKind.BAR -> listOf(line(0f, y, w, y, size = size))
            TexNode.AccentKind.VEC -> listOf(
                line(0f, y, w, y, size = size),
                line(w - size * 0.15f to y - size * 0.1f, w to y, w - size * 0.15f to y + size * 0.1f, size = size),
            )
            TexNode.AccentKind.HAT -> listOf(line(w * 0.2f to y + size * 0.02f, w * 0.5f to y - size * 0.14f, w * 0.8f to y + size * 0.02f, size = size))
        }
        return TexBox(body.strokes + mark, body.texts, w, -y + size * 0.16f, body.descent)
    }

    private fun dot(center: Boolean, size: Float): TexBox {
        val y = if (center) -size / 2f else -size * 0.04f
        val cx = size * 0.12f
        val mark = line(cx, y, cx + size * 0.01f, y, size = size).copy(width = stroke(size) * 1.6f)
        return TexBox(listOf(mark), emptyList(), size * 0.24f, -y + size * 0.05f, 0f)
    }

    private companion object {
        /** Grosor de la tinta en fracción de la cifra. */
        const val STROKE = 0.07f
        const val TYPESET_STROKE = 0.055f
        const val MIN_SIZE = 6f
        /** Alto de una cifra en cuerpos de letra. */
        const val DIGIT = 0.7f

        /** Los nombres de los patrones en letra de imprenta. */
        val TYPESET_CHARS = mapOf(
            "Más" to "+", "Menos" to "−", "Por" to "×", "Dividido" to "÷", "Más o menos" to "±", "Barra" to "/",
            "Igual" to "=", "Menor" to "<", "Mayor" to ">", "Menor o igual" to "≤", "Mayor o igual" to "≥",
            "Distinto" to "≠", "Aproximado" to "≈", "Equivalente" to "≡", "Flecha a la derecha" to "→",
            "Flecha a la izquierda" to "←", "Flecha doble" to "↔", "Implica" to "⇒", "Si y solo si" to "⇔",
            "Flecha hacia arriba" to "↑", "Flecha hacia abajo" to "↓", "Asigna" to "↦",
            "Abre paréntesis" to "(", "Cierra paréntesis" to ")", "Abre corchete" to "[", "Cierra corchete" to "]",
            "Abre llave" to "{", "Cierra llave" to "}", "Valor absoluto" to "|", "Paralelo" to "‖",
            "Coma" to ",", "Factorial" to "!", "Porcentaje" to "%", "Infinito" to "∞", "Grado" to "°",
            "Unión" to "∪", "Intersección" to "∩", "Y lógico" to "∧", "O lógico" to "∨",
            "Pertenece" to "∈", "No pertenece" to "∉", "Subconjunto" to "⊂", "Superconjunto" to "⊃",
            "Subconjunto o igual" to "⊆", "Superconjunto o igual" to "⊇", "Perpendicular" to "⊥",
            "Derivada parcial" to "∂", "Nabla" to "∇", "Vacío" to "∅", "Para todo" to "∀", "Existe" to "∃",
            "No" to "¬", "Ángulo" to "∠", "Reales" to "ℝ", "Naturales" to "ℕ", "Enteros" to "ℤ",
            "Racionales" to "ℚ", "Complejos" to "ℂ",
            "alfa" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "Delta" to "Δ", "épsilon" to "ε",
            "eta" to "η", "theta" to "θ", "lambda" to "λ", "mu" to "μ", "pi" to "π", "rho" to "ρ",
            "sigma" to "σ", "tau" to "τ", "fi" to "φ", "psi" to "ψ", "omega" to "ω", "Omega" to "Ω",
        )
        val DELIMITERS = setOf("Valor absoluto", "Paralelo")
    }
}
