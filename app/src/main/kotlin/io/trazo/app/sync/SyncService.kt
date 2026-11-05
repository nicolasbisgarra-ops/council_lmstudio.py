package io.trazo.app.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.trazo.app.data.TrazoRepository
import io.trazo.core.sync.PairingCode
import io.trazo.core.sync.PairingQr
import io.trazo.core.sync.SecureChannel
import io.trazo.core.sync.SyncAddresses
import io.trazo.core.sync.SyncAuthException
import io.trazo.core.sync.SyncBusyException
import io.trazo.core.sync.SyncConfig
import io.trazo.core.sync.SyncEngine
import io.trazo.core.sync.SyncMessage
import io.trazo.core.sync.SyncPeer
import io.trazo.core.sync.SyncProtocol
import io.trazo.core.sync.SyncReport
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Un dispositivo vinculado y cómo le fue en la última vuelta. */
data class SyncPeerStatus(
    val peer: SyncPeer,
    /** Se lo vio en la red de casa (NSD) en esta sesión. */
    val nearby: Boolean = false,
    val running: Boolean = false,
    val message: String? = null,
)

/** Código de vinculación visible en este dispositivo. */
data class SyncPairing(val code: String, val expiresAt: Long)

data class SyncUiState(
    val config: SyncConfig = SyncConfig(deviceId = ""),
    val deviceName: String = "",
    val peers: List<SyncPeerStatus> = emptyList(),
    /** Direcciones de este dispositivo que el otro puede usar (LAN y Tailscale). */
    val addresses: List<String> = emptyList(),
    val pairing: SyncPairing? = null,
    val linking: Boolean = false,
    val message: String? = null,
)

/**
 * Sincronización nativa entre los dispositivos del usuario, sin servidores: cada Trazo atiende en el
 * puerto [SyncProtocol.PORT] y se encuentra con los otros en la red de casa (NSD) o por las direcciones
 * guardadas al vincular (también las de Tailscale). Solo se aceptan y se usan direcciones privadas o de
 * Tailscale ([SyncAddresses]); todo va cifrado con la clave que comparten los dos ([SecureChannel]).
 * Mientras Trazo está abierto se sincroniza cada minuto, al cerrar un cuaderno y con «Sincronizar ahora».
 */
class SyncService(context: Context, private val repo: TrazoRepository) {
    private val app = context.applicationContext
    private val store = SyncPeerStore(app.filesDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Una vuelta a la vez, como cliente o como servidor. */
    private val busy = ReentrantLock()
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    @Volatile private var server: ServerSocket? = null
    private val connections = AtomicInteger()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var foreground = false
    private var loop: Job? = null

    @Volatile private var pairingKey: ByteArray? = null
    @Volatile private var pairingExpires = 0L
    private val pairingFailures = AtomicInteger()

    private val nsd: NsdManager? = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var registered: NsdManager.RegistrationListener? = null
    private var discovering: NsdManager.DiscoveryListener? = null
    /** deviceId → dirección vista por NSD. */
    private val nearby = ConcurrentHashMap<String, String>()
    /** Servicios de Trazo vistos que todavía no están vinculados: nombre → dirección (para vincular). */
    private val unpaired = ConcurrentHashMap<String, String>()
    private val serviceIds = ConcurrentHashMap<String, String>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val messages = ConcurrentHashMap<String, String>()
    private val running = ConcurrentHashMap.newKeySet<String>()

    private val engine by lazy { SyncEngine(SyncRepositoryStore(repo, store, store.config().deviceId)) }

    init {
        scope.launch {
            refresh()
            if (store.config().enabled) start()
        }
    }

    // ------------------------------------------------------------------ lo que usa la interfaz

    fun setEnabled(on: Boolean) = scope.launch {
        store.saveConfig(store.config().copy(enabled = on))
        if (on) start() else stop()
        refresh()
    }

    fun setName(name: String) = scope.launch {
        store.saveConfig(store.config().copy(name = name.trim().take(40)))
        refresh()
    }

    /** La app pasó a primer plano o a segundo: solo se busca y se sincroniza en primer plano. */
    fun setForeground(on: Boolean) {
        foreground = on
        scope.launch {
            if (!store.config().enabled) return@launch
            if (on) { startDiscovery(); requestSync() } else stopDiscovery()
        }
    }

    fun requestSync() { wake.trySend(Unit) }

    fun syncNow() = scope.launch { syncAll(manual = true) }

    /** Muestra un código por 5 minutos para que el otro dispositivo se vincule. */
    fun showCode() = scope.launch {
        if (!store.config().enabled) { store.saveConfig(store.config().copy(enabled = true)); start() }
        val code = PairingCode.generate()
        pairingKey = PairingCode.key(code)
        pairingExpires = System.currentTimeMillis() + PAIRING_MILLIS
        pairingFailures.set(0)
        _state.update { it.copy(pairing = SyncPairing(code, pairingExpires), message = null) }
        delay(PAIRING_MILLIS)
        if (_state.value.pairing?.code == code) cancelCode("El código venció.")
    }

    fun cancelCode(message: String? = null) {
        pairingKey = null
        _state.update { it.copy(pairing = null, message = message) }
    }

    /** Se vincula con el que muestra [code]; sin [address] lo busca en la red de casa. */
    fun link(code: String, address: String) = link(code, listOf(address.trim()).filter { it.isNotEmpty() }, discover = false)

    /** Lo leído del QR del otro: prueba sus direcciones (casa o Tailscale) y, si ninguna responde, la red de casa. */
    fun linkScanned(text: String): Boolean {
        val p = PairingQr.decode(text) ?: run {
            _state.update { it.copy(message = "Ese QR no es de Trazo. Escaneá el que aparece en «Mostrar código».") }
            return false
        }
        link(p.code, p.addresses, discover = true)
        return true
    }

    private fun link(code: String, addresses: List<String>, discover: Boolean) = scope.launch {
        val normalized = PairingCode.normalize(code)
        if (normalized == null) { _state.update { it.copy(message = "El código tiene 10 letras y números, como ABCDE-FGH23.") }; return@launch }
        if (!store.config().enabled) { store.saveConfig(store.config().copy(enabled = true)); start() }
        _state.update { it.copy(linking = true, message = "Buscando el otro dispositivo…") }
        try {
            val key = PairingCode.key(normalized)
            suspend fun searched(): List<String> {
                startDiscovery()
                // Se da un momento a la búsqueda en la red de casa.
                repeat(8) { if (unpaired.isEmpty() && nearby.isEmpty()) delay(500) }
                return (unpaired.values + nearby.values).distinct()
            }
            val first = addresses.ifEmpty { searched() }
            if (first.isEmpty()) {
                _state.update { it.copy(message = "No se encontró otro Trazo en esta red. Escribí su dirección (la de Tailscale sirve).") }
                return@launch
            }
            val tried = HashSet<String>()
            suspend fun tryHosts(hosts: List<String>): Boolean {
                for (host in hosts) for (a in resolve(host)) {
                    if (!tried.add(a.hostAddress.orEmpty())) continue
                    val peer = runCatching { pairWith(a, key) }.getOrNull() ?: continue
                    _state.update { it.copy(message = "Vinculado con ${peer.name}.") }
                    refresh()
                    requestSync()
                    return true
                }
                return false
            }
            if (tryHosts(first)) return@launch
            if (discover && addresses.isNotEmpty() && tryHosts(searched())) return@launch
            _state.update { it.copy(message = "No se pudo vincular: revisá el código (vence a los 5 minutos) y que los dos estén en la misma red o en Tailscale.") }
        } finally {
            _state.update { it.copy(linking = false) }
        }
    }

    fun forget(id: String) = scope.launch {
        store.forget(id)
        messages.remove(id)
        refresh()
    }

    // ------------------------------------------------------------------ servidor

    private fun start() {
        startServer()
        register()
        if (foreground) startDiscovery()
        if (loop == null) loop = scope.launch {
            while (isActive) {
                withTimeoutOrNull(PERIOD_MILLIS) { wake.receive() }
                if (foreground && store.config().enabled) syncAll(manual = false)
            }
        }
    }

    private fun stop() {
        runCatching { server?.close() }
        server = null
        unregister()
        stopDiscovery()
        cancelCode()
    }

    private fun startServer() {
        if (server != null) return
        val ss = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(SyncProtocol.PORT)) }
        } catch (e: IOException) {
            _state.update { it.copy(message = "No se pudo abrir el puerto ${SyncProtocol.PORT}: ${e.message}") }
            return
        }
        server = ss
        Thread({ acceptLoop(ss) }, "trazo-sync-server").apply { isDaemon = true; start() }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (_: IOException) { break }
            // Solo la red de casa y Tailscale; nunca una dirección pública.
            if (!SyncAddresses.isAllowed(s.inetAddress.address) || connections.get() >= MAX_CONNECTIONS) {
                runCatching { s.close() }
                continue
            }
            connections.incrementAndGet()
            Thread({ try { handle(s) } finally { connections.decrementAndGet() } }, "trazo-sync-conn").apply { isDaemon = true; start() }
        }
    }

    private fun handle(socket: Socket) = socket.use { s ->
        s.soTimeout = READ_TIMEOUT
        s.tcpNoDelay = true
        val cfg = store.config()
        val ch = try {
            SecureChannel.server(BufferedInputStream(s.getInputStream()), BufferedOutputStream(s.getOutputStream()), cfg.deviceId) { mode, id ->
                when (mode) {
                    SecureChannel.Mode.SYNC -> if (cfg.enabled) store.peers().firstOrNull { it.deviceId == id }?.let { decodeKey(it.key) } else null
                    SecureChannel.Mode.PAIR -> pairingKey?.takeIf { System.currentTimeMillis() < pairingExpires }
                }
            }
        } catch (_: IOException) { return@use }
        when (ch.mode) {
            SecureChannel.Mode.PAIR -> answerPairing(ch, s)
            SecureChannel.Mode.SYNC -> {
                if (!busy.tryLock()) {
                    runCatching { ch.receive(); ch.send(SyncMessage.Busy) }
                    return@use
                }
                val peer = ch.peerId
                try {
                    setRunning(peer, true)
                    engine.serve(ch)
                    synced(peer, s.inetAddress.hostAddress, null)
                } catch (e: Exception) {
                    if (e !is SyncAuthException) messages[peer] = "Se cortó la conexión."
                } finally {
                    busy.unlock()
                    setRunning(peer, false)
                }
            }
        }
    }

    private fun answerPairing(ch: SecureChannel, s: Socket) {
        val hello = try { ch.receiveMessage() as? SyncMessage.PairHello } catch (_: SyncAuthException) {
            if (pairingFailures.incrementAndGet() >= MAX_PAIRING_FAILURES) cancelCode("Demasiados intentos con un código equivocado. Generá otro.")
            null
        } catch (_: Exception) { null } ?: return
        val cfg = store.config()
        if (hello.deviceId == cfg.deviceId) return
        val key = SecureChannel.newKey()
        val encoded = Base64.getEncoder().encodeToString(key)
        runCatching { ch.send(SyncMessage.PairWelcome(cfg.deviceId, deviceName(cfg), myAddresses(), encoded)) }.onFailure { return }
        val addresses = (listOf(s.inetAddress.hostAddress.orEmpty()) + hello.addresses).filter { it.isNotBlank() }.distinct().take(MAX_ADDRESSES)
        store.savePeer(SyncPeer(hello.deviceId, hello.name.take(40).ifBlank { "Dispositivo" }, encoded, addresses))
        cancelCode("Vinculado con ${hello.name}.")
        scope.launch { refresh() }
        requestSync()
    }

    // ------------------------------------------------------------------ cliente

    private fun pairWith(address: InetAddress, key: ByteArray): SyncPeer? {
        val cfg = store.config()
        connect(address).use { s ->
            val ch = SecureChannel.client(BufferedInputStream(s.getInputStream()), BufferedOutputStream(s.getOutputStream()), cfg.deviceId, SecureChannel.Mode.PAIR, key)
            ch.send(SyncMessage.PairHello(cfg.deviceId, deviceName(cfg), myAddresses()))
            val w = ch.receiveMessage() as? SyncMessage.PairWelcome ?: return null
            if (w.deviceId == cfg.deviceId || runCatching { decodeKey(w.key) }.getOrNull()?.size != 32) return null
            val addresses = (listOf(address.hostAddress.orEmpty()) + w.addresses).filter { it.isNotBlank() }.distinct().take(MAX_ADDRESSES)
            val peer = SyncPeer(w.deviceId, w.name.take(40).ifBlank { "Dispositivo" }, w.key, addresses)
            store.savePeer(peer)
            return peer
        }
    }

    private suspend fun syncAll(manual: Boolean) {
        val peers = store.peers()
        if (peers.isEmpty()) return
        // Si los dos dispositivos arrancan a la vez, uno se adelanta.
        if (!manual) delay(Random.nextLong(0, 1_500))
        if (!busy.tryLock()) return
        try {
            for (p in peers) syncPeer(p)
        } finally {
            busy.unlock()
        }
        refresh()
    }

    private fun syncPeer(peer: SyncPeer) {
        val cfg = store.config()
        val key = decodeKey(peer.key)
        setRunning(peer.deviceId, true)
        try {
            val hosts = (listOfNotNull(nearby[peer.deviceId]) + peer.addresses).distinct()
            for (host in hosts) for (a in resolve(host)) {
                try {
                    connect(a).use { s ->
                        val ch = SecureChannel.client(BufferedInputStream(s.getInputStream()), BufferedOutputStream(s.getOutputStream()), cfg.deviceId, SecureChannel.Mode.SYNC, key)
                        if (ch.peerId != peer.deviceId) throw SyncAuthException("Otro dispositivo")
                        val report = engine.runClient(ch)
                        synced(peer.deviceId, a.hostAddress, report)
                        return
                    }
                } catch (_: SyncBusyException) {
                    messages[peer.deviceId] = "Ocupado; se reintenta en un minuto."
                    return
                } catch (e: IllegalStateException) {
                    messages[peer.deviceId] = e.message ?: "No se pudo sincronizar."
                    return
                } catch (_: IOException) {
                    continue
                }
            }
            messages[peer.deviceId] = "No está en la misma red ni en Tailscale."
        } finally {
            setRunning(peer.deviceId, false)
        }
    }

    private fun synced(peerId: String, address: String?, report: SyncReport?) {
        store.update(peerId) { p ->
            p.copy(
                lastSync = System.currentTimeMillis(),
                addresses = (listOfNotNull(address?.takeIf { it.isNotBlank() }) + p.addresses).distinct().take(MAX_ADDRESSES),
            )
        }
        messages[peerId] = when {
            report == null -> "Al día."
            report.conflicts > 0 -> "Al día. ${report.conflicts} hoja(s) cambiadas en los dos quedaron duplicadas para que elijas."
            report.deferred > 0 -> "Al día, salvo ${report.deferred} cuaderno(s) abiertos: siguen al cerrarlos."
            else -> "Al día."
        }
    }

    private fun connect(a: InetAddress): Socket {
        if (!SyncAddresses.isAllowed(a.address)) throw IOException("Dirección fuera de la red de casa y Tailscale")
        val s = Socket()
        try {
            s.connect(InetSocketAddress(a, SyncProtocol.PORT), CONNECT_TIMEOUT)
            s.soTimeout = READ_TIMEOUT
            s.tcpNoDelay = true
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
        return s
    }

    /** Las direcciones permitidas de un nombre o IP (un nombre de MagicDNS de Tailscale también sirve). */
    private fun resolve(host: String): List<InetAddress> {
        val clean = host.trim().removePrefix("[").substringBefore("]").let { h ->
            // «100.64.1.2:47821» o «nombre:47821»: el puerto es fijo, se ignora.
            if (h.count { it == ':' } == 1) h.substringBefore(':') else h
        }
        if (clean.isEmpty()) return emptyList()
        return runCatching { InetAddress.getAllByName(clean).toList() }.getOrDefault(emptyList())
            .filter { SyncAddresses.isAllowed(it.address) }
            .sortedBy { if (it.address.size == 4) 0 else 1 }
    }

    // ------------------------------------------------------------------ red de casa (NSD)

    private fun register() {
        val m = nsd ?: return
        if (registered != null) return
        val cfg = store.config()
        val info = NsdServiceInfo().apply {
            serviceName = "Trazo " + deviceName(cfg)
            serviceType = SyncProtocol.SERVICE_TYPE
            port = SyncProtocol.PORT
            setAttribute("id", cfg.deviceId)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { registered = null }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        registered = l
        runCatching { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { registered = null }
    }

    private fun unregister() {
        val l = registered ?: return
        registered = null
        runCatching { nsd?.unregisterService(l) }
    }

    @Synchronized
    private fun startDiscovery() {
        val m = nsd ?: return
        if (discovering != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discovering = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { enqueueResolve(info) }
            override fun onServiceLost(info: NsdServiceInfo) {
                unpaired.remove(info.serviceName)
                serviceIds.remove(info.serviceName)?.let { nearby.remove(it) }
                scope.launch { refresh() }
            }
        }
        discovering = l
        runCatching { m.discoverServices(SyncProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { discovering = null }
    }

    @Synchronized
    private fun stopDiscovery() {
        val l = discovering ?: return
        discovering = null
        runCatching { nsd?.stopServiceDiscovery(l) }
    }

    @Synchronized
    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.addLast(info)
        resolveNext()
    }

    /** NsdManager resuelve de a un servicio por vez. */
    @Synchronized
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val m = nsd ?: return
        if (resolving) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val l = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()
            override fun onServiceResolved(info: NsdServiceInfo) {
                val id = info.attributes["id"]?.toString(Charsets.UTF_8)
                val host = info.host
                val mine = store.config().deviceId
                if (id != null && id != mine && host != null && SyncAddresses.isAllowed(host.address)) {
                    val addr = host.hostAddress.orEmpty()
                    serviceIds[info.serviceName] = id
                    if (store.peers().any { it.deviceId == id }) nearby[id] = addr else unpaired[info.serviceName] = addr
                    scope.launch { refresh() }
                    requestSync()
                }
                done()
            }
            fun done() {
                synchronized(this@SyncService) { resolving = false }
                resolveNext()
            }
        }
        runCatching { m.resolveService(next, l) }.onFailure { resolving = false }
    }

    // ------------------------------------------------------------------ estado

    private fun setRunning(peer: String, on: Boolean) {
        if (on) running += peer else running -= peer
        scope.launch { refresh() }
    }

    private fun refresh() {
        val cfg = store.config()
        val peers = store.peers().map { p ->
            SyncPeerStatus(p, nearby.containsKey(p.deviceId), p.deviceId in running, messages[p.deviceId])
        }
        _state.update { it.copy(config = cfg, deviceName = deviceName(cfg), peers = peers, addresses = myAddresses()) }
    }

    private fun deviceName(cfg: SyncConfig) = cfg.name.ifBlank { Build.MODEL ?: "Trazo" }

    /** Direcciones de este dispositivo en la red de casa o Tailscale (sin las de enlace local). */
    private fun myAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { SyncAddresses.isAllowed(it.address) && !it.isLinkLocalAddress }
            .sortedWith(compareBy({ if (SyncAddresses.isTailscale(it.address)) 1 else 0 }, { if (it.address.size == 4) 0 else 1 }))
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .distinct()
    }.getOrDefault(emptyList())

    private fun decodeKey(b64: String): ByteArray = Base64.getDecoder().decode(b64)

    private companion object {
        const val PERIOD_MILLIS = 60_000L
        const val PAIRING_MILLIS = 5 * 60_000L
        const val MAX_PAIRING_FAILURES = 5
        const val CONNECT_TIMEOUT = 3_000
        const val READ_TIMEOUT = 30_000
        const val MAX_CONNECTIONS = 4
        const val MAX_ADDRESSES = 6
    }
}
