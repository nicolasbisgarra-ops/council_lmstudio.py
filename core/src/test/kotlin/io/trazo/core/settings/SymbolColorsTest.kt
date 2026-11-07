package io.trazo.core.settings

import io.trazo.core.Bounds
import io.trazo.core.DefaultPatterns
import io.trazo.core.IdGenerator
import io.trazo.core.InkReplacement
import io.trazo.core.InkStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolColorsTest {
    private val blue = 0xFF1E4FA0.toInt()
    private val anchor = Bounds(100f, 100f, 160f, 260f)

    @Test fun `defaults son verde rojo y violeta`() {
        assertEquals(SymbolColors.GREEN, SymbolColors.defaults["mas"])
        assertEquals(SymbolColors.RED, SymbolColors.defaults["menos"])
        assertEquals(SymbolColors.VIOLET, SymbolColors.defaults[DefaultPatterns.INTEGRAL])
        assertEquals(SymbolColors.defaults, SymbolColors.read(null))
    }

    @Test fun `ida y vuelta de la serializacion`() {
        val colors = mapOf("mas" to SymbolColors.GREEN, "x" to 0x00112233, "alfa" to blue)
        assertEquals(colors, SymbolColors.read(SymbolColors.serialize(colors)))
        assertEquals(SymbolColors.defaults, SymbolColors.read(SymbolColors.serialize(SymbolColors.defaults)))
        assertEquals(emptyMap<String, Int>(), SymbolColors.read(SymbolColors.serialize(emptyMap())))
    }

    @Test fun `texto corrupto se ignora`() {
        assertEquals(mapOf("mas" to SymbolColors.GREEN), SymbolColors.read("basura;=FF;mas=FF2E9E44;x=zz;y"))
    }

    @Test fun `con preset la salida usa ese color aunque la pluma sea azul`() {
        val pen = InkStyle(blue, 3f, io.trazo.core.Brush.PEN)
        val out = InkReplacement.place(
            DefaultPatterns.integral(), anchor, IdGenerator.sequential(), style = pen, presetColor = SymbolColors.VIOLET,
        )
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.color == SymbolColors.VIOLET })
    }

    @Test fun `sin preset adopta el color de la pluma`() {
        val pen = InkStyle(blue, 3f, io.trazo.core.Brush.PEN)
        val out = InkReplacement.place(DefaultPatterns.integral(), anchor, IdGenerator.sequential(), style = pen)
        assertTrue(out.all { it.color == blue })
    }
}
