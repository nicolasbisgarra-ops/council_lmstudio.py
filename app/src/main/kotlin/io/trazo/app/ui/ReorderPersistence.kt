package io.trazo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState

/** Confirma el orden al soltar o cancelar, y también si se cierra la pantalla durante el gesto. */
@Composable
internal fun PersistReorderOnEnd(state: ReorderableState, dirty: () -> Boolean, persist: () -> Unit) {
    val flush = rememberUpdatedState { if (dirty()) persist() }
    LaunchedEffect(state.draggingKey, dirty()) {
        if (state.draggingKey == null) flush.value()
    }
    DisposableEffect(state) {
        onDispose { flush.value() }
    }
}
