package io.trazo.core

/**
 * Mueve por índice sin modificar la lista recibida ni los elementos.
 * [to] es el índice final, acotado a las posiciones de la lista original.
 * Si [from] no existe o ya coincide con el destino, conserva la lista.
 */
fun <T> moveItem(list: List<T>, from: Int, to: Int): List<T> {
    if (from !in list.indices) return list
    val destination = to.coerceIn(0, list.lastIndex)
    if (from == destination) return list
    return list.toMutableList().apply { add(destination, removeAt(from)) }
}

/** Posición para mostrar, desde uno; sin elementos muestra «0 / 0». */
fun positionLabel(index: Int, total: Int): String {
    if (total <= 0) return "0 / 0"
    return "${index.coerceIn(0, total - 1) + 1} / $total"
}
