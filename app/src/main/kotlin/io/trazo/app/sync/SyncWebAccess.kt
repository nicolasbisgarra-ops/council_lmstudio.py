package io.trazo.app.sync

import android.content.Context
import io.trazo.core.share.WebAccess
import io.trazo.core.share.WebDevice
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * La web de revisión, a nivel de la app: mientras Trazo está abierto y el acceso está prendido, atiende a
 * los navegadores vinculados de la red de casa. No usa servicios ni permisos nuevos: se apaga con la app.
 */
class SyncWebAccess(private val app: Context) {
    data class UiState(
        val enabled: Boolean = false,
        val running: Boolean = false,
        val port: Int = 0,
        val addresses: List<String> = emptyList(),
        /** El código para vincular un navegador, o null si no hay uno vigente. */
        val code: String? = null,
        val codeExpiresAt: Long = 0L,
        val devices: List<WebDevice> = emptyList(),
        val problem: String? = null,
    )

    private val store = SyncWebStore(app.filesDir)
    private val access = WebAccess(store::load, store::save)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "trazo-web-control").apply { isDaemon = true } }
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    @Volatile private var host: SyncWebHost? = null
    private var server: SyncWebServer? = null
    private var problem: String? = null

    init {
        access.onChange = { worker.execute { publish() } }
    }

    /** La hoja abierta pasa a ser lo que ve la web. */
    fun attach(h: SyncWebHost) { host = h }

    fun detach(h: SyncWebHost) { if (host === h) host = null }

    /** Trazo volvió al frente: si el acceso está prendido, la web atiende. */
    fun resume() = worker.execute {
        if (access.config().enabled) startServer()
        publish()
    }

    /** Actualiza las direcciones (la red pudo cambiar). */
    fun refresh() = worker.execute { publish() }

    fun setEnabled(on: Boolean) = worker.execute {
        access.update { it.copy(enabled = on) }
        if (on) {
            startServer()
            if (server != null && access.code() == null) access.newCode()
        } else {
            access.cancelCode()
            server?.stop()
            server = null
            problem = null
        }
        publish()
    }

    /** Se abrió el diálogo para vincular: con el acceso prendido hay un código vigente para mostrar. */
    fun showCode() = worker.execute {
        if (access.config().enabled) {
            startServer()
            if (server != null && access.code() == null) access.newCode()
        }
        publish()
    }

    /** Un código nuevo; el anterior deja de servir. */
    fun newCode() = worker.execute {
        if (access.config().enabled && server?.isRunning == true) access.newCode()
        publish()
    }

    /** El código deja de servir (se cerró el diálogo). */
    fun cancelCode() = worker.execute { access.cancelCode() }

    fun forget(id: String) = worker.execute { access.forget(id); publish() }

    fun forgetAll() = worker.execute { access.forgetAll(); publish() }

    private fun startServer() {
        if (server?.isRunning == true) return
        server?.stop()
        server = null
        val saved = access.config().port
        val s = SyncWebServer(host = { host }, access = access, asset = ::asset)
        val wanted = if (saved in 1024..65535) saved else SyncWebServer.DEFAULT_PORT
        val port = runCatching { s.start(wanted) }.recoverCatching { s.start(0) }.getOrNull()
        if (port == null) {
            s.stop()
            problem = "No se pudo abrir la web en este equipo."
            return
        }
        server = s
        // El puerto queda fijo para que la dirección (y la app instalada en el navegador) no cambie.
        if (saved == 0) access.update { it.copy(port = port) }
        problem = if (saved != 0 && port != saved) "El puerto de siempre está ocupado: por ahora la dirección es otra." else null
    }

    private fun asset(name: String): ByteArray? = runCatching { app.assets.open("web/$name").use { it.readBytes() } }.getOrNull()

    private fun publish() {
        val c = access.config()
        val s = server?.takeIf { it.isRunning }
        val code = access.code()
        _state.value = UiState(
            enabled = c.enabled,
            running = s != null,
            port = s?.port ?: 0,
            addresses = if (s != null) localAddresses() else emptyList(),
            code = code?.code,
            codeExpiresAt = code?.expiresAt ?: 0L,
            devices = c.devices.sortedByDescending { it.seenAt },
            problem = problem,
        )
    }
}
