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

    /** La hoja es tinta: LaTeX (JLaTeXMath, nativo) solo existe en el chat de revisión del Modo IA. */
    @Test fun `T24 no typeset math engines outside the review chat`() {
        val forbidden = listOf("latex", "katex", "mathjax", "mathlive", "MathView")
        for (f in sources) for (word in forbidden) {
            if (f.name == "AiChat.kt" && word == "latex") continue
            assertFalse("${f.path} menciona $word", code(f).contains(word, ignoreCase = true))
        }
        val chat = code(sources.single { it.name == "AiChat.kt" })
        assertFalse(chat.contains("katex", ignoreCase = true) || chat.contains("WebView"))
    }

    /**
     * La única excepción (DECISIONES 36): la web de revisión, que corre en el navegador de otro equipo y no
     * en la app, lleva su motor de fórmulas como archivos fijos. Nada de esa web sale a internet.
     */
    @Test fun `the review web is self-contained and its formula engine stays in its vendor folder`() {
        val web = File(main, "assets/web")
        assertTrue("falta la web de revisión", File(web, "index.html").isFile)
        val assets = File(main, "assets").walkTopDown().filter { it.isFile }.toList()
        for (f in assets) {
            val path = f.path.replace('\\', '/')
            assertTrue("$path no es de la web de revisión", path.contains("/assets/web/"))
            if (f.name.contains("katex", ignoreCase = true)) assertTrue(path, path.contains("/assets/web/vendor/katex/"))
        }
        for (f in web.listFiles().orEmpty().filter { it.isFile && it.extension in setOf("html", "js", "css", "webmanifest") }) {
            val text = f.readText()
            for (word in listOf("https://", "http://", "//cdn", "googleapis", "eval(", "document.write")) {
                assertFalse("${f.name} contiene $word", text.contains(word, ignoreCase = true))
            }
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

    /**
     * La única letra sobre la hoja es la de las notas del Modo IA (12): una capa aparte, que se oculta con el
     * ojo y vive fuera de `ink/`, en `ai/`.
     */
    @Test fun `the AI layer is the only text over the page`() {
        val painter = sources.single { it.name == "AiMarkPainter.kt" }
        assertTrue(painter.path.replace('\\', '/').contains("/app/ai/"))
    }

    /** Las imágenes pegadas son una capa aparte, debajo de la tinta, que vive fuera de `ink/`, en `image/`. */
    @Test fun `pasted images are their own layer outside ink`() {
        val painter = sources.single { it.name == "ImagePainter.kt" }
        assertTrue(painter.path.replace('\\', '/').contains("/app/image/"))
    }

    /**
     * La red es solo para el Modo IA (módulo :ai) y para la sincronización entre los dispositivos del usuario
     * (`app/sync/`, que solo acepta direcciones de la red de casa o Tailscale); el motor y el resto no la tocan.
     */
    @Test fun `only the AI mode and device sync touch the network`() {
        val network = listOf("java.net", "HttpURLConnection", "okhttp", "Socket(", "UrlConnectionTransport")
        fun isSync(f: File) = f.name.startsWith("Sync") && f.path.replace('\\', '/').contains("/app/sync/")
        for (f in sources.filterNot { it.name.startsWith("Ai") || isSync(it) }) for (word in network) {
            assertFalse("${f.path} usa $word", code(f).contains(word))
        }
        val core = (File("../core/src/main").takeIf { it.exists() } ?: File("core/src/main"))
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(core.isNotEmpty())
        for (f in core) assertFalse("${f.path} usa la red", code(f).contains("java.net"))
        val service = sources.single { it.name == "SyncService.kt" }
        assertTrue(isSync(service))
        assertTrue("la sincronización filtra las direcciones", code(service).contains("SyncAddresses.isAllowed"))
    }
}
