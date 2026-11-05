package io.trazo.app.sync

import android.bluetooth.BluetoothServerSocket
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
    /** El equipo tiene Bluetooth. */
    val bluetoothAvailable: Boolean = false,
    /** Bluetooth prendido y con permiso. */
    val bluetoothReady: Boolean = false,
    /** Emparejados en los ajustes de Android (para vincular por Bluetooth). */
    val bluetoothDevices: List<BluetoothPeer> = emptyList(),
    /** El equipo tiene Google Play Services (para «Cerca, sin red»). */
    val nearbyAvailable: Boolean = false,
    /** Con los permisos de «Dispositivos cercanos». */
    val nearbyReady: Boolean = false,
    /** Cuántos Trazo se ven cerca ahora. */
    val nearbySeen: Int = 0,
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
    private val bt = BluetoothLink(app)
    @Volatile private var btServer: BluetoothServerSocket? = null
    private val near = NearbyLink(
        app,
        myDeviceId = { store.config().deviceId },
        myName = { deviceName(store.config()) },
        onIncoming = { c -> serve(c, ip = null, btAddr = null) },
        onChange = { scope.launch { refresh() }; requestSync() },
    )
    /** El último motivo por el que falló un intento de vincular (para decirlo al final). */
    @Volatile private var lastPairError: String? = null
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

    private val engine by lazy { SyncEngine(SyncRepositoryStore(repo, store, store.config().deviceId), addresses = { myAddresses() }) }

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

    /** Usar también Bluetooth con los emparejados en Android (cuando no hay red de casa ni Tailscale). */
    fun setBluetooth(on: Boolean) = scope.launch {
        store.saveConfig(store.config().copy(bluetooth = on))
        if (on && store.config().enabled) startBtServer() else stopBtServer()
        refresh()
    }

    /** «Cerca, sin red» con Nearby (sin router, internet ni Tailscale). */
    fun setNearby(on: Boolean) = scope.launch {
        store.saveConfig(store.config().copy(nearby = on))
        if (on && store.config().enabled && foreground) near.start() else if (!on) near.stop()
        refresh()
    }

    /** La app pasó a primer plano o a segundo: solo se busca y se sincroniza en primer plano. */
    fun setForeground(on: Boolean) {
        foreground = on
        scope.launch {
            val cfg = store.config()
            if (!cfg.enabled) return@launch
            if (on) {
                startBtServer()
                if (cfg.nearby) near.start()
                refresh(); startDiscovery(); requestSync()
            } else {
                stopDiscovery()
                near.stop()
            }
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

    /** Se vincula por Bluetooth con [address], un emparejado en los ajustes de Android. */
    fun linkBluetooth(code: String, address: String) = link(code, emptyList(), discover = false, bluetooth = address)

    /** Lo leído del QR del otro: prueba sus direcciones (casa o Tailscale) y, si ninguna responde, la red de casa. */
    fun linkScanned(text: String): Boolean {
        val p = PairingQr.decode(text) ?: run {
            _state.update { it.copy(message = "Ese QR no es de Trazo. Escaneá el que aparece en «Mostrar código».") }
            return false
        }
        link(p.code, p.addresses, discover = true)
        return true
    }

    private fun link(code: String, addresses: List<String>, discover: Boolean, bluetooth: String? = null) = scope.launch {
        val normalized = PairingCode.normalize(code)
        if (normalized == null) { _state.update { it.copy(message = "El código tiene 10 letras y números, como ABCDE-FGH23.") }; return@launch }
        if (!store.config().enabled) { store.saveConfig(store.config().copy(enabled = true)); start() }
        _state.update { it.copy(linking = true, message = "Buscando el otro dispositivo…") }
        try {
            val key = PairingCode.key(normalized)
            lastPairError = null
            suspend fun tryBluetooth(devices: List<String>): Boolean {
                for (mac in devices) {
                    _state.update { it.copy(message = "Probando por Bluetooth con ${bt.nameOf(mac) ?: mac}…") }
                    val peer = attempt { bt.connect(mac).use { pair(it, key, ip = null, btAddr = mac) } } ?: continue
                    _state.update { it.copy(message = "Vinculado con ${peer.name} por Bluetooth.") }
                    refresh()
                    requestSync()
                    return true
                }
                return false
            }
            if (bluetooth != null) {
                if (!bt.ready()) { _state.update { it.copy(message = "Prendé el Bluetooth y dale permiso a Trazo.") }; return@launch }
                if (tryBluetooth(listOf(bluetooth))) return@launch
                val why = lastPairError?.let { " ($it)" }.orEmpty()
                _state.update { it.copy(message = "No se pudo vincular por Bluetooth$why: revisá el código y que el otro tenga Trazo abierto con «Usar también Bluetooth».") }
                return@launch
            }
            suspend fun searched(): List<String> {
                startDiscovery()
                // Se da un momento a la búsqueda en la red de casa.
                repeat(8) { if (unpaired.isEmpty() && nearby.isEmpty()) delay(500) }
                return (unpaired.values + nearby.values).distinct()
            }
            val tried = HashSet<String>()
            // «Cerca, sin red» primero: no depende del router ni de Tailscale.
            val viaNearby = store.config().nearby && near.ready()
            if (viaNearby) {
                near.start()
                _state.update { it.copy(message = "Buscando cerca, sin red…") }
                val paired = store.peers().map { it.deviceId }.toSet()
                repeat(16) { if (near.seen().none { it.deviceId !in paired }) delay(500) }
                for (e in near.seen().filter { it.deviceId !in paired }) {
                    tried += "nearby:" + e.endpointId
                    _state.update { it.copy(message = "Probando cerca con ${e.name}…") }
                    val peer = attempt { near.connect(e.endpointId).use { pair(it, key, ip = null, btAddr = null) } } ?: continue
                    _state.update { it.copy(message = "Vinculado con ${peer.name} (cerca, sin red).") }
                    refresh()
                    requestSync()
                    return@launch
                }
            }
            val first = addresses.ifEmpty { searched() }
            val viaBluetooth = store.config().bluetooth && bt.ready()
            if (first.isEmpty() && !viaBluetooth && !viaNearby) {
                _state.update { it.copy(message = "No se encontró otro Trazo en esta red. Activá «Cerca, sin red» en los dos, o escribí su dirección (la de Tailscale sirve).") }
                return@launch
            }
            suspend fun tryHosts(hosts: List<String>): Boolean {
                for (host in hosts) for (a in resolve(host)) {
                    if (!tried.add(a.hostAddress.orEmpty())) continue
                    _state.update { it.copy(message = "Probando ${a.hostAddress?.substringBefore('%')}…") }
                    val peer = attempt { pairWith(a, key) } ?: continue
                    _state.update { it.copy(message = "Vinculado con ${peer.name}.") }
                    refresh()
                    requestSync()
                    return true
                }
                return false
            }
            if (tryHosts(first)) return@launch
            if (discover && addresses.isNotEmpty() && tryHosts(searched())) return@launch
            if (viaBluetooth && tryBluetooth(bt.likely().map { it.address })) return@launch
            val why = lastPairError?.let { " ($it)" }.orEmpty()
            _state.update {
                it.copy(message = "No se pudo vincular$why. Revisá que el otro esté mostrando el código (vence a los 5 minutos) y " +
                    if (viaNearby) "que tenga «Cerca, sin red» activado y Trazo abierto." else "probá «Cerca, sin red» en los dos: no necesita red.")
            }
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
        startBtServer()
        register()
        if (foreground) startDiscovery()
        if (foreground && store.config().nearby) near.start()
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
        stopBtServer()
        near.stop()
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

    /** Atiende por Bluetooth (RFCOMM) si está activado, prendido y con permiso. */
    @Synchronized
    private fun startBtServer() {
        if (btServer != null || !store.config().enabled || !store.config().bluetooth) return
        val ss = bt.listen() ?: return
        btServer = ss
        Thread({
            while (true) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                if (connections.get() >= MAX_CONNECTIONS) { runCatching { s.close() }; continue }
                connections.incrementAndGet()
                Thread({
                    try {
                        bt.guard(s).use { c -> serve(c, ip = null, btAddr = runCatching { s.remoteDevice.address }.getOrNull()) }
                    } finally {
                        connections.decrementAndGet()
                    }
                }, "trazo-bt-conn").apply { isDaemon = true; start() }
            }
            // Bluetooth apagado o servidor cerrado: se vuelve a abrir al volver a primer plano.
            synchronized(this@SyncService) { if (btServer === ss) btServer = null }
            runCatching { ss.close() }
        }, "trazo-bt-server").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun stopBtServer() {
        val ss = btServer ?: return
        btServer = null
        runCatching { ss.close() }
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
        serve(tcp(s), ip = s.inetAddress.hostAddress, btAddr = null)
    }

    /** Atiende una conexión entrante, por la red ([ip]) o por Bluetooth ([btAddr]). */
    private fun serve(conn: SyncConn, ip: String?, btAddr: String?) {
        val cfg = store.config()
        val ch = try {
            SecureChannel.server(conn.input, conn.output, cfg.deviceId) { mode, id ->
                when (mode) {
                    SecureChannel.Mode.SYNC -> if (cfg.enabled) store.peers().firstOrNull { it.deviceId == id }?.let { decodeKey(it.key) } else null
                    SecureChannel.Mode.PAIR -> pairingKey?.takeIf { System.currentTimeMillis() < pairingExpires }
                }
            }
        } catch (_: IOException) { return }
        when (ch.mode) {
            SecureChannel.Mode.PAIR -> answerPairing(ch, ip, btAddr)
            SecureChannel.Mode.SYNC -> {
                if (!busy.tryLock()) {
                    runCatching { ch.receive(); ch.send(SyncMessage.Busy) }
                    return
                }
                val peer = ch.peerId
                try {
                    setRunning(peer, true)
                    engine.serve(ch)
                    synced(peer, ip, btAddr, null)
                } catch (e: Exception) {
                    if (e !is SyncAuthException) messages[peer] = "Se cortó la conexión."
                } finally {
                    busy.unlock()
                    setRunning(peer, false)
                }
            }
        }
    }

    private fun answerPairing(ch: SecureChannel, ip: String?, btAddr: String?) {
        val hello = try { ch.receiveMessage() as? SyncMessage.PairHello } catch (_: SyncAuthException) {
            if (pairingFailures.incrementAndGet() >= MAX_PAIRING_FAILURES) cancelCode("Demasiados intentos con un código equivocado. Generá otro.")
            null
        } catch (_: Exception) { null } ?: return
        val cfg = store.config()
        if (hello.deviceId == cfg.deviceId) return
        val key = SecureChannel.newKey()
        val encoded = Base64.getEncoder().encodeToString(key)
        runCatching { ch.send(SyncMessage.PairWelcome(cfg.deviceId, deviceName(cfg), myAddresses(), encoded)) }.onFailure { return }
        val addresses = (listOfNotNull(ip) + hello.addresses).filter { it.isNotBlank() }.distinct().take(MAX_ADDRESSES)
        store.savePeer(SyncPeer(hello.deviceId, hello.name.take(40).ifBlank { "Dispositivo" }, encoded, addresses, bluetooth = btAddr))
        cancelCode("Vinculado con ${hello.name}.")
        scope.launch { refresh() }
        requestSync()
    }

    // ------------------------------------------------------------------ cliente

    /** Un intento de vincular; si falla, guarda el motivo en palabras para el mensaje final. */
    private inline fun attempt(block: () -> SyncPeer?): SyncPeer? = try {
        block().also { if (it == null) lastPairError = "el otro respondió algo inesperado" }
    } catch (e: Exception) {
        lastPairError = when (e) {
            is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.NoRouteToHostException -> "el otro no responde"
            is java.io.EOFException, is SyncAuthException -> "el otro cortó: código equivocado o no lo está mostrando"
            is IOException -> e.message?.take(80) ?: "se cortó la conexión"
            else -> e.message?.take(80) ?: e.javaClass.simpleName
        }
        null
    }

    private fun pairWith(address: InetAddress, key: ByteArray): SyncPeer? =
        tcp(connect(address)).use { pair(it, key, ip = address.hostAddress, btAddr = null) }

    private fun pair(conn: SyncConn, key: ByteArray, ip: String?, btAddr: String?): SyncPeer? {
        val cfg = store.config()
        val ch = SecureChannel.client(conn.input, conn.output, cfg.deviceId, SecureChannel.Mode.PAIR, key)
        ch.send(SyncMessage.PairHello(cfg.deviceId, deviceName(cfg), myAddresses()))
        val w = ch.receiveMessage() as? SyncMessage.PairWelcome ?: return null
        if (w.deviceId == cfg.deviceId || runCatching { decodeKey(w.key) }.getOrNull()?.size != 32) return null
        val addresses = (listOfNotNull(ip) + w.addresses).filter { it.isNotBlank() }.distinct().take(MAX_ADDRESSES)
        val peer = SyncPeer(w.deviceId, w.name.take(40).ifBlank { "Dispositivo" }, w.key, addresses, bluetooth = btAddr)
        store.savePeer(peer)
        return peer
    }

    private fun tcp(s: Socket) = SyncConn(BufferedInputStream(s.getInputStream()), BufferedOutputStream(s.getOutputStream()), s)

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
            // Primero la red (casa o Tailscale); si no responde, Bluetooth. El segundo valor es la IP usada.
            val targets = ArrayList<() -> Pair<SyncConn, String?>>()
            for (h in hosts) for (a in resolve(h)) targets += { tcp(connect(a)) to a.hostAddress }
            val ep = if (cfg.nearby) near.endpointFor(peer.deviceId) else null
            // Visto cerca y no en la red de casa: Nearby primero (las direcciones guardadas pueden tardar en fallar).
            if (ep != null) {
                val viaNearby: () -> Pair<SyncConn, String?> = { near.connect(ep.endpointId) to NEARBY }
                targets.add(if (nearby[peer.deviceId] == null) 0 else targets.size, viaNearby)
            }
            val mac = peer.bluetooth
            if (mac != null && cfg.bluetooth && bt.ready()) targets += { bt.connect(mac) to null }
            for (open in targets) {
                try {
                    val (conn, ip) = open()
                    conn.use { c ->
                        val ch = SecureChannel.client(c.input, c.output, cfg.deviceId, SecureChannel.Mode.SYNC, key)
                        if (ch.peerId != peer.deviceId) throw SyncAuthException("Otro dispositivo")
                        val report = engine.runClient(ch)
                        synced(peer.deviceId, ip?.takeIf { it != NEARBY }, if (ip == null) peer.bluetooth else null, report)
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
            messages[peer.deviceId] = when {
                cfg.nearby -> "No se lo encontró: ni cerca, ni en la misma red, ni en Tailscale."
                cfg.bluetooth -> "No está en la misma red, ni en Tailscale, ni cerca por Bluetooth."
                else -> "No está en la misma red ni en Tailscale. Probá «Cerca, sin red»."
            }
        } finally {
            setRunning(peer.deviceId, false)
        }
    }

    private fun synced(peerId: String, address: String?, btAddr: String?, report: SyncReport?) {
        store.update(peerId) { p ->
            p.copy(
                lastSync = System.currentTimeMillis(),
                // Primero la que funcionó, después las que anunció el otro (así se aprende la de Tailscale), después las viejas.
                addresses = (listOfNotNull(address?.takeIf { it.isNotBlank() }) + announced(report) + p.addresses)
                    .distinct().take(MAX_ADDRESSES),
                bluetooth = btAddr ?: p.bluetooth,
            )
        }
        messages[peerId] = when {
            report == null -> "Al día."
            report.conflicts > 0 -> "Al día. ${report.conflicts} hoja(s) cambiadas en los dos quedaron duplicadas para que elijas."
            report.deferred > 0 -> "Al día, salvo ${report.deferred} cuaderno(s) abiertos: siguen al cerrarlos."
            else -> "Al día."
        }
    }

    /** Las direcciones que el otro anunció: IP de casa o Tailscale, o un nombre (MagicDNS). */
    private fun announced(report: SyncReport?): List<String> =
        report?.remoteAddresses.orEmpty().map { cleanHost(it) }.filter { h ->
            h.isNotEmpty() && (!looksLikeIp(h) || runCatching { SyncAddresses.isAllowed(InetAddress.getByName(h).address) }.getOrDefault(false))
        }

    private fun looksLikeIp(h: String) = h.contains(':') || h.all { it.isDigit() || it == '.' }

    /** Agrega a mano una dirección del otro (por ejemplo su IP 100.x de Tailscale o su nombre MagicDNS). */
    fun addAddress(peerId: String, address: String) = scope.launch {
        val h = cleanHost(address)
        if (h.isEmpty()) return@launch
        val found = resolve(h)
        store.update(peerId) { p -> p.copy(addresses = (listOf(h) + p.addresses).distinct().take(MAX_ADDRESSES)) }
        messages[peerId] = if (found.isEmpty()) "Guardada «$h», pero ahora no se encuentra: ¿Tailscale conectado en los dos?" else "Dirección guardada: $h."
        refresh()
        requestSync()
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
        val clean = cleanHost(host)
        if (clean.isEmpty()) return emptyList()
        return runCatching { InetAddress.getAllByName(clean).toList() }.getOrDefault(emptyList())
            .filter { SyncAddresses.isAllowed(it.address) }
            .sortedBy { if (it.address.size == 4) 0 else 1 }
    }

    /** «https://nombre.ts.net:8443/x», «[fd7a::1]:47821» o «100.64.1.2:47821» → solo el equipo: el puerto es fijo. */
    private fun cleanHost(host: String): String {
        var h = host.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringAfter('@')
        h = if (h.startsWith("[")) h.removePrefix("[").substringBefore("]")
        else if (h.count { it == ':' } == 1) h.substringBefore(':') else h
        return h.trim().trimEnd('.').lowercase()
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
                // Una IPv4 antes que una IPv6 de enlace local (sin la interfaz no se puede conectar a esa).
                val candidates = (if (Build.VERSION.SDK_INT >= 34) runCatching { info.hostAddresses }.getOrDefault(emptyList()) else emptyList()) +
                    listOfNotNull(info.host)
                val host = candidates.filter { !it.isLinkLocalAddress || (it is java.net.Inet6Address && it.scopedInterface != null) }
                    .minByOrNull { if (it.address.size == 4) 0 else 1 }
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
        val btReady = bt.ready()
        _state.update {
            it.copy(
                config = cfg, deviceName = deviceName(cfg), peers = peers, addresses = myAddresses(),
                bluetoothAvailable = bt.available, bluetoothReady = btReady,
                bluetoothDevices = if (btReady && cfg.bluetooth) bt.bonded() else emptyList(),
                nearbyAvailable = near.available, nearbyReady = near.ready(),
                nearbySeen = if (cfg.nearby) near.seen().size else 0,
            )
        }
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
        const val CONNECT_TIMEOUT = 6_000
        /** Marca de «la conexión fue por Nearby» (no es una dirección para guardar). */
        const val NEARBY = "nearby"
        const val READ_TIMEOUT = 30_000
        const val MAX_CONNECTIONS = 4
        const val MAX_ADDRESSES = 6
    }
}
