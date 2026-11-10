package io.trazo.core.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTextTest {
    @Test fun escapesEverything() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", WebText.escape("&<>\"'"))
        val html = WebText.html("Hola <script>alert('x')</script> & \"chau\"\n\n<img src=x onerror=alert(1)>")
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<img"))
        assertTrue(html, html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; &quot;chau&quot;"))
        // Tampoco dentro de una fórmula, de código o de una tabla.
        val tricky = WebText.html("\$</span><script>x</script>\$ y `</code><b>` y\n\n```\n</pre><i>\n```\n\n|a|b|\n|-|-|\n|<u>|\"|")
        for (tag in listOf("<script", "<b>", "<i>", "<u>")) assertFalse(tricky, tricky.contains(tag))
        assertTrue(tricky, tricky.contains("<span class=\"tex\">&lt;/span&gt;&lt;script&gt;x&lt;/script&gt;</span>"))
        assertTrue(tricky, tricky.contains("<code>&lt;/code&gt;&lt;b&gt;</code>"))
        assertTrue(tricky, tricky.contains("<pre><code>&lt;/pre&gt;&lt;i&gt;</code></pre>"))
    }

    @Test fun paragraphsAndLineBreaks() {
        assertEquals("<p>uno<br>dos</p><p>tres</p>", WebText.html("uno\ndos\n\n\ntres\n"))
        assertEquals("", WebText.html("  \n\n"))
        assertEquals("<p>a    b</p>", WebText.html("a\tb\r\n"))
    }

    @Test fun headingsRulesAndQuotes() {
        assertEquals("<h5>1. Identificación del problema</h5><p>Texto</p>", WebText.html("### 1. Identificación del problema\nTexto"))
        assertEquals("<h3>Título</h3>", WebText.html("# Título ##"))
        assertEquals("<h6>Chico</h6>", WebText.html("###### Chico"))
        assertEquals("<p>#sin espacio</p>", WebText.html("#sin espacio"))
        assertEquals("<p>arriba</p><hr><p>abajo</p>", WebText.html("arriba\n\n---\n\nabajo"))
        assertEquals("<blockquote>cita<br>sigue</blockquote><p>fuera</p>", WebText.html("> cita\n> sigue\nfuera"))
    }

    @Test fun emphasis() {
        assertEquals("<p><strong>negrita</strong> y <em>cursiva</em> y <strong><em>las dos</em></strong></p>", WebText.html("**negrita** y *cursiva* y ***las dos***"))
        assertEquals("<p><strong>a <em>b</em></strong></p>", WebText.html("**a *b***"))
        assertEquals("<p><strong>Paso 1:</strong> sigue</p>", WebText.html("__Paso 1:__ sigue"))
        assertEquals("<p><del>no</del> sí</p>", WebText.html("~~no~~ sí"))
        // Lo que no es énfasis queda como está.
        assertEquals("<p>2 * 3 = 6 y 4 * 5</p>", WebText.html("2 * 3 = 6 y 4 * 5"))
        assertEquals("<p>mi_variable_larga y a_b</p>", WebText.html("mi_variable_larga y a_b"))
        assertEquals("<p>**sin cerrar</p>", WebText.html("**sin cerrar"))
        assertEquals("<p>1. punto y *asterisco* y #</p>", WebText.html("1\\. punto y \\*asterisco\\* y \\#"))
        assertEquals("<p>usa <code>x*y*z</code> acá</p>", WebText.html("usa `x*y*z` acá"))
    }

    @Test fun lists() {
        assertEquals("<ul><li>uno</li><li>dos</li></ul>", WebText.html("- uno\n- dos"))
        assertEquals("<ul><li>uno</li><li>dos</li></ul>", WebText.html("* uno\n\n* dos"))
        assertEquals("<ol><li>uno</li><li>dos</li></ol><p>fin</p>", WebText.html("1. uno\n2. dos\n\nfin"))
        assertEquals("<ol start=\"3\"><li>tres</li></ol>", WebText.html("3) tres"))
        assertEquals("<ol><li>paso<br>sigue<ul><li>detalle</li><li>otro</li></ul></li><li>dos</li></ol>", WebText.html("1. paso\n   sigue\n   - detalle\n   - otro\n2. dos"))
        assertEquals("<ul><li>a</li></ul><ol><li>b</li></ol>", WebText.html("- a\n1. b"))
        assertEquals("<ul><li><strong>x</strong>: y</li></ul>", WebText.html("- **x**: y"))
        // Un párrafo con sangría después de un blanco sigue en el ítem; sin sangría, lo cierra.
        assertEquals("<ul><li>a<br>b</li></ul>", WebText.html("- a\n\n  b"))
        assertEquals("<ul><li>a</li></ul><p>b</p>", WebText.html("- a\n\nb"))
    }

    @Test fun tablesAndCode() {
        val t = WebText.html("| Paso | Valor |\n|:--|--:|\n| 1 | \$\\frac{1}{2}\$ |\n| 2 | b \\| c |\nfin")
        assertEquals(
            "<div class=\"tbl\"><table><thead><tr><th>Paso</th><th class=\"r\">Valor</th></tr></thead><tbody>" +
                "<tr><td>1</td><td class=\"r\"><span class=\"tex\">\\frac{1}{2}</span></td></tr>" +
                "<tr><td>2</td><td class=\"r\">b | c</td></tr></tbody></table></div><p>fin</p>",
            t,
        )
        assertEquals("<p>a | b</p>", WebText.html("a | b"))
        assertEquals("<p>antes</p><pre><code>x = 1\n  y = \$2\$ **no**</code></pre><p>después</p>", WebText.html("antes\n```python\nx = 1\n  y = \$2\$ **no**\n```\ndespués"))
        assertEquals("<pre><code>sin cerrar</code></pre>", WebText.html("```\nsin cerrar"))
    }

    @Test fun formulas() {
        assertEquals("<p>Sea <span class=\"tex\">x^2</span> y <span class=\"tex\">a_1 * b_2 * c</span>.</p>", WebText.html("Sea \$x^2\$ y \\(a_1 * b_2 * c\\)."))
        // En su renglón: sin <br> alrededor.
        assertEquals(
            "<p>Queda:<span class=\"tex display\">\\frac{1}{2}</span>y sigue</p>",
            WebText.html("Queda:\n\$\$\\frac{1}{2}\$\$\ny sigue"),
        )
        assertEquals("<p><span class=\"tex display\">x</span></p>", WebText.html("\\[x\\]"))
        assertEquals("<p>Cuesta $ 5 y $ 6</p>", WebText.html("Cuesta $ 5 y $ 6"))
        assertEquals("<p><strong>Resultado: <span class=\"tex\">x=2</span></strong></p>", WebText.html("**Resultado: \$x=2\$**"))
        assertEquals("<ol><li>Paso:<span class=\"tex display\">a|b</span></li><li>Fin</li></ol>", WebText.html("1. Paso:\n   \$\$a|b\$\$\n2. Fin"))
        // Las marcas internas no se pueden falsificar desde el texto.
        assertEquals("<p>0 y <span class=\"tex\">y</span></p>", WebText.html("0 y \$y\$"))
        assertEquals("<p>1</p>", WebText.html("1"))
    }

    @Test fun inlineTexts() {
        assertEquals("Falta el <strong>signo</strong> en <span class=\"tex\">-x</span>", WebText.inline("Falta el **signo** en \$-x\$"))
        assertEquals("uno<br>dos", WebText.inline("uno\n\ndos"))
        assertEquals("# no es título", WebText.inline("# no es título"))
        assertEquals("&lt;b&gt;", WebText.inline("<b>"))
    }

    @Test fun aRealAnswer() {
        val reply = """
            ### 1. Identificación del problema y factorización del denominador
            Para resolver la integral ${'$'}\int_{-\infty}^{3} \frac{dx}{x^2 - 4x + 3}${'$'}, primero debemos trabajar con el denominador. Estos son ${'$'}-1${'$'} y ${'$'}-3${'$'}:
            ${'$'}${'$'}x^2 - 4x + 3 = (x - 1)(x - 3)${'$'}${'$'}

            ### 2. Descomposición en fracciones parciales
            ${'$'}${'$'}\frac{1}{(x-1)(x-3)} = \frac{A}{x-1} + \frac{B}{x-3}${'$'}${'$'}
            Multiplicando por ${'$'}(x-1)(x-3)${'$'} obtenemos el sistema:
        """.trimIndent()
        val html = WebText.html(reply)
        assertFalse(html, html.contains("###"))
        assertFalse(html, html.contains("$"))
        assertTrue(html, html.startsWith("<h5>1. Identificación del problema y factorización del denominador</h5><p>Para resolver la integral <span class=\"tex\">\\int_{-\\infty}^{3} \\frac{dx}{x^2 - 4x + 3}</span>, primero"))
        assertTrue(html, html.contains("<span class=\"tex\">-3</span>:<span class=\"tex display\">x^2 - 4x + 3 = (x - 1)(x - 3)</span></p><h5>2. Descomposición"))
        assertTrue(html, html.contains("</span>Multiplicando por <span class=\"tex\">(x-1)(x-3)</span> obtenemos el sistema:</p>"))
    }

    @Test fun hugeAndWeirdInputNeverThrows() {
        val big = "*".repeat(5000) + "_".repeat(5000) + "`".repeat(999) + "|".repeat(999) + "\n" + "- ".repeat(3000) + "\n" + "$".repeat(4001)
        WebText.html(big)
        WebText.inline(big)
        val nested = (1..40).joinToString("") { "**a *b " } + (1..40).joinToString("") { "c* d** " }
        WebText.html(nested)
        assertTrue(WebText.html("x".repeat(300_000)).length < 210_000)
        for (s in listOf("\\", "$", "$$", "`", "|", "|\n|-|", "> ", "1. ", "- ", "#", "\\[", "```", "***", "~~", "", "")) {
            WebText.html(s); WebText.inline(s)
        }
    }
}
