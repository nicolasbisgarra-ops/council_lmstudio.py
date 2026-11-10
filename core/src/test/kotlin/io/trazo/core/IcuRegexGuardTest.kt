package io.trazo.core

import java.io.File
import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * Android compila las expresiones regulares con ICU, que dentro de `[...]` lee `{…}` como una cadena
 * (`[^{}]` falla al cargar la clase y cierra la app). La JVM las acepta, así que los tests no lo ven:
 * acá se revisa el código fuente y se exige escribir `\{` y `\}` dentro de las clases.
 */
class IcuRegexGuardTest {
    private val literal = Regex("(?:Regex|toRegex|Pattern\\.compile)\\(\\s*(\"\"\"(.*?)\"\"\"|\"((?:\\\\.|[^\"\\\\])*)\")")

    @Test
    fun bracesInsideCharacterClassesAreEscaped() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val bad = listOf("app", "ai", "core").flatMap { module ->
            File(root, "$module/src/main").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
                file.readLines().withIndex().flatMap { (i, line) ->
                    literal.findAll(line).mapNotNull { m ->
                        val pattern = m.groupValues[2].ifEmpty { m.groupValues[3].replace("\\\\", "\\").replace("\\\"", "\"") }
                        "${file.relativeTo(root)}:${i + 1}: $pattern".takeIf { unescapedBraceInClass(pattern) }
                    }.toList()
                }
            }.toList()
        }
        assertTrue("Llaves sin escapar dentro de [...] (ICU las rechaza):\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun detectsTheCrashPattern() {
        assertTrue(unescapedBraceInClass("""\{([^{}]*)\}"""))
        assertTrue(unescapedBraceInClass("""[{}^_]"""))
        assertTrue(!unescapedBraceInClass("""\{([^\{\}]*)\}"""))
        assertTrue(!unescapedBraceInClass("""\p{L}{1,3}[a-z]"""))
        assertTrue(!unescapedBraceInClass("""[^\p{L}\p{N}]+"""))
    }

    private fun unescapedBraceInClass(p: String): Boolean {
        var depth = 0
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> {
                    i++
                    // \p{L}, \P{..}, \x{..}, \N{..}: la llave es parte del escape.
                    if (i + 1 < p.length && p[i] in "pPxN" && p[i + 1] == '{') i = p.indexOf('}', i).let { if (it < 0) p.length else it }
                }
                c == '[' -> depth++
                c == ']' && depth > 0 -> depth--
                (c == '{' || c == '}') && depth > 0 -> return true
            }
            i++
        }
        return false
    }
}
