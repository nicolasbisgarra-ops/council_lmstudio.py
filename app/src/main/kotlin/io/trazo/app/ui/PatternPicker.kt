package io.trazo.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.trazo.core.DefaultPatterns
import io.trazo.core.InkPattern
import io.trazo.core.PatternCategory
import java.text.Normalizer

/** Sección con integral, raíz y fracción: lo que más se usa va primero. */
const val BASICS_SECTION = "Básicos"

private val BASIC_IDS = listOf(DefaultPatterns.INTEGRAL, DefaultPatterns.ROOT, DefaultPatterns.FRACTION)

/** Patrones agrupados para mostrar: básicos, los del usuario y después cada sección de signos. */
fun patternSections(patterns: List<InkPattern>): List<Pair<String, List<InkPattern>>> {
    val basics = BASIC_IDS.mapNotNull { id -> patterns.firstOrNull { it.id == id } }
    val grouped = patterns.filter { it.id !in BASIC_IDS }.groupBy { PatternCategory.of(it) }
    val order = PatternCategory.ORDER + (grouped.keys - PatternCategory.ORDER.toSet()).sorted()
    return buildList {
        if (basics.isNotEmpty()) add(BASICS_SECTION to basics)
        order.forEach { c -> grouped[c]?.takeIf { it.isNotEmpty() }?.let { add(c to it) } }
    }
}

/** Texto para buscar sin distinguir tildes ni mayúsculas ("raiz" encuentra "Raíz"). */
fun searchKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()

/** Filtra las secciones por búsqueda (nombre del patrón o de la sección) y por sección elegida. */
fun filterSections(
    sections: List<Pair<String, List<InkPattern>>>,
    query: String,
    only: String? = null,
): List<Pair<String, List<InkPattern>>> {
    val q = searchKey(query)
    return sections.filter { only == null || it.first == only }.mapNotNull { (name, list) ->
        val shown = if (q.isEmpty() || searchKey(name).contains(q)) list else list.filter { searchKey(it.name).contains(q) }
        if (shown.isEmpty()) null else name to shown
    }
}

/**
 * Selector de patrones: buscador, píldoras de sección y una grilla con la tinta de salida de cada
 * patrón (nunca texto ni imagen en su lugar). Se usa para insertar, para enseñar un gesto y en el
 * panel lateral de tablet.
 */
@Composable
fun PatternPicker(
    patterns: List<InkPattern>,
    onPick: (InkPattern) -> Unit,
    modifier: Modifier = Modifier,
    minTile: Dp = 104.dp,
    showSearch: Boolean = true,
    showSections: Boolean = true,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var only by rememberSaveable { mutableStateOf<String?>(null) }
    val sections = remember(patterns) { patternSections(patterns) }
    if (only != null && sections.none { it.first == only }) only = null
    val shown = remember(sections, query, only) { filterSections(sections, query, only) }
    Column(modifier) {
        if (showSearch) SearchField(query, { query = it }, "Buscar signo o sección", Modifier.fillMaxWidth().padding(bottom = 8.dp))
        if (showSections && sections.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChoiceChip("Todos", only == null, onClick = { only = null })
                sections.forEach { (name, _) -> ChoiceChip(name, only == name, onClick = { only = if (only == name) null else name }) }
            }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f, fill = false).padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No hay patrones que coincidan.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Adaptive(minTile),
            Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            shown.forEach { (name, list) ->
                if (shown.size > 1 || only == null) {
                    item(key = "h:$name", span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel(name, Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp))
                    }
                }
                items(list, key = { "p:${it.id}" }) { p -> PatternTile(p, onClick = { onPick(p) }) }
            }
        }
    }
}

/** Tarjeta de un patrón: su tinta de salida y el nombre. */
@Composable
fun PatternTile(
    p: InkPattern,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    previewHeight: Dp = 64.dp,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.padding(4.dp),
    ) {
        Column(Modifier.padding(6.dp).alpha(if (dimmed) 0.45f else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
            InkPreview(p.outputInk, Modifier.fillMaxWidth().height(previewHeight))
            Text(
                p.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

/** Buscador de una línea con lupa y botón para limpiar. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Clear, "Limpiar búsqueda") }
        },
        shape = RoundedCornerShape(24.dp),
        modifier = modifier,
    )
}
