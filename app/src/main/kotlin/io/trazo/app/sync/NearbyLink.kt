package io.trazo.app.sync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Tasks
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Un Trazo visto cerca por Nearby. */
internal data class NearbyEndpoint(val endpointId: String, val deviceId: String, val name: String)

/**
 * «Cerca, sin red»: Nearby Connections de Google Play (Bluetooth y Wi‑Fi directo entre los dos equipos, sin
 * router ni internet). Cada Trazo se anuncia y busca a la vez mientras está abierto; las conexiones se aceptan
 * solas porque la autenticación la hace [io.trazo.core.sync.SecureChannel] con la clave del par o del código.
 */
@SuppressLint("MissingPermission")
internal class NearbyLink(
    private val context: Context,
    private val myDeviceId: () -> String,
    private val myName: () -> String,
    /** Una conexión entrante lista para atender (la cierra quien la recibe). */
    private val onIncoming: (SyncConn) -> Unit,
    private val onChange: () -> Unit,
) {
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(context) }
    private val endpoints = ConcurrentHashMap<String, NearbyEndpoint>()
    private val pipes = ConcurrentHashMap<String, Pipe>()
    /** Conexiones que pedimos nosotros: endpointId → espera del resultado. */
    private val outgoing = ConcurrentHashMap<String, CompletableFuture<Pipe>>()
    private val incoming = AtomicInteger()
    @Volatile private var running = false

    /** El equipo tiene Google Play Services (Nearby viene con él). */
    val available: Boolean by lazy {
        runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS }
            .getOrDefault(false)
    }

    fun permitted(): Boolean = PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun ready(): Boolean = available && permitted()

    val active: Boolean get() = running

    /** Los Trazo cerca en este momento. */
    fun seen(): List<NearbyEndpoint> = endpoints.values.toList()

    fun endpointFor(deviceId: String): NearbyEndpoint? = endpoints.values.firstOrNull { it.deviceId == deviceId }

    @Synchronized
    fun start() {
        if (running || !ready()) return
        running = true
        val name = endpointName()
        val strategy = Strategy.P2P_CLUSTER
        client.startAdvertising(name, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { /* ya anunciado o sin radio: se reintenta al volver a primer plano */ }
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
        pipes.values.forEach { it.fail() }
        pipes.clear()
        outgoing.values.forEach { it.completeExceptionally(IOException("Nearby detenido")) }
        outgoing.clear()
        endpoints.clear()
        onChange()
    }

    /** Abre una conexión con [endpointId]; tarda unos segundos (Nearby elige Bluetooth o Wi‑Fi directo). */
    fun connect(endpointId: String): SyncConn {
        if (!running) throw IOException("«Cerca, sin red» apagado")
        val f = CompletableFuture<Pipe>()
        outgoing.put(endpointId, f)?.completeExceptionally(IOException("Reemplazada"))
        try {
            Tasks.await(client.requestConnection(endpointName(), endpointId, lifecycle), CONNECT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            outgoing.remove(endpointId, f)
            // Ya conectados (por ejemplo, el otro nos pidió primero): no sirve para un canal nuevo.
            throw IOException("No respondió por Nearby", e)
        }
        val pipe = try {
            f.get(CONNECT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            outgoing.remove(endpointId, f)
            runCatching { client.disconnectFromEndpoint(endpointId) }
            throw IOException("No se conectó por Nearby", e)
        }
        return pipe.conn()
    }

    private fun endpointName(): String {
        val n = myName().replace('|', ' ').take(24)
        return "T1|${myDeviceId()}|$n"
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            val parts = info.endpointName.split('|', limit = 3)
            if (parts.size < 2 || parts[0] != "T1") return
            val id = parts[1]
            if (id.isBlank() || id == myDeviceId()) return
            endpoints[endpointId] = NearbyEndpoint(endpointId, id, parts.getOrNull(2)?.ifBlank { null } ?: "Dispositivo")
            onChange()
        }

        override fun onEndpointLost(endpointId: String) {
            if (endpoints.remove(endpointId) != null) onChange()
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val mine = outgoing.containsKey(endpointId)
            if (!mine && incoming.get() >= MAX_INCOMING) {
                runCatching { client.rejectConnection(endpointId) }
                return
            }
            val pipe = Pipe(endpointId)
            pipes[endpointId] = pipe
            client.acceptConnection(endpointId, pipe.callback).addOnFailureListener { pipe.fail() }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val pipe = pipes[endpointId]
            val waiting = outgoing.remove(endpointId)
            if (!result.status.isSuccess || pipe == null) {
                pipes.remove(endpointId)?.fail()
                waiting?.completeExceptionally(IOException("Nearby rechazó la conexión"))
                return
            }
            if (waiting != null) {
                waiting.complete(pipe)
            } else {
                incoming.incrementAndGet()
                Thread({
                    try { pipe.conn().use(onIncoming) } finally { incoming.decrementAndGet() }
                }, "trazo-nearby-conn").apply { isDaemon = true; start() }
            }
        }

        override fun onDisconnected(endpointId: String) {
            pipes.remove(endpointId)?.fail()
            outgoing.remove(endpointId)?.completeExceptionally(IOException("Desconectado"))
        }
    }

    /**
     * Una conexión de Nearby como flujo de bytes: lo que llega se encola y se lee con espera máxima
     * [READ_MILLIS]; lo escrito se junta y sale en trozos al hacer flush.
     */
    private inner class Pipe(private val endpointId: String) {
        private val chunks = LinkedBlockingQueue<ByteArray>()
        @Volatile private var failed = false
        private val inFlight = Semaphore(MAX_IN_FLIGHT)
        private val pending = ConcurrentHashMap.newKeySet<Long>()

        val callback = object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                if (payload.type == Payload.Type.BYTES) payload.asBytes()?.let { if (it.isNotEmpty()) chunks.put(it) }
            }

            override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
                when (update.status) {
                    PayloadTransferUpdate.Status.SUCCESS -> if (pending.remove(update.payloadId)) inFlight.release()
                    PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                        if (pending.remove(update.payloadId)) inFlight.release()
                        fail()
                    }
                    else -> {}
                }
            }
        }

        fun fail() {
            failed = true
            chunks.offer(EOF)
            inFlight.release(MAX_IN_FLIGHT)
        }

        fun conn(): SyncConn = SyncConn(Input(), Output(), Closeable { close() })

        private fun close() {
            // Se espera a que salga lo último antes de cortar (si no, el otro no recibe la respuesta final).
            val deadline = System.currentTimeMillis() + DRAIN_MILLIS
            while (!failed && pending.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            pipes.remove(endpointId, this)
            runCatching { client.disconnectFromEndpoint(endpointId) }
            fail()
        }

        private fun send(bytes: ByteArray) {
            var off = 0
            while (off < bytes.size) {
                val n = minOf(CHUNK, bytes.size - off)
                if (!inFlight.tryAcquire(READ_MILLIS, TimeUnit.MILLISECONDS) || failed) throw IOException("Nearby no responde")
                val p = Payload.fromBytes(bytes.copyOfRange(off, off + n))
                pending += p.id
                try {
                    Tasks.await(client.sendPayload(endpointId, p), READ_MILLIS, TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    if (pending.remove(p.id)) inFlight.release()
                    fail()
                    throw IOException("No se pudo mandar por Nearby", e)
                }
                off += n
            }
        }

        private inner class Input : InputStream() {
            private var cur: ByteArray? = null
            private var pos = 0

            private fun fill(): Boolean {
                val c = cur
                if (c != null && pos < c.size) return true
                if (c === EOF) return false
                val next = chunks.poll(READ_MILLIS, TimeUnit.MILLISECONDS) ?: throw IOException("Nearby: tiempo de espera agotado")
                if (next === EOF) { cur = EOF; chunks.offer(EOF); return false }
                cur = next; pos = 0
                return true
            }

            override fun read(): Int = if (fill()) cur!![pos++].toInt() and 0xff else -1

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (!fill()) return -1
                val c = cur!!
                val n = minOf(len, c.size - pos)
                System.arraycopy(c, pos, b, off, n)
                pos += n
                return n
            }
        }

        private inner class Output : OutputStream() {
            private val buf = ByteArrayOutputStream()

            override fun write(b: Int) { buf.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { buf.write(b, off, len) }

            override fun flush() {
                if (buf.size() == 0) return
                if (failed) throw IOException("Nearby desconectado")
                val bytes = buf.toByteArray()
                buf.reset()
                send(bytes)
            }

            override fun close() = flush()
        }
    }

    companion object {
        const val SERVICE_ID = "io.trazo.sync"
        private const val CONNECT_SECONDS = 20L
        private const val READ_MILLIS = 30_000L
        private const val DRAIN_MILLIS = 5_000L
        private const val CHUNK = 32 * 1024
        private const val MAX_IN_FLIGHT = 8
        private const val MAX_INCOMING = 4
        private val EOF = ByteArray(0)

        /** Lo que Nearby pide según la versión de Android (se piden todos juntos). */
        val PERMISSIONS: Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}
