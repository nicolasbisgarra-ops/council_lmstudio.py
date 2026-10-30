package io.trazo.core

/** Aplica ids guardados; ignora repetidos y borrados y agrega los nuevos en su orden actual. Los items deben tener ids únicos. */
fun <T : Any> applySavedOrder(items: List<T>, order: List<String>, id: (T) -> String): List<T> {
    val remaining = items.associateByTo(linkedMapOf(), id)
    val result = ArrayList<T>(items.size)
    for (key in order) remaining.remove(key)?.let { result.add(it) }
    result.addAll(remaining.values)
    return result
}
