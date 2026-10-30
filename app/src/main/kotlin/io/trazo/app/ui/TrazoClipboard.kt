package io.trazo.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.trazo.core.InkStroke

/** Lo último copiado con el lazo: vale para cualquier hoja y cualquier cuaderno mientras la app está abierta. */
object TrazoClipboard {
    var strokes by mutableStateOf<List<InkStroke>>(emptyList())
}
