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
    /** Imagen que adjuntó el estudiante (nombre en la carpeta de imágenes), o null. */
    val image: String? = null,
)

/** Una charla guardada de una hoja. [id] es el momento de su primer mensaje. */
data class AiChatArchive(val id: String, val at: Long, val count: Int, val preview: String)

/**
 * La conversación del chat de revisión, guardada por página (un archivo JSON por hoja) para que siga ahí
 * al volver a abrirla. Se borra con la página o con «Nueva revisión».
 */
class AiChatStore(dir: File) {
    private val root = File(dir, "ai_chat")
    private val historyRoot = File(root, "historial")

    private fun file(notebookId: String, pageId: String) = File(root, "${safe(notebookId)}__${safe(pageId)}.json")

    private fun historyDir(notebookId: String, pageId: String) = File(historyRoot, "${safe(notebookId)}__${safe(pageId)}")

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun isValidArchiveId(id: String) = id.isNotEmpty() && id.length <= 16 && id.all { it.isDigit() }

    private fun loadFile(f: File): List<AiChatMessage> {
        if (!f.exists()) return emptyList()
        return runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { i -> fromJson(a.getJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    private fun writeAtomic(target: File, messages: List<AiChatMessage>) {
        val dir = target.parentFile ?: return
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        try {
            tmp.writeText(JSONArray(messages.takeLast(LIMIT).map(::toJson)).toString())
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        } catch (_: IOException) {
            tmp.delete()
        }
    }

    @Synchronized fun load(notebookId: String, pageId: String): List<AiChatMessage> =
        loadFile(file(notebookId, pageId))

    @Synchronized fun save(notebookId: String, pageId: String, messages: List<AiChatMessage>) {
        val f = file(notebookId, pageId)
        if (messages.isEmpty()) { f.delete(); return }
        writeAtomic(f, messages)
    }

    @Synchronized fun delete(notebookId: String, pageId: String) {
        file(notebookId, pageId).delete()
        historyDir(notebookId, pageId).deleteRecursively()
    }

    /** Guarda [messages] en el historial de la hoja; no hace nada si está vacía. */
    @Synchronized fun archive(notebookId: String, pageId: String, messages: List<AiChatMessage>) {
        if (messages.isEmpty()) return
        val dir = historyDir(notebookId, pageId).apply { mkdirs() }
        val id = messages.first().at.toString()
        writeAtomic(File(dir, "$id.json"), messages)
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedByDescending { it.name.removeSuffix(".json").toLongOrNull() ?: 0L }.drop(MAX_ARCHIVES).forEach { it.delete() }
    }

    /** Las charlas archivadas de esta hoja, la más nueva primero. */
    @Synchronized fun archives(notebookId: String, pageId: String): List<AiChatArchive> {
        val dir = historyDir(notebookId, pageId)
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        return files.mapNotNull { f ->
            val id = f.name.removeSuffix(".json")
            if (!isValidArchiveId(id)) return@mapNotNull null
            val messages = loadFile(f)
            if (messages.isEmpty()) null
            else {
                val at = messages.first().at
                val tutor = messages.firstOrNull { it.role == TutorRole.TUTOR }?.text
                val raw = (tutor ?: messages.first().text).replace(Regex("\\s+"), " ").trim()
                AiChatArchive(id = id, at = at, count = messages.size, preview = raw.take(120))
            }
        }.sortedByDescending { it.at }
    }

    /** Carga una charla archivada por su [id]; null si no existe. */
    @Synchronized fun loadArchive(notebookId: String, pageId: String, id: String): List<AiChatMessage>? {
        if (!isValidArchiveId(id)) return null
        val f = File(historyDir(notebookId, pageId), "$id.json")
        if (!f.isFile) return null
        val messages = loadFile(f)
        return messages.takeIf { it.isNotEmpty() }
    }

    /** Borra una charla archivada por su [id]. Devuelve true si existía y se borró. */
    @Synchronized fun deleteArchive(notebookId: String, pageId: String, id: String): Boolean {
        if (!isValidArchiveId(id)) return false
        val f = File(historyDir(notebookId, pageId), "$id.json")
        return if (f.isFile) f.delete() else false
    }

    /** Guarda la charla actual en el historial y deja [id] como actual. Devuelve los mensajes restaurados, o null si [id] no existe. */
    @Synchronized fun restore(notebookId: String, pageId: String, id: String): List<AiChatMessage>? {
        if (!isValidArchiveId(id)) return null
        val target = loadArchive(notebookId, pageId, id) ?: return null
        val current = load(notebookId, pageId)
        deleteArchive(notebookId, pageId, id)
        if (current.isNotEmpty()) archive(notebookId, pageId, current)
        save(notebookId, pageId, target)
        return target
    }

    /** Devuelve si alguna charla (actual o archivada) de esta hoja contiene una imagen adjunta con este [imageName]. */
    @Synchronized fun hasImage(notebookId: String, pageId: String, imageName: String): Boolean {
        if (imageName.isBlank()) return false
        val currentFile = file(notebookId, pageId)
        if (currentFile.isFile && currentFile.readText().contains(imageName)) return true
        val dir = historyDir(notebookId, pageId)
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        for (f in files) {
            if (f.isFile && f.readText().contains(imageName)) return true
        }
        return false
    }

    private fun toJson(m: AiChatMessage) = JSONObject().apply {
        put("role", m.role.name)
        put("text", m.text)
        put("at", m.at)
        m.line?.let { put("line", it) }
        m.correct?.let { put("correct", it) }
        m.verdict?.let { put("verdict", it) }
        if (m.strokeIds.isNotEmpty()) put("strokes", JSONArray(m.strokeIds))
        if (m.error) put("error", true)
        m.image?.let { put("image", it) }
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
        image = if (o.has("image")) o.optString("image") else null,
    )

    private companion object {
        const val LIMIT = 200
        const val MAX_ARCHIVES = 30
    }
}
