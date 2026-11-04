package io.trazo.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.trazo.app.data.label
import io.trazo.app.ink.PaperColors
import io.trazo.core.ImagePreset
import io.trazo.core.PageImages
import kotlin.math.hypot
import kotlinx.coroutines.delay

/**
 * Recorte con perspectiva: se arrastran las cuatro esquinas sobre la hoja fotografiada y el ajuste
 * (Documento, Modo oscuro) sube el contraste y la definición del texto. «Vista previa» muestra el
 * resultado enderezado tal como quedará en la hoja.
 */
@Composable
fun ImageCropDialog(vm: EditorViewModel, s: EditorViewModel.ImageEditSession, darkPaper: Boolean) {
    var quad by remember(s) { mutableStateOf(s.quad) }
    var preset by remember(s) { mutableStateOf(s.preset) }
    var showPreview by remember(s) { mutableStateOf(false) }
    var preview by remember(s) { mutableStateOf<ImageBitmap?>(null) }
    val image = remember(s) { s.preview.asImageBitmap() }

    LaunchedEffect(s, quad, preset, showPreview) {
        if (!showPreview) return@LaunchedEffect
        delay(80)
        preview = vm.previewImage(s, quad, preset)?.asImageBitmap()
    }

    Dialog(onDismissRequest = vm::cancelImageEdit, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (s.existing == null) "Insertar imagen" else "Recortar y ajustar",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    if (showPreview) "Así va a quedar en la hoja, debajo de la tinta."
                    else "Arrastrá las esquinas hasta los bordes de la hoja o la pizarra: se endereza sola.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (showPreview) {
                        val p = preview
                        if (p == null) CircularProgressIndicator()
                        else PreviewPane(p, preset, darkPaper)
                    } else {
                        CropPane(image, quad) { quad = it }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (p in ImagePreset.entries) {
                        FilterChip(selected = preset == p, onClick = { preset = p }, label = { Text(p.label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { quad = PageImages.FULL_QUAD }, enabled = quad != PageImages.FULL_QUAD) { Text("Toda la imagen") }
                    TextButton(onClick = { showPreview = !showPreview }) { Text(if (showPreview) "Recortar" else "Vista previa") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = vm::cancelImageEdit) { Text("Cancelar") }
                    Button(onClick = { vm.finishImageEdit(quad, preset) }, enabled = !vm.imageBusy) {
                        if (vm.imageBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Listo")
                    }
                }
            }
        }
    }
}

/** Lugar donde entra la imagen, centrada y con un margen para alcanzar las esquinas con el dedo. */
private fun fit(w: Int, h: Int, area: IntSize, margin: Float): Rect {
    val s = minOf((area.width - 2 * margin) / w, (area.height - 2 * margin) / h).coerceAtLeast(0.01f)
    val dw = w * s
    val dh = h * s
    val l = (area.width - dw) / 2f
    val t = (area.height - dh) / 2f
    return Rect(l, t, l + dw, t + dh)
}

private fun DrawScope.drawFitted(image: ImageBitmap, r: Rect, filter: ColorFilter? = null) {
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(r.left.toInt(), r.top.toInt()),
        dstSize = IntSize(r.width.toInt().coerceAtLeast(1), r.height.toInt().coerceAtLeast(1)),
        colorFilter = filter,
    )
}

@Composable
private fun CropPane(image: ImageBitmap, quad: List<Float>, onQuad: (List<Float>) -> Unit) {
    val current by rememberUpdatedState(quad)
    val change by rememberUpdatedState(onQuad)
    val accent = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier.fillMaxSize().pointerInput(image) {
            val margin = 28.dp.toPx()
            var corner = -1
            detectDragGestures(
                onDragStart = { at ->
                    val r = fit(image.width, image.height, size, margin)
                    val q = current
                    corner = (0 until 4).minByOrNull { i ->
                        hypot(r.left + q[2 * i] * r.width - at.x, r.top + q[2 * i + 1] * r.height - at.y)
                    } ?: -1
                },
                onDragEnd = { corner = -1 },
                onDragCancel = { corner = -1 },
                onDrag = { pointer, _ ->
                    if (corner < 0) return@detectDragGestures
                    pointer.consume()
                    val r = fit(image.width, image.height, size, margin)
                    val x = ((pointer.position.x - r.left) / r.width).coerceIn(0f, 1f)
                    val y = ((pointer.position.y - r.top) / r.height).coerceIn(0f, 1f)
                    val next = current.toMutableList().also { it[2 * corner] = x; it[2 * corner + 1] = y }
                    // Una esquina que cruzaría el lado opuesto se queda donde estaba.
                    if (PageImages.isValidQuad(next)) change(next)
                },
            )
        },
    ) {
        val margin = 28.dp.toPx()
        val r = fit(image.width, image.height, IntSize(size.width.toInt(), size.height.toInt()), margin)
        drawFitted(image, r)
        val pts = (0 until 4).map { i -> Offset(r.left + quad[2 * i] * r.width, r.top + quad[2 * i + 1] * r.height) }
        val hole = Path().apply { moveTo(pts[0].x, pts[0].y); for (p in pts.drop(1)) lineTo(p.x, p.y); close() }
        val shade = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(r)
            moveTo(pts[0].x, pts[0].y); for (p in pts.drop(1)) lineTo(p.x, p.y); close()
        }
        drawPath(shade, Color.Black.copy(alpha = 0.55f))
        drawPath(hole, accent, style = Stroke(width = 2.dp.toPx()))
        for (p in pts) {
            drawCircle(Color.White, radius = 11.dp.toPx(), center = p)
            drawCircle(accent, radius = 11.dp.toPx(), center = p, style = Stroke(width = 3.dp.toPx()))
        }
    }
}

@Composable
private fun PreviewPane(image: ImageBitmap, preset: ImagePreset, darkPaper: Boolean) {
    val sheet = Color(if (darkPaper) PaperColors.DARK.sheet else PaperColors.LIGHT.sheet)
    // «Modo oscuro» guarda la tinta en el alfa: se ve clara sobre papel oscuro y oscura sobre claro.
    val filter = if (preset == ImagePreset.DARK) {
        ColorFilter.tint(if (darkPaper) Color(0xFFE6E6E6) else Color(0xFF1A1A1A), BlendMode.SrcIn)
    } else null
    Canvas(Modifier.fillMaxSize()) {
        val r = fit(image.width, image.height, IntSize(size.width.toInt(), size.height.toInt()), 12.dp.toPx())
        drawRect(sheet, topLeft = Offset(r.left - 8f, r.top - 8f), size = androidx.compose.ui.geometry.Size(r.width + 16f, r.height + 16f))
        drawFitted(image, r, filter)
    }
}
