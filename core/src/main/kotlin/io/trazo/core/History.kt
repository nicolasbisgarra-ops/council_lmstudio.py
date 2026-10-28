package io.trazo.core

/**
 * Cambio atómico sobre una página: quita [removed] y agrega [added].
 * Un trazo con el mismo id en ambas listas se reemplaza en su lugar (mover, escalar…).
 * El reemplazo gesto → tinta de salida es UN solo [StrokeEdit], así un deshacer lo revierte entero.
 */
data class StrokeEdit(
    val removed: List<InkStroke>,
    val added: List<InkStroke>,
    val kind: Kind,
    /** Formas paramétricas quitadas/agregadas; mismo id en ambas = edición en su lugar. */
    val removedShapes: List<ParametricShape> = emptyList(),
    val addedShapes: List<ParametricShape> = emptyList(),
) {
    enum class Kind { DRAW, CONVERT, ERASE, TRANSFORM, DELETE, INSERT, CLEAR }

    val isEmpty: Boolean
        get() = removed.isEmpty() && added.isEmpty() && removedShapes.isEmpty() && addedShapes.isEmpty()
}

/** Estado mutable de la página que edita la sesión. Conserva el orden (z) de los trazos y de las formas. */
class PageState(initial: List<InkStroke> = emptyList(), initialShapes: List<ParametricShape> = emptyList()) {
    private val list = ArrayList(initial)
    private val shapeList = ArrayList(initialShapes)

    /** Se incrementa con cada cambio; la vista lo usa para invalidar su caché. */
    var version: Long = 0L
        private set

    val strokes: List<InkStroke> get() = list
    val shapes: List<ParametricShape> get() = shapeList

    private var inkCache: List<InkStroke>? = null
    private var inkCacheVersion = -1L

    /** Trazos más la tinta derivada de las formas, para dibujar y exportar. */
    val allInk: List<InkStroke>
        get() {
            if (shapeList.isEmpty()) return list
            if (inkCacheVersion != version || inkCache == null) {
                inkCache = list + shapeList.flatMap { it.render() }
                inkCacheVersion = version
            }
            return inkCache!!
        }

    fun find(id: String): InkStroke? = list.firstOrNull { it.id == id }
    fun contains(id: String): Boolean = list.any { it.id == id }
    fun findShape(id: String): ParametricShape? = shapeList.firstOrNull { it.id == id }

    /** Aplica el cambio y devuelve la posición previa de cada elemento quitado (para revertir exacto). */
    fun apply(edit: StrokeEdit): Map<String, Int> {
        val positions = HashMap<String, Int>()
        applyTo(list, edit.removed, edit.added, { it.id }, "", positions)
        applyTo(shapeList, edit.removedShapes, edit.addedShapes, { it.id }, SHAPE_KEY, positions)
        version++
        return positions
    }

    /** Deshace [edit] usando las posiciones devueltas por [apply]. */
    fun revert(edit: StrokeEdit, positions: Map<String, Int>) {
        revertIn(list, edit.removed, edit.added, { it.id }, "", positions)
        revertIn(shapeList, edit.removedShapes, edit.addedShapes, { it.id }, SHAPE_KEY, positions)
        version++
    }

    fun replaceAll(strokes: List<InkStroke>, shapes: List<ParametricShape> = shapeList.toList()) {
        list.clear(); list.addAll(strokes)
        shapeList.clear(); shapeList.addAll(shapes)
        version++
    }

    companion object {
        /** Prefijo de las posiciones de formas en el mapa que devuelve [apply]. */
        const val SHAPE_KEY = "shape:"

        private fun <T> applyTo(
            target: ArrayList<T>, removed: List<T>, added: List<T>, idOf: (T) -> String,
            prefix: String, positions: HashMap<String, Int>,
        ) {
            if (removed.isEmpty() && added.isEmpty()) return
            val removeIds = removed.mapTo(HashSet()) { idOf(it) }
            val addedById = added.associateBy { idOf(it) }
            val placed = HashSet<String>()
            val out = ArrayList<T>(target.size + added.size)
            target.forEachIndexed { i, s ->
                val id = idOf(s)
                if (id in removeIds) {
                    positions[prefix + id] = i
                    addedById[id]?.let { out += it; placed += id }
                } else if (id in addedById) {
                    // Id duplicado: el agregado reemplaza al existente.
                    out += addedById.getValue(id); placed += id
                } else {
                    out += s
                }
            }
            added.forEach { if (idOf(it) !in placed) out += it }
            target.clear(); target.addAll(out)
        }

        private fun <T> revertIn(
            target: ArrayList<T>, removed: List<T>, added: List<T>, idOf: (T) -> String,
            prefix: String, positions: Map<String, Int>,
        ) {
            if (removed.isEmpty() && added.isEmpty()) return
            val addedIds = added.mapTo(HashSet()) { idOf(it) }
            val removedById = removed.associateBy { idOf(it) }
            val restoredInPlace = HashSet<String>()
            val out = ArrayList<T>(target.size + removed.size)
            for (s in target) {
                val id = idOf(s)
                if (id in addedIds) {
                    removedById[id]?.let { out += it; restoredInPlace += id }
                } else {
                    out += s
                }
            }
            val pending = removed.filter { idOf(it) !in restoredInPlace }
            val (known, unknown) = pending.partition { prefix + idOf(it) in positions }
            known.sortedBy { positions.getValue(prefix + idOf(it)) }.forEach { s ->
                out.add(positions.getValue(prefix + idOf(s)).coerceIn(0, out.size), s)
            }
            out.addAll(unknown)
            target.clear(); target.addAll(out)
        }
    }
}

/** Deshacer/rehacer por página. */
class History(private val limit: Int = 300) {
    private class Entry(val edit: StrokeEdit, var positions: Map<String, Int>)

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val lastEdit: StrokeEdit? get() = undoStack.lastOrNull()?.edit

    /** Aplica y registra. */
    fun perform(page: PageState, edit: StrokeEdit) {
        if (edit.isEmpty) return
        record(edit, page.apply(edit))
    }

    /** Registra un cambio que ya se aplicó en vivo (borrador, arrastre del lazo). */
    fun record(edit: StrokeEdit, positions: Map<String, Int>) {
        if (edit.isEmpty) return
        undoStack.addLast(Entry(edit, positions))
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(page: PageState): StrokeEdit? {
        val e = undoStack.removeLastOrNull() ?: return null
        page.revert(e.edit, e.positions)
        redoStack.addLast(e)
        return e.edit
    }

    fun redo(page: PageState): StrokeEdit? {
        val e = redoStack.removeLastOrNull() ?: return null
        e.positions = page.apply(e.edit)
        undoStack.addLast(e)
        return e.edit
    }

    fun clear() { undoStack.clear(); redoStack.clear() }
}
