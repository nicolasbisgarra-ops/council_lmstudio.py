package io.trazo.core.share

/** Un mensaje del chat de revisión, tal como se muestra en la página web. */
data class SharedMessage(
    val fromStudent: Boolean,
    val text: String,
    val line: Int? = null,
    val correct: Boolean? = null,
    val verdict: String? = null,
    val error: Boolean = false,
)

/** Una marca de la IA sobre la hoja. */
data class SharedNote(val line: Int, val kind: String, val short: String, val detail: String = "", val fix: String = "")

/** Lo que se ve desde otro dispositivo: título, marcas y chat de una hoja. */
data class SharedReview(
    val title: String,
    val messages: List<SharedMessage>,
    val notes: List<SharedNote>,
    /** Cambia cuando cambia la hoja; va en la URL de la imagen para que el navegador no use una vieja. */
    val updatedAt: Long,
)

/**
 * La página web de solo lectura con la revisión: imagen de la hoja con las marcas, la lista de marcas y
 * el chat. Autocontenida (sin scripts ni recursos de afuera) y todo el texto escapado.
 */
object ReviewWebPage {
    fun escape(s: String): String {
        val b = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> b.append("&amp;")
                '<' -> b.append("&lt;")
                '>' -> b.append("&gt;")
                '"' -> b.append("&quot;")
                '\'' -> b.append("&#39;")
                else -> b.append(c)
            }
        }
        return b.toString()
    }

    fun html(r: SharedReview, token: String, refreshSeconds: Int = 15): String = buildString {
        val t = escape(token)
        append("<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<meta name=\"referrer\" content=\"no-referrer\"><meta name=\"robots\" content=\"noindex\">")
        if (refreshSeconds > 0) append("<meta http-equiv=\"refresh\" content=\"").append(refreshSeconds).append("\">")
        append("<title>").append(escape(r.title.ifBlank { "Revisión" })).append(" · Trazo</title>")
        append("<style>").append(CSS).append("</style></head><body><main>")
        append("<h1>").append(escape(r.title.ifBlank { "Revisión" })).append("</h1>")
        append("<p class=\"sub\">Solo lectura · se actualiza sola")
        if (refreshSeconds > 0) append(" cada ").append(refreshSeconds).append(" s")
        append("</p>")
        append("<img class=\"page\" alt=\"La hoja con las marcas\" src=\"/").append(t).append("/page.png?t=").append(r.updatedAt).append("\">")
        if (r.notes.isNotEmpty()) {
            append("<h2>Marcas</h2><ol class=\"notes\">")
            for (n in r.notes) {
                append("<li><span class=\"tag\">Línea ").append(n.line).append(" · ").append(escape(n.kind)).append("</span> ")
                append("<b>").append(escape(n.short)).append("</b>")
                if (n.detail.isNotBlank()) append("<div class=\"txt\">").append(escape(n.detail)).append("</div>")
                if (n.fix.isNotBlank()) append("<div class=\"txt fix\">Arreglo: ").append(escape(n.fix)).append("</div>")
                append("</li>")
            }
            append("</ol>")
        }
        append("<h2>Chat de revisión</h2>")
        if (r.messages.isEmpty()) append("<p class=\"sub\">Todavía no hay mensajes.</p>")
        append("<div class=\"chat\">")
        for (m in r.messages) {
            val cls = when {
                m.error -> "msg err"
                m.fromStudent -> "msg me"
                else -> "msg tutor"
            }
            append("<div class=\"").append(cls).append("\"><div class=\"who\">").append(if (m.fromStudent) "Vos" else "Tutor")
            if (m.line != null) append(" · línea ").append(m.line)
            append("</div>")
            val verdict = m.verdict?.takeIf { it.isNotBlank() } ?: when (m.correct) {
                true -> "Correcto"
                false -> "Hay un error"
                null -> null
            }
            if (verdict != null) {
                append("<div class=\"verdict ").append(if (m.correct == false) "bad" else "ok").append("\">").append(escape(verdict)).append("</div>")
            }
            append("<div class=\"txt\">").append(escape(m.text)).append("</div></div>")
        }
        append("</div></main></body></html>")
    }

    private const val CSS =
        ":root{color-scheme:light dark;--bg:#f6f6f4;--fg:#1b1b1b;--mut:#666;--card:#fff;--me:#e3edff;--tu:#fff;--err:#ffe3e0;--ok:#1a7f37;--bad:#c62828;--line:#ddd}" +
            "@media (prefers-color-scheme:dark){:root{--bg:#121212;--fg:#eee;--mut:#aaa;--card:#1e1e1e;--me:#1d2b45;--tu:#1e1e1e;--err:#4a1f1c;--ok:#5fd17a;--bad:#ff8a80;--line:#333}}" +
            "body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.45 system-ui,sans-serif}" +
            "main{max-width:900px;margin:0 auto;padding:16px}h1{font-size:1.3em;margin:0}h2{font-size:1.1em;margin:24px 0 8px}" +
            ".sub{color:var(--mut);margin:4px 0 12px;font-size:.9em}" +
            ".page{display:block;width:100%;height:auto;background:#fff;border:1px solid var(--line);border-radius:8px}" +
            ".notes{padding-left:20px}.notes li{margin:8px 0}.tag{color:var(--mut);font-size:.85em}" +
            ".txt{white-space:pre-wrap;overflow-wrap:anywhere}.fix{color:var(--ok)}" +
            ".chat{display:flex;flex-direction:column;gap:10px}" +
            ".msg{border:1px solid var(--line);border-radius:12px;padding:10px 12px;max-width:92%}" +
            ".me{align-self:flex-end;background:var(--me)}.tutor{align-self:flex-start;background:var(--tu)}.err{background:var(--err)}" +
            ".who{font-size:.8em;color:var(--mut);margin-bottom:2px}.verdict{font-weight:600}.ok{color:var(--ok)}.bad{color:var(--bad)}"
}
