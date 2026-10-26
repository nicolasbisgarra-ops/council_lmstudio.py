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
) {
    enum class Kind { DRAW, CONVERT, ERASE, TRANSFORM, DELETE, INSERT, CLEAR }

    val isEmpty: Boolean get() = removed.isEmpty() && added.isEmpty()
}

/** Estado mutable de la página que edita la sesión. Conserva el orden (z) de los trazos. */
class PageState(initial: List<InkStroke> = emptyList()) {
    private val list = ArrayList(initial)

    /** Se incrementa con cada cambio; la vista lo usa para invalidar su caché. */
    var version: Long = 0L
        private set

    val strokes: List<InkStroke> get() = list

    fun find(id: String): InkStroke? = list.firstOrNull { it.id == id }
    fun contains(id: String): Boolean = list.any { it.id == id }

    /** Aplica el cambio y devuelve la posición previa de cada trazo quitado (para revertir exacto). */
    fun apply(edit: StrokeEdit): Map<String, Int> {
        val removeIds = edit.removed.mapTo(HashSet()) { it.id }
        val addedById = edit.added.associateBy { it.id }
        val positions = HashMap<String, Int>()
        val placed = HashSet<String>()
        val out = ArrayList<InkStroke>(list.size + edit.added.size)
        list.forEachIndexed { i, s ->
            if (s.id in removeIds) {
                positions[s.id] = i
                addedById[s.id]?.let { out += it; placed += it.id }
            } else if (s.id in addedById) {
                // Id duplicado: el agregado reemplaza al existente.
                out += addedById.getValue(s.id); placed += s.id
            } else {
                out += s
            }
        }
        edit.added.forEach { if (it.id !in placed) out += it }
        list.clear(); list.addAll(out)
        version++
        return positions
    }

    /** Deshace [edit] usando las posiciones devueltas por [apply]. */
    fun revert(edit: StrokeEdit, positions: Map<String, Int>) {
        val addedIds = edit.added.mapTo(HashSet()) { it.id }
        val removedById = edit.removed.associateBy { it.id }
        val restoredInPlace = HashSet<String>()
        val out = ArrayList<InkStroke>(list.size + edit.removed.size)
        for (s in list) {
            if (s.id in addedIds) {
                removedById[s.id]?.let { out += it; restoredInPlace += it.id }
            } else {
                out += s
            }
        }
        val pending = edit.removed.filter { it.id !in restoredInPlace }
        val (known, unknown) = pending.partition { it.id in positions }
        known.sortedBy { positions.getValue(it.id) }.forEach { s ->
            out.add(positions.getValue(s.id).coerceIn(0, out.size), s)
        }
        out.addAll(unknown)
        list.clear(); list.addAll(out)
        version++
    }

    fun replaceAll(strokes: List<InkStroke>) {
        list.clear(); list.addAll(strokes); version++
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
