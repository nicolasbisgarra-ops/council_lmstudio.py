package io.trazo.app.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.trazo.ai.AiTutor
import io.trazo.ai.TutorRole
import io.trazo.app.data.AiChatMessage
import io.trazo.core.math.tex.TexBox
import io.trazo.core.math.tex.TexFont
import io.trazo.core.math.tex.TexInk
import io.trazo.core.math.tex.TexMeasure
import io.trazo.core.math.tex.TexMath
import io.trazo.core.math.tex.TexPart

/**
 * Chat de revisión (Modo IA): un panel que se despliega a la izquierda de la hoja. El tutor rehace la
 * resolución paso a paso y marca el primer error; las fórmulas se arman en letra de imprenta, apiladas.
 */
@Composable
internal fun AiChatPanel(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val ink = ChatTex.ink
    val messages = vm.chatMessages
    val streaming = vm.chatStreaming
    val busy = vm.chatBusy
    val list = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    val count = messages.size + (if (streaming != null) 1 else 0)
    LaunchedEffect(count, (streaming?.length ?: 0) / 120) {
        if (count > 0) list.animateScrollToItem(count - 1)
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AiStarsIcon, null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Chat de revisión", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
                items(messages) { m -> ChatMessage(m, ink, onShow = { vm.showChatLine(m) }) }
                if (streaming != null) {
                    item {
                        if (streaming.isBlank()) {
                            Text("Revisando tu resolución…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            ChatRichText(streaming, ink)
                        }
                    }
                }
            }
            val canAsk = !busy && messages.any { !it.error }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (q in AiTutor.QUICK) {
                    SuggestionChip(onClick = { vm.sendChat(q) }, label = { Text(q) }, enabled = canAsk)
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Preguntá algo…") }, maxLines = 4,
                )
                IconButton(
                    onClick = { val q = draft; draft = ""; vm.sendChat(q) },
                    enabled = canAsk && draft.isNotBlank(),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar") }
            }
        }
    }
}

@Composable
private fun ChatMessage(m: AiChatMessage, ink: TexInk, onShow: () -> Unit) {
    when {
        m.role == TutorRole.STUDENT -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                m.text,
                Modifier.widthIn(max = 280.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        m.error -> Text(
            m.text,
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp)).padding(10.dp),
            color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (m.text.isNotBlank()) ChatRichText(m.text, ink)
            m.correct?.let { ok -> VerdictCard(ok, m.line, m.verdict.orEmpty(), ink, onShow.takeIf { m.strokeIds.isNotEmpty() }) }
        }
    }
}

@Composable
private fun VerdictCard(correct: Boolean, line: Int?, summary: String, ink: TexInk, onShow: (() -> Unit)?) {
    val bg = if (correct) Color(0xFFE3F4E6) else Color(0xFFFDE4E1)
    val fg = if (correct) Color(0xFF1B5E20) else Color(0xFFB3261E)
    Column(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (correct) "Correcto" else if (line != null) "Primer error · renglón $line" else "Primer error",
            color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall,
        )
        if (summary.isNotBlank()) ChatRichText(summary, ink, color = Color(0xFF1C1B1F))
        if (onShow != null) TextButton(onClick = onShow) { Text("Ver en la hoja", color = fg) }
    }
}

/** Prosa con fórmulas: las de su renglón se dibujan aparte (con desplazamiento lateral si no entran). */
@Composable
private fun ChatRichText(text: String, ink: TexInk, color: Color = MaterialTheme.colorScheme.onSurface) {
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
                    FormulaInk(single.source, ink, DISPLAY_DIGIT_SP, color)
                }
            } else {
                val trimmed = trimGroup(g)
                if (trimmed.isNotEmpty()) InlineParagraph(trimmed, ink, color)
            }
        }
    }
}

@Composable
private fun InlineParagraph(parts: List<TexPart>, ink: TexInk, color: Color) {
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyLarge
    val fontPx = with(density) { style.fontSize.toPx() }
    val boxes = remember(parts, ink, fontPx) {
        parts.filterIsInstance<TexPart.Formula>().associate { it.source to ink.layout(it.source, fontPx * 0.7f) }
    }
    val inline = boxes.entries.mapIndexed { i, (source, box) ->
        val w = with(density) { (box.width + fontPx * 0.2f).toSp() }
        val h = with(density) { (box.height + fontPx * 0.1f).coerceAtLeast(fontPx).toSp() }
        "f$i" to (source to InlineTextContent(Placeholder(w, h, PlaceholderVerticalAlign.TextCenter)) { DrawTex(box, color, fontPx * 0.1f) })
    }.toMap()
    val ids = inline.entries.associate { (id, v) -> v.first to id }
    val annotated = buildAnnotatedString {
        for (p in parts) when (p) {
            is TexPart.Prose -> append(markdown(p.text))
            is TexPart.Formula -> ids[p.source]?.let { appendInlineContent(it, p.source) } ?: append(p.source)
        }
    }
    Text(annotated, inlineContent = inline.mapValues { it.value.second }, style = style, color = color)
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

/** La letra de las fórmulas del chat: serif en cursiva para las variables, derecha para cifras y signos. */
private object ChatTex {
    private fun paint(style: Int, family: android.graphics.Typeface) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create(family, style)
    }

    private val italic = paint(android.graphics.Typeface.ITALIC, android.graphics.Typeface.SERIF)
    private val upright = paint(android.graphics.Typeface.NORMAL, android.graphics.Typeface.SERIF)
    private val prose = paint(android.graphics.Typeface.NORMAL, android.graphics.Typeface.DEFAULT)

    fun paint(font: TexFont): android.graphics.Paint = when (font) {
        TexFont.ITALIC -> italic
        TexFont.UPRIGHT -> upright
        TexFont.PROSE -> prose
    }

    val ink = TexInk(emptyList(), typeset = TexMeasure { text, font, size ->
        val p = paint(font)
        p.textSize = size
        p.measureText(text)
    })
}

/** Alto de una cifra en las fórmulas de su propio renglón. */
private const val DISPLAY_DIGIT_SP = 13f

@Composable
private fun FormulaInk(source: String, ink: TexInk, sizeSp: Float, color: Color) {
    val density = LocalDensity.current
    val sizePx = with(density) { sizeSp.sp.toPx() }
    val box = remember(source, ink, sizePx) { ink.layout(source, sizePx) }
    val pad = sizePx * 0.15f
    with(density) {
        Canvas(Modifier.size((box.width + pad * 2).toDp(), (box.height + pad * 2).toDp())) { drawTex(box, color, pad) }
    }
}

@Composable
private fun DrawTex(box: TexBox, color: Color, pad: Float) {
    Canvas(Modifier.fillMaxSize()) {
        // Centrado vertical dentro del espacio del renglón.
        val dy = (size.height - box.height) / 2f - pad
        drawTex(box, color, pad, dy)
    }
}

private fun DrawScope.drawTex(box: TexBox, color: Color, pad: Float, extraY: Float = 0f) {
    val ox = pad
    val oy = pad + box.ascent + extraY
    for (s in box.strokes) {
        val w = s.width
        if (s.points.size == 1) {
            drawCircle(color, w / 2f, Offset(s.points[0].x + ox, s.points[0].y + oy))
            continue
        }
        val path = Path()
        s.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x + ox, p.y + oy) else path.lineTo(p.x + ox, p.y + oy) }
        drawPath(path, color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    if (box.texts.isEmpty()) return
    val argb = color.toArgb()
    drawIntoCanvas { c ->
        for (t in box.texts) {
            val paint = ChatTex.paint(t.font)
            paint.color = argb
            paint.textSize = t.size
            c.nativeCanvas.drawText(t.text, t.x + ox, t.baseline + oy, paint)
        }
    }
}
