package io.trazo.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T23 / T24 (revisión de fuente automatizada): la app es nativa y la hoja solo contiene trazos.
 * Se ignoran comentarios; se revisa el código real de `src/main`.
 */
class SourcePolicyTest {
    private val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
    private val sources = main.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml") }.toList()

    private fun code(f: File): String = f.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//").let { l -> if ("://" in it) it else l } }

    @Test fun `sources exist`() {
        assertTrue("No se encontraron fuentes en ${main.absolutePath}", sources.any { it.name == "InkCanvasView.kt" })
    }

    @Test fun `T23 no web runtime in the app`() {
        val forbidden = listOf("WebView", "loadUrl", "evaluateJavascript", "JavascriptInterface", "capacitor", "cordova", "flutter", "ReactActivity")
        for (f in sources) for (word in forbidden) {
            assertFalse("${f.path} usa $word", code(f).contains(word, ignoreCase = true))
        }
    }

    @Test fun `T24 no typeset math engines anywhere`() {
        val forbidden = listOf("latex", "katex", "mathjax", "mathlive", "MathView")
        for (f in sources) for (word in forbidden) {
            assertFalse("${f.path} menciona $word", code(f).contains(word, ignoreCase = true))
        }
    }

    @Test fun `T24 the ink surface never draws text, fonts or images`() {
        val ink = sources.filter { it.path.replace('\\', '/').contains("/ink/") }
        assertTrue(ink.isNotEmpty())
        val forbidden = listOf("drawText", "Typeface", "TextView", "StaticLayout", "BitmapFactory", "drawPicture", "ImageView", "Html.")
        for (f in ink) for (word in forbidden) {
            assertFalse("${f.path} usa $word", code(f).contains(word))
        }
    }

    /** Internet es solo para el Modo IA (módulo :ai); el motor y el resto de la app no tocan la red. */
    @Test fun `only the AI mode touches the network`() {
        val network = listOf("java.net", "HttpURLConnection", "okhttp", "Socket(", "UrlConnectionTransport")
        for (f in sources.filterNot { it.name.startsWith("Ai") }) for (word in network) {
            assertFalse("${f.path} usa $word", code(f).contains(word))
        }
        val core = (File("../core/src/main").takeIf { it.exists() } ?: File("core/src/main"))
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(core.isNotEmpty())
        for (f in core) assertFalse("${f.path} usa la red", code(f).contains("java.net"))
    }
}
