package io.trazo.app.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Un envío al modelo, para el registro (36): qué se mandó, cuándo, a qué modelo y cómo terminó. */
data class AiSend(
    val at: Long,
    val profile: String,
    val model: String,
    val notebook: String,
    val page: Int,
    val lines: Int,
    val imageBytes: Int,
    val selection: Boolean,
    val millis: Long,
    /** null = salió bien. */
    val error: String? = null,
    val marks: Int = 0,
    /** Así lo entendió el modelo (puntaje), si lo dio. */
    val score: Double? = null,
)

/** Un pedido que no salió por falta de conexión (37): se manda solo al volver la red, con la hoja abierta. */
data class AiQueued(val notebookId: String, val pageId: String, val strokeIds: List<String>?, val at: Long)

/**
 * Registro de envíos y cola sin conexión del Modo IA, en archivos JSON propios de la app. No guarda la
 * imagen ni el texto: solo lo necesario para saber qué salió del equipo.
 */
class AiJournal(dir: File) {
    private val logFile = File(dir, "ai_log.json")
    private val queueFile = File(dir, "ai_queue.json")

    private val _log = MutableStateFlow(readLog())
    val log: StateFlow<List<AiSend>> = _log.asStateFlow()
    private val _queue = MutableStateFlow(readQueue())
    val queue: StateFlow<List<AiQueued>> = _queue.asStateFlow()

    @Synchronized fun record(s: AiSend) {
        val list = (_log.value + s).takeLast(LOG_LIMIT)
        _log.value = list
        write(logFile, JSONArray(list.map { it.toJson() }))
    }

    @Synchronized fun clearLog() {
        _log.value = emptyList()
        logFile.delete()
    }

    @Synchronized fun enqueue(q: AiQueued) {
        val list = _queue.value.filterNot { it.notebookId == q.notebookId && it.pageId == q.pageId && it.strokeIds == q.strokeIds } + q
        setQueue(list)
    }

    /** Saca de la cola los pedidos de esa hoja y los devuelve. */
    @Synchronized fun take(notebookId: String, pageId: String): List<AiQueued> {
        val (mine, rest) = _queue.value.partition { it.notebookId == notebookId && it.pageId == pageId }
        if (mine.isNotEmpty()) setQueue(rest)
        return mine
    }

    fun pending(notebookId: String): Int = _queue.value.count { it.notebookId == notebookId }

    private fun setQueue(list: List<AiQueued>) {
        _queue.value = list
        write(queueFile, JSONArray(list.map { it.toJson() }))
    }

    /** Atómico: se escribe aparte, se fuerza al disco y se reemplaza de una vez; el archivo viejo nunca queda vacío. */
    private fun write(file: File, json: JSONArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(json.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("No se pudo reemplazar ${file.name}")
        } catch (e: Exception) {
            tmp.delete()
            Log.w("AiJournal", "No se guardó ${file.name}", e)
        }
    }

    private fun readLog(): List<AiSend> = readArray(logFile).mapNotNull { o ->
        runCatching {
            AiSend(
                at = o.getLong("at"), profile = o.optString("profile"), model = o.optString("model"),
                notebook = o.optString("notebook"), page = o.optInt("page"), lines = o.optInt("lines"),
                imageBytes = o.optInt("imageBytes"), selection = o.optBoolean("selection"), millis = o.optLong("millis"),
                error = o.optString("error").takeIf { o.has("error") }, marks = o.optInt("marks"),
                score = if (o.has("score")) o.getDouble("score") else null,
            )
        }.getOrNull()
    }

    private fun readQueue(): List<AiQueued> = readArray(queueFile).mapNotNull { o ->
        runCatching {
            val ids = o.optJSONArray("strokeIds")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            AiQueued(o.getString("notebookId"), o.getString("pageId"), ids, o.optLong("at"))
        }.getOrNull()
    }

    private fun readArray(file: File): List<JSONObject> = runCatching {
        if (!file.exists()) return emptyList()
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun AiSend.toJson() = JSONObject().apply {
        put("at", at); put("profile", profile); put("model", model); put("notebook", notebook); put("page", page)
        put("lines", lines); put("imageBytes", imageBytes); put("selection", selection); put("millis", millis)
        error?.let { put("error", it) }
        put("marks", marks)
        score?.let { put("score", it) }
    }

    private fun AiQueued.toJson() = JSONObject().apply {
        put("notebookId", notebookId); put("pageId", pageId); put("at", at)
        strokeIds?.let { put("strokeIds", JSONArray(it)) }
    }

    private companion object {
        const val LOG_LIMIT = 200
    }
}
