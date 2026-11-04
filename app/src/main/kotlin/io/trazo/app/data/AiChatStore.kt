package io.trazo.app.data

import io.trazo.ai.TutorRole
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Un mensaje del chat de revisión. [line] es el renglón del primer error que marcó el tutor (desde 1). */
data class AiChatMessage(
    val role: TutorRole,
    val text: String,
    val at: Long,
    val line: Int? = null,
    val correct: Boolean? = null,
    val verdict: String? = null,
    val strokeIds: List<String> = emptyList(),
    val error: Boolean = false,
)

/**
 * La conversación del chat de revisión, guardada por página (un archivo JSON por hoja) para que siga ahí
 * al volver a abrirla. Se borra con la página o con «Nueva revisión».
 */
class AiChatStore(dir: File) {
    private val root = File(dir, "ai_chat")

    private fun file(notebookId: String, pageId: String) = File(root, "${safe(notebookId)}__${safe(pageId)}.json")

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    @Synchronized fun load(notebookId: String, pageId: String): List<AiChatMessage> {
        val f = file(notebookId, pageId)
        if (!f.exists()) return emptyList()
        return runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { i -> fromJson(a.getJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun save(notebookId: String, pageId: String, messages: List<AiChatMessage>) {
        val f = file(notebookId, pageId)
        if (messages.isEmpty()) { f.delete(); return }
        root.mkdirs()
        val tmp = File(root, f.name + ".tmp")
        try {
            tmp.writeText(JSONArray(messages.takeLast(LIMIT).map(::toJson)).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: IOException) {
            tmp.delete()
        }
    }

    @Synchronized fun delete(notebookId: String, pageId: String) { file(notebookId, pageId).delete() }

    private fun toJson(m: AiChatMessage) = JSONObject().apply {
        put("role", m.role.name)
        put("text", m.text)
        put("at", m.at)
        m.line?.let { put("line", it) }
        m.correct?.let { put("correct", it) }
        m.verdict?.let { put("verdict", it) }
        if (m.strokeIds.isNotEmpty()) put("strokes", JSONArray(m.strokeIds))
        if (m.error) put("error", true)
    }

    private fun fromJson(o: JSONObject) = AiChatMessage(
        role = runCatching { TutorRole.valueOf(o.getString("role")) }.getOrDefault(TutorRole.TUTOR),
        text = o.optString("text"),
        at = o.optLong("at"),
        line = if (o.has("line")) o.optInt("line") else null,
        correct = if (o.has("correct")) o.optBoolean("correct") else null,
        verdict = if (o.has("verdict")) o.optString("verdict") else null,
        strokeIds = o.optJSONArray("strokes")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        error = o.optBoolean("error", false),
    )

    private companion object {
        const val LIMIT = 200
    }
}
