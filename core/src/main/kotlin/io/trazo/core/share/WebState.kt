package io.trazo.core.share

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Un mensaje del chat de revisión, tal como lo tiene la app (texto crudo). */
data class SharedMessage(
    val fromStudent: Boolean,
    val text: String,
    val line: Int? = null,
    val correct: Boolean? = null,
    val verdict: String? = null,
    val error: Boolean = false,
    /** Nombre de la imagen que adjuntó el estudiante, o null. */
    val image: String? = null,
)

/** Una marca de la IA sobre la hoja. */
data class SharedNote(val line: Int, val kind: String, val short: String, val detail: String = "", val fix: String = "")

/**
 * Lo que la app le muestra a la web en este momento. Sin hoja abierta ([open] false) solo va [hint], que
 * explica por qué (no hay hoja, modo examen).
 */
data class SharedReview(
    val open: Boolean,
    val title: String = "",
    val hint: String? = null,
    /** Cambia cuando cambia el dibujo de la hoja; null si no hay imagen para mostrar. */
    val pageKey: String? = null,
    val messages: List<SharedMessage> = emptyList(),
    val notes: List<SharedNote> = emptyList(),
    /** El tutor está contestando. */
    val busy: Boolean = false,
    /** Lo que lleva escrito de la respuesta en curso. */
    val streaming: String? = null,
    /** Preguntas rápidas para mandar con un toque. */
    val quick: List<String> = emptyList(),
)

@Serializable
data class WebMessage(
    /** Posición y contenido: si no cambia, el navegador no vuelve a dibujar el mensaje. */
    val id: String,
    val me: Boolean,
    val html: String,
    val line: Int? = null,
    val correct: Boolean? = null,
    val verdict: String? = null,
    val error: Boolean = false,
    val image: String? = null,
)

@Serializable
data class WebNote(val line: Int, val kind: String, val short: String, val detail: String = "", val fix: String = "")

/** El estado que baja el navegador (`/api/state`); todos los textos ya son HTML seguro ([WebText]). */
@Serializable
data class WebState(
    val v: String,
    val open: Boolean,
    val title: String = "",
    val hint: String? = null,
    val page: String? = null,
    val busy: Boolean = false,
    val streaming: String? = null,
    val messages: List<WebMessage> = emptyList(),
    val notes: List<WebNote> = emptyList(),
    /** Texto plano: el navegador lo pone como texto, no como HTML. */
    val quick: List<String> = emptyList(),
)

/** Arma el JSON de [WebState] a partir de lo que tiene la app, y guarda el HTML ya hecho de cada mensaje. */
class WebStateBuilder(private val cacheSize: Int = 256) {
    private val htmlCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > cacheSize
    }

    @Synchronized
    private fun html(text: String): String = htmlCache.getOrPut(text) { WebText.html(text) }

    /** La versión de [r]: igual mientras no cambie nada de lo que se ve. */
    fun version(r: SharedReview): String {
        val d = MessageDigest.getInstance("SHA-256")
        fun put(s: String?) {
            val b = (s ?: "\u0000").toByteArray(Charsets.UTF_8)
            d.update(b.size.toString().toByteArray(Charsets.US_ASCII))
            d.update(':'.code.toByte())
            d.update(b)
        }
        put(FORMAT.toString()); put(r.open.toString()); put(r.title); put(r.hint); put(r.pageKey); put(r.busy.toString()); put(r.streaming)
        put(r.messages.size.toString())
        for (m in r.messages) {
            put(m.fromStudent.toString()); put(m.text); put(m.line?.toString()); put(m.correct?.toString())
            put(m.verdict); put(m.error.toString()); put(m.image)
        }
        put(r.notes.size.toString())
        for (n in r.notes) { put(n.line.toString()); put(n.kind); put(n.short); put(n.detail); put(n.fix) }
        put(r.quick.size.toString())
        for (q in r.quick) put(q)
        return hex(d.digest(), 12)
    }

    fun state(r: SharedReview, version: String = version(r)): WebState {
        if (!r.open) return WebState(v = version, open = false, hint = r.hint)
        return WebState(
            v = version,
            open = true,
            title = r.title,
            hint = r.hint,
            page = r.pageKey?.let { id(it) },
            busy = r.busy,
            streaming = r.streaming?.takeIf { it.isNotBlank() }?.let { WebText.html(it) },
            messages = r.messages.mapIndexed { i, m ->
                WebMessage(
                    id = "$i-" + id(listOf(m.fromStudent, m.text, m.line, m.correct, m.verdict, m.error, m.image).joinToString("\u0001")),
                    me = m.fromStudent,
                    html = html(m.text),
                    line = m.line,
                    correct = m.correct,
                    verdict = m.verdict?.takeIf { it.isNotBlank() }?.let { WebText.inline(it) },
                    error = m.error,
                    image = m.image,
                )
            },
            notes = r.notes.map { WebNote(it.line, WebText.escape(it.kind), WebText.inline(it.short), WebText.inline(it.detail), WebText.inline(it.fix)) },
            quick = r.quick,
        )
    }

    fun json(state: WebState): String = JSON.encodeToString(WebState.serializer(), state)

    private fun id(s: String): String = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)), 8)

    companion object {
        /** Sube cuando cambia la forma del HTML, para que el navegador no se quede con uno viejo. */
        private const val FORMAT = 2
        /** Las listas vacías y los «false» viajan igual: el navegador no tiene que adivinar lo que falta. */
        val JSON = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

        /** Lo que contesta `/api/state` cuando el navegador ya tiene esta versión. */
        fun same(version: String): String = "{\"v\":\"$version\",\"same\":true}"

        private fun hex(b: ByteArray, bytes: Int): String {
            val s = StringBuilder(bytes * 2)
            for (i in 0 until bytes) s.append("%02x".format(b[i].toInt() and 0xff))
            return s.toString()
        }
    }
}
