package io.trazo.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import io.trazo.app.data.ImageImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.trazo.ai.AiTutor
import io.trazo.ai.TutorRole
import io.trazo.app.data.AiChatMessage
import io.trazo.core.math.tex.TexMath
import io.trazo.core.math.tex.TexPart
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * Chat de revisión (Modo IA): un panel que se despliega a la izquierda de la hoja. El tutor rehace la
 * resolución paso a paso y marca el primer error; las fórmulas se escriben con LaTeX de verdad (JLaTeXMath,
 * sin WebView ni red), solo acá: la hoja sigue siendo tinta.
 */
@Composable
internal fun AiChatPanel(
    vm: EditorViewModel,
    modifier: Modifier = Modifier,
    /** Tamaño de la letra del chat (sp) y cómo cambiarlo. */
    fontSp: Float = CHAT_SP_DEFAULT,
    onFontSp: (Float) -> Unit = {},
    /** Arrastre del asa del borde: cuántos dp se movió, y cuándo se soltó (para guardar el ancho). */
    onResizeBy: (Float) -> Unit = {},
    onResizeEnd: () -> Unit = {},
) {
    val density = LocalDensity.current.density
    val resize by rememberUpdatedState(onResizeBy)
    val resizeEnd by rememberUpdatedState(onResizeEnd)
    CompositionLocalProvider(LocalChatSp provides fontSp) {
        Box(modifier) {
            AiChatContent(vm, onFontSp, Modifier.fillMaxSize())
            // Asa para cambiar el ancho: una franja sobre el borde derecho del panel.
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(16.dp).pointerInput(density) {
                    detectHorizontalDragGestures(onDragEnd = { resizeEnd() }, onDragCancel = { resizeEnd() }) { change, dx ->
                        change.consume()
                        resize(dx / density)
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(4.dp).height(40.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
            }
        }
    }
}

/** Tamaño de letra del chat por omisión (el de bodyLarge) y sus límites. */
internal const val CHAT_SP_DEFAULT = 16f
internal const val CHAT_SP_MIN = 12f
internal const val CHAT_SP_MAX = 24f

/** Tamaño de letra vigente del chat, para no pasarlo por cada función. */
private val LocalChatSp = compositionLocalOf { CHAT_SP_DEFAULT }

@Composable
private fun AiChatContent(vm: EditorViewModel, onFontSp: (Float) -> Unit, modifier: Modifier) {
    rememberLatex()
    val chatSp = LocalChatSp.current
    val messages = vm.chatMessages
    val streaming = vm.chatStreaming
    val busy = vm.chatBusy
    val list = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val count = messages.size + (if (streaming != null) 1 else 0)
    LaunchedEffect(count, (streaming?.length ?: 0) / 120) {
        if (count > 0) list.animateScrollToItem(count - 1)
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AiStarsIcon, null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Chat de revisión", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onFontSp((chatSp - 2f).coerceIn(CHAT_SP_MIN, CHAT_SP_MAX)) }, enabled = chatSp > CHAT_SP_MIN, modifier = Modifier.size(36.dp)) {
                    Text("A−", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                IconButton(onClick = { onFontSp((chatSp + 2f).coerceIn(CHAT_SP_MIN, CHAT_SP_MAX)) }, enabled = chatSp < CHAT_SP_MAX, modifier = Modifier.size(36.dp)) {
                    Text("A+", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                IconButton(onClick = vm::restartChat, enabled = !busy) { Icon(Icons.Filled.Refresh, contentDescription = "Revisar de nuevo") }
                IconButton(onClick = vm::closeChat) { Icon(Icons.Filled.Close, contentDescription = "Plegar el chat") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), state = list,
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (messages.isEmpty() && streaming == null) {
                    item {
                        Text(
                            "Tocá «Revisar de nuevo» para que el tutor rehaga tu resolución y te diga dónde está el primer error.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(messages) { m -> ChatMessage(vm, m, onShow = { vm.showChatLine(m) }) }
                if (streaming != null) {
                    item {
                        if (streaming.isBlank()) {
                            Text("Revisando tu resolución…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            ChatRichText(streaming)
                        }
                    }
                }
            }
            val attachment = vm.chatAttachment
            val canAsk = !busy && (messages.any { !it.error } || attachment != null)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (q in AiTutor.QUICK) {
                    SuggestionChip(onClick = { vm.sendChat(q) }, label = { Text(q) }, enabled = canAsk)
                }
            }
            if (attachment != null || vm.chatAttaching) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (attachment != null) ChatImageThumb(vm, attachment, 72.dp)
                    else Text("Cargando imagen…", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = vm::clearChatAttachment, enabled = attachment != null) {
                        Icon(Icons.Filled.Close, contentDescription = "Quitar la imagen")
                    }
                }
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                if (uri != null) vm.attachChatImage { checkNotNull(context.contentResolver.openInputStream(uri)) { "No se pudo abrir la imagen" } }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !busy && !vm.chatAttaching,
                ) { Icon(TrazoIcons.Image, contentDescription = "Adjuntar una imagen") }
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Preguntá algo…", fontSize = chatSp.sp) }, maxLines = 4,
                    textStyle = LocalTextStyle.current.copy(fontSize = chatSp.sp),
                )
                IconButton(
                    onClick = { val q = draft; draft = ""; vm.sendChat(q) },
                    enabled = canAsk && (draft.isNotBlank() || attachment != null),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar") }
            }
        }
    }
}

@Composable
private fun ChatMessage(vm: EditorViewModel, m: AiChatMessage, onShow: () -> Unit) {
    val chatSp = LocalChatSp.current
    when {
        m.role == TutorRole.STUDENT -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            m.image?.let { ChatImageThumb(vm, it, 160.dp) }
            Text(
                m.text,
                Modifier.widthIn(max = 280.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = chatSp.sp, lineHeight = (chatSp * 1.5f).sp,
            )
        }
        m.error -> Text(
            m.text,
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp)).padding(10.dp),
            color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
            fontSize = (chatSp * 0.875f).sp,
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (m.text.isNotBlank()) {
                ChatRichText(m.text)
                TextButton(onClick = { vm.writeTutorAnswer(m.text) }) { Text("Escribir en la hoja") }
            }
            m.correct?.let { ok -> VerdictCard(ok, m.line, m.verdict.orEmpty(), onShow.takeIf { m.strokeIds.isNotEmpty() }) }
        }
    }
}

/** Miniatura de una imagen del chat (se decodifica achicada, fuera del hilo de la UI). */
@Composable
private fun ChatImageThumb(vm: EditorViewModel, name: String, side: Dp) {
    val px = with(LocalDensity.current) { side.roundToPx() }
    val bmp by produceState<ImageBitmap?>(null, name, px) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val src = ImageImport.decodeFile(vm.chatImageFile(name).path) ?: return@runCatching null
                val small = ImageImport.scaledDown(src, px * 2)
                if (small !== src) src.recycle()
                small.asImageBitmap()
            }.getOrNull()
        }
    }
    val b = bmp
    if (b == null) {
        Box(Modifier.size(side).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)))
    } else {
        Image(
            b, contentDescription = "Imagen adjunta",
            modifier = Modifier.widthIn(max = side * 1.6f).heightIn(max = side).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun VerdictCard(correct: Boolean, line: Int?, summary: String, onShow: (() -> Unit)?) {
    val bg = if (correct) Color(0xFFE3F4E6) else Color(0xFFFDE4E1)
    val fg = if (correct) Color(0xFF1B5E20) else Color(0xFFB3261E)
    Column(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (correct) "Correcto" else if (line != null) "Primer error · renglón $line" else "Primer error",
            color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall,
        )
        if (summary.isNotBlank()) ChatRichText(summary, color = Color(0xFF1C1B1F))
        if (onShow != null) TextButton(onClick = onShow) { Text("Ver en la hoja", color = fg) }
    }
}

/** Prosa con fórmulas: las de su renglón se dibujan aparte (con desplazamiento lateral si no entran). */
@Composable
internal fun ChatRichText(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    val chatSp = LocalChatSp.current
    val parts = remember(text) { TexMath.split(text) }
    val groups = remember(parts) {
        val out = mutableListOf<List<TexPart>>()
        var run = mutableListOf<TexPart>()
        for (p in parts) {
            if (p is TexPart.Formula && p.display) {
                if (run.isNotEmpty()) { out += run; run = mutableListOf() }
                out += listOf(p)
            } else run += p
        }
        if (run.isNotEmpty()) out += run
        out
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (g in groups) {
            val single = g.singleOrNull()
            if (single is TexPart.Formula && single.display) {
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
                    LatexFormula(single.source, DISPLAY_SP * chatSp / CHAT_SP_DEFAULT, color)
                }
            } else {
                val trimmed = trimGroup(g)
                if (trimmed.isNotEmpty()) InlineParagraph(trimmed, color)
            }
        }
    }
}

@Composable
private fun InlineParagraph(parts: List<TexPart>, color: Color) {
    val density = LocalDensity.current
    val chatSp = LocalChatSp.current
    val style = MaterialTheme.typography.bodyLarge.copy(fontSize = chatSp.sp, lineHeight = (chatSp * 1.5f).sp)
    val fontPx = with(density) { style.fontSize.toPx() }
    val argb = color.toArgb()
    val drawables = remember(parts, fontPx, argb) {
        // Una fórmula vacía («\\( \\)») no se dibuja: Compose no acepta un texto alternativo vacío.
        parts.filterIsInstance<TexPart.Formula>().filter { it.source.isNotBlank() }
            .associate { it.source to latexDrawable(it.source, fontPx * 1.05f, argb) }
    }
    val inline = drawables.entries.filter { it.value != null }.mapIndexed { i, (source, d) ->
        val w = with(density) { (d!!.intrinsicWidth + fontPx * 0.2f).toSp() }
        val h = with(density) { (d!!.intrinsicHeight + fontPx * 0.25f).coerceAtLeast(fontPx).toSp() }
        "f$i" to (source to InlineTextContent(Placeholder(w, h, PlaceholderVerticalAlign.TextCenter)) { DrawLatex(d!!, centered = true) })
    }.toMap()
    val ids = inline.entries.associate { (id, v) -> v.first to id }
    val annotated = buildAnnotatedString {
        for (p in parts) when (p) {
            is TexPart.Prose -> append(markdown(p.text))
            is TexPart.Formula -> ids[p.source]?.let { appendInlineContent(it, p.source.ifBlank { " " }) } ?: append(p.source)
        }
    }
    // Con altura de renglón fija, una fórmula alta (∫, fracción) se dibuja encima de los renglones vecinos;
    // sin ella, cada renglón crece hasta lo que mide su fórmula.
    val lineH = if (style.lineHeight.isSp) with(density) { style.lineHeight.toPx() } else fontPx * 1.3f
    val tall = drawables.values.any { it != null && it.intrinsicHeight > lineH * 0.95f }
    val textStyle = if (tall) style.copy(lineHeight = TextUnit.Unspecified) else style
    Text(annotated, inlineContent = inline.mapValues { it.value.second }, style = textStyle, color = color)
}

/** Saca los renglones en blanco del principio y del final de un párrafo (los que deja una fórmula aparte). */
private fun trimGroup(g: List<TexPart>): List<TexPart> {
    val out = g.toMutableList()
    (out.firstOrNull() as? TexPart.Prose)?.let { out[0] = TexPart.Prose(it.text.trimStart()) }
    (out.lastOrNull() as? TexPart.Prose)?.let { out[out.lastIndex] = TexPart.Prose(it.text.trimEnd()) }
    return out.filterNot { it is TexPart.Prose && it.text.isEmpty() }
}

private val HEADING = Regex("^\\s*#{1,6}\\s+(.*)$")
private val BULLET = Regex("^(\\s*)[*\\-+]\\s+")
private val BOLD = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__")

/** El formato de chat de la prosa: títulos con «#», viñetas con «*» o «-», **negrita** y «1\\.» escapado. */
private fun markdown(s: String): AnnotatedString = buildAnnotatedString {
    val lines = s.split('\n')
    for ((n, raw) in lines.withIndex()) {
        if (n > 0) append('\n')
        var line = raw.replace("\\.", ".").replace("\\*", "*").replace("\\#", "#")
        if (line.trim().matches(Regex("[-*_]{3,}"))) continue
        val heading = HEADING.find(line)
        if (heading != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.12.em)) { append(heading.groupValues[1].replace("**", "")) }
            continue
        }
        BULLET.find(line)?.let { m -> line = m.groupValues[1] + "•  " + line.substring(m.range.last + 1) }
        var at = 0
        for (m in BOLD.findAll(line)) {
            append(line.substring(at, m.range.first))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1].ifEmpty { m.groupValues[2] }) }
            at = m.range.last + 1
        }
        append(line.substring(at))
    }
}

/** Tamaño de la letra de las fórmulas en su propio renglón. */
private const val DISPLAY_SP = 18f

/** JLaTeXMath carga sus fuentes una vez por proceso. */
@Composable
internal fun rememberLatex() {
    val context = LocalContext.current
    remember { LatexEngine.init(context) }
}

private object LatexEngine {
    @Volatile var ready = false
        private set
    @Volatile private var failed = false

    /** Si las fuentes no cargan, el chat sigue andando: las fórmulas van como texto. */
    fun init(context: android.content.Context) {
        if (ready || failed) return
        synchronized(this) {
            if (ready || failed) return
            try {
                JLatexMathAndroid.init(context.applicationContext)
                ready = true
            } catch (t: Throwable) {
                failed = true
                android.util.Log.w("Trazo", "JLaTeXMath no cargó; las fórmulas del chat van como texto", t)
            }
        }
    }
}

/** Tope del tamaño de una fórmula dibujada: una respuesta enorme no puede pedir un lienzo imposible. */
private const val MAX_FORMULA_PX = 8000

private val ROW_SPACING = Regex("""\\\\\s*\[[^\]]{1,15}\]""")
private val TEXT_ARG = Regex("""\\(text|textrm|textit|textbf|mbox|operatorname)\s*\{([^{}]*)\}""")
private val ACCENTS = mapOf(
    'á' to "\\'a", 'é' to "\\'e", 'í' to "\\'i", 'ó' to "\\'o", 'ú' to "\\'u", 'ñ' to "\\~n", 'ü' to "\\\"u",
    'Á' to "\\'A", 'É' to "\\'E", 'Í' to "\\'I", 'Ó' to "\\'O", 'Ú' to "\\'U", 'Ñ' to "\\~N",
)

/** Ajustes para JLaTeXMath: el «\\\\[8pt]» queda como salto común y las tildes del texto van como acentos de TeX. */
private fun prepareLatex(source: String): String {
    var s = source.replace(ROW_SPACING, "\\\\\\\\")
    s = TEXT_ARG.replace(s) { m ->
        val body = buildString { for (c in m.groupValues[2]) append(ACCENTS[c] ?: c.toString()) }
        "\\" + m.groupValues[1] + "{" + body + "}"
    }
    return s
}

/** Arma la fórmula; si JLaTeXMath no la entiende prueba con un array en vez de aligned, y si no, null (va como texto). */
private fun latexDrawable(source: String, sizePx: Float, color: Int): JLatexMathDrawable? {
    if (!LatexEngine.ready || source.isBlank()) return null
    val prepared = prepareLatex(source)
    val tries = listOf(
        prepared,
        prepared.replace(Regex("""\\begin\{(aligned|align\*?|split)\}"""), "\\\\begin{array}{rl}")
            .replace(Regex("""\\end\{(aligned|align\*?|split)\}"""), "\\\\end{array}"),
    ).distinct()
    for (t in tries) {
        val d = runCatching {
            JLatexMathDrawable.builder(t).textSize(sizePx).color(color).padding(2).build()
        }.getOrNull()
        if (d != null && d.intrinsicWidth in 1..MAX_FORMULA_PX && d.intrinsicHeight in 1..MAX_FORMULA_PX) return d
    }
    return null
}

@Composable
private fun LatexFormula(source: String, sizeSp: Float, color: Color) {
    val density = LocalDensity.current
    val sizePx = with(density) { sizeSp.sp.toPx() }
    val argb = color.toArgb()
    val d = remember(source, sizePx, argb) { latexDrawable(source, sizePx, argb) }
    if (d == null) {
        Text(source, style = MaterialTheme.typography.bodyMedium, color = color)
        return
    }
    with(density) {
        Canvas(Modifier.size(d.intrinsicWidth.toDp(), d.intrinsicHeight.toDp())) { drawLatex(d, 0f) }
    }
}

@Composable
private fun DrawLatex(d: JLatexMathDrawable, centered: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        drawLatex(d, if (centered) (size.height - d.intrinsicHeight) / 2f else 0f)
    }
}

private fun DrawScope.drawLatex(d: JLatexMathDrawable, dy: Float) {
    d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
    drawIntoCanvas { c ->
        val n = c.nativeCanvas
        val saved = n.save()
        try {
            n.translate(0f, dy)
            d.draw(n)
        } catch (t: Throwable) {
            // Lo que JLaTeXMath no sepa dibujar queda en blanco; nunca cierra la app.
            android.util.Log.w("Trazo", "No se pudo dibujar una fórmula del chat", t)
        } finally {
            n.restoreToCount(saved)
        }
    }
}
