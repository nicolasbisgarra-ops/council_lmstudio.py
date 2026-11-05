package io.trazo.app.sync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Una conexión abierta con el otro Trazo, por la red o por Bluetooth. */
internal class SyncConn(val input: InputStream, val output: OutputStream, private val closer: Closeable) : Closeable {
    override fun close() { runCatching { closer.close() } }
}

/** Un dispositivo emparejado en los ajustes de Bluetooth de Android. */
data class BluetoothPeer(val name: String, val address: String)

/**
 * Bluetooth clásico (RFCOMM) con los dispositivos ya emparejados en Android: sirve cuando no hay red de casa
 * ni Tailscale. No busca equipos nuevos (no hace falta el permiso de ubicación ni el de búsqueda); el enlace
 * de Android ya va cifrado y encima va el canal de Trazo con la clave propia.
 */
@SuppressLint("MissingPermission")
internal class BluetoothLink(private val context: Context) {
    private val adapter: BluetoothAdapter? =
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()

    /** El equipo tiene Bluetooth. */
    val available: Boolean get() = adapter != null

    /** Android 12+ pide «Dispositivos cercanos» para conectar; antes alcanza con el permiso del manifiesto. */
    fun permitted(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Bluetooth prendido y con permiso. */
    fun ready(): Boolean = permitted() && runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    /** Emparejados en Android; primero teléfonos y tabletas (los que pueden tener Trazo). */
    fun bonded(): List<BluetoothPeer> {
        if (!ready()) return emptyList()
        val devices = runCatching { adapter?.bondedDevices.orEmpty().toList() }.getOrDefault(emptyList())
        return devices
            .sortedBy { if (likelyTrazo(it.bluetoothClass)) 0 else 1 }
            .map { BluetoothPeer(runCatching { it.name }.getOrNull()?.takeIf { n -> n.isNotBlank() } ?: it.address, it.address) }
    }

    /** Los emparejados que pueden tener Trazo: teléfonos, tabletas y computadoras (no auriculares ni relojes). */
    fun likely(): List<BluetoothPeer> {
        if (!ready()) return emptyList()
        val devices = runCatching { adapter?.bondedDevices.orEmpty().toList() }.getOrDefault(emptyList())
        return devices.filter { likelyTrazo(it.bluetoothClass) }
            .map { BluetoothPeer(runCatching { it.name }.getOrNull() ?: it.address, it.address) }
    }

    fun nameOf(address: String): String? = bonded().firstOrNull { it.address.equals(address, ignoreCase = true) }?.name

    fun listen(): BluetoothServerSocket? {
        if (!ready()) return null
        return runCatching { adapter?.listenUsingRfcommWithServiceRecord("Trazo", SERVICE) }.getOrNull()
    }

    /** Abre la conexión con [address]; tarda unos segundos si el otro está lejos o apagado. */
    fun connect(address: String): SyncConn {
        val a = adapter ?: throw IOException("Sin Bluetooth")
        if (!ready()) throw IOException("Bluetooth apagado o sin permiso")
        if (!BluetoothAdapter.checkBluetoothAddress(address.uppercase())) throw IOException("Dirección Bluetooth inválida")
        val socket = try {
            a.getRemoteDevice(address.uppercase()).createRfcommSocketToServiceRecord(SERVICE)
        } catch (e: Exception) {
            throw IOException(e.message ?: "Bluetooth", e)
        }
        try {
            socket.connect()
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw if (e is IOException) e else IOException(e.message ?: "Bluetooth", e)
        }
        return guard(socket)
    }

    /**
     * El socket de Bluetooth no tiene tiempo de espera de lectura: si el otro deja de responder, un vigilante
     * lo cierra tras [IDLE_MILLIS] sin tráfico (lo mismo que hace el socket de red).
     */
    fun guard(socket: BluetoothSocket): SyncConn {
        val watch = IdleWatch(socket)
        val input = object : FilterInputStream(socket.inputStream) {
            override fun read(): Int = super.read().also { watch.touch() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { watch.touch() }
        }
        val output = object : FilterOutputStream(socket.outputStream) {
            override fun write(b: Int) { out.write(b); watch.touch() }
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); watch.touch() }
        }
        return SyncConn(BufferedInputStream(input), BufferedOutputStream(output, 16 * 1024), watch)
    }

    private class IdleWatch(private val socket: BluetoothSocket) : Closeable {
        @Volatile private var last = System.currentTimeMillis()
        @Volatile private var closed = false

        init {
            Thread({
                while (!closed) {
                    try { Thread.sleep(2_000) } catch (_: InterruptedException) { break }
                    if (System.currentTimeMillis() - last > IDLE_MILLIS) close()
                }
            }, "trazo-bt-watch").apply { isDaemon = true; start() }
        }

        fun touch() { last = System.currentTimeMillis() }

        override fun close() {
            closed = true
            runCatching { socket.close() }
        }
    }

    private fun likelyTrazo(c: BluetoothClass?): Boolean {
        val major = c?.majorDeviceClass ?: return true
        return major == BluetoothClass.Device.Major.PHONE || major == BluetoothClass.Device.Major.COMPUTER ||
            major == BluetoothClass.Device.Major.UNCATEGORIZED || major == BluetoothClass.Device.Major.MISC
    }

    companion object {
        /** Servicio RFCOMM de Trazo (fijo: los dos lados lo buscan por este UUID). */
        val SERVICE: UUID = UUID.fromString("6f1c2a5e-3b7d-4c1e-9a2f-7e0d5b8c4a11")
        const val IDLE_MILLIS = 30_000L
        val PERMISSION: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null
    }
}
