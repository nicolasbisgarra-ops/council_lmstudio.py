package io.trazo.core

import kotlinx.serialization.Serializable

/**
 * Una página del cuaderno abierto: decodificada (la que se está editando) o fija como el JSON tal cual
 * está en el archivo, que ocupa poco y no se toca hasta que se abre. Guardar una página fija copia ese
 * texto sin volver a decodificarlo.
 */
class PageSlot private constructor(val id: String, private val json: String?, private val decoded: Page?) {
    val isLoaded: Boolean get() = decoded != null

    /** La página; si está fija se decodifica en el momento (sin quedar cargada). */
    fun page(): Page = decoded ?: TrazoFormat.decodePage(json!!)

    /** La misma página, cargada. */
    fun loaded(): PageSlot = if (decoded != null) this else PageSlot(id, null, page())

    /** La misma página, fija como JSON: libera los trazos decodificados. */
    fun frozen(): PageSlot = if (json != null) this else PageSlot(id, TrazoFormat.encodePage(decoded!!), null)

    fun json(): String = json ?: TrazoFormat.encodePage(decoded!!)

    companion object {
        fun of(page: Page) = PageSlot(page.id, null, page)
        fun frozen(id: String, json: String) = PageSlot(id, json, null)
    }
}

/**
 * Cuaderno abierto en el editor: solo la página actual se decodifica y se dibuja; el resto queda fijo
 * ([PageSlot]) y se carga al pasar a ella. Mismo formato de archivo que [Notebook].
 */
data class LazyNotebook(
    val id: String,
    val name: String,
    val slots: List<PageSlot>,
    val paper: Paper = Paper.LINES,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Última página abierta: el cuaderno vuelve a abrirse ahí. */
    val lastPage: Int = 0,
) {
    init { require(slots.isNotEmpty()) { "A notebook needs at least one page" } }

    val pageCount: Int get() = slots.size

    /** Página donde se reabre (la última vista, si sigue existiendo). */
    val openPage: Int get() = lastPage.coerceIn(0, slots.size - 1)

    fun page(index: Int): Page = slots[index].page()

    /** Reemplaza la página [index] por [page], cargada. */
    fun withPage(index: Int, page: Page): LazyNotebook = copy(slots = slots.toMutableList().also { it[index] = PageSlot.of(page) })

    /** Deja cargada solo [index]; las demás quedan fijas. */
    fun loadedOnly(index: Int): LazyNotebook =
        copy(slots = slots.mapIndexed { i, s -> if (i == index) s.loaded() else s.frozen() })

    fun withPageAdded(page: Page, at: Int = slots.size): LazyNotebook =
        copy(slots = slots.toMutableList().apply { add(at.coerceIn(0, slots.size), PageSlot.of(page)) })

    fun withPageRemoved(index: Int): LazyNotebook {
        require(slots.size > 1 && index in slots.indices) { "Cannot remove page $index" }
        return copy(slots = slots.filterIndexed { i, _ -> i != index })
    }

    /** Copia profunda de la página justo después (ids nuevos), como [Notebook.withPageDuplicated]. */
    fun withPageDuplicated(index: Int, ids: IdGenerator = IdGenerator.RANDOM): LazyNotebook {
        require(index in slots.indices) { "No page $index" }
        val src = slots[index].page()
        val dup = Page(ids.next(), src.strokes.copiedWithNewIds(ids), src.parametricShapes.map { it.copy(id = ids.next()) })
        return withPageAdded(dup, index + 1)
    }

    fun withPageMoved(from: Int, to: Int): LazyNotebook {
        require(from in slots.indices && to in slots.indices) { "Cannot move page $from to $to" }
        if (from == to) return this
        return copy(slots = slots.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** Todo decodificado (exportar, archivo `.trazo`). */
    fun toNotebook(): Notebook = Notebook(id, name, slots.map { it.page() }, paper, createdAt, updatedAt, lastPage)

    companion object {
        fun of(n: Notebook): LazyNotebook =
            LazyNotebook(n.id, n.name, n.pages.map { PageSlot.of(it) }, n.paper, n.createdAt, n.updatedAt, n.lastPage)
    }
}

/** Los datos del cuaderno sin las páginas. */
@Serializable
internal data class NotebookMeta(
    val id: String,
    val name: String,
    val paper: Paper = Paper.LINES,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastPage: Int = 0,
)

/**
 * Recorre un JSON sin decodificarlo: ubica dónde empieza y termina cada valor para recortar las páginas
 * como texto. Solo cuenta llaves, corchetes y comillas (con sus escapes).
 */
internal class JsonSpans(private val s: String) {
    var i = 0

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun expect(c: Char) {
        ws()
        if (i >= s.length || s[i] != c) throw IllegalArgumentException("Expected '$c' at $i")
        i++
    }

    private fun skipString() {
        i++ // comilla de apertura
        while (s[i] != '"') { if (s[i] == '\\') i++; i++ }
        i++
    }

    /** Saltea un valor y devuelve dónde empezaba. */
    fun skipValue(): Int {
        ws()
        val start = i
        when (s[i]) {
            '"' -> skipString()
            '{', '[' -> {
                var depth = 0
                do {
                    when (s[i]) {
                        '"' -> { skipString(); continue }
                        '{', '[' -> depth++
                        '}', ']' -> depth--
                    }
                    i++
                } while (depth > 0)
            }
            else -> while (i < s.length && s[i] != ',' && s[i] != '}' && s[i] != ']' && !s[i].isWhitespace()) i++
        }
        return start
    }

    /** Miembros del objeto que empieza en [at]: clave cruda (con comillas) → (inicio, fin) del valor. */
    fun members(at: Int): Map<String, IntRange> {
        i = at
        expect('{')
        val out = LinkedHashMap<String, IntRange>()
        ws()
        if (s[i] == '}') { i++; return out }
        while (true) {
            ws()
            val keyStart = i
            skipString()
            val key = s.substring(keyStart, i)
            expect(':')
            val start = skipValue()
            out[key] = start until i
            ws()
            if (s[i] == ',') { i++; continue }
            expect('}')
            return out
        }
    }

    /** Elementos del arreglo que empieza en [at]. */
    fun elements(at: Int): List<IntRange> {
        i = at
        expect('[')
        val out = ArrayList<IntRange>()
        ws()
        if (s[i] == ']') { i++; return out }
        while (true) {
            val start = skipValue()
            out += start until i
            ws()
            if (s[i] == ',') { i++; continue }
            expect(']')
            return out
        }
    }
}
