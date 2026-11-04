package io.trazo.core.sync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** La conexión no es de un dispositivo vinculado, o los datos se alteraron. */
class SyncAuthException(message: String) : IOException(message)

/**
 * Canal cifrado y autenticado entre dos dispositivos que comparten una clave de 32 bytes (o la que sale
 * del código de vinculación). Cada conexión deriva claves propias para cada sentido a partir de dos
 * números al azar, así nada de una conexión sirve en otra. Cada mensaje va con AES-256-GCM y un
 * contador como nonce: un mensaje alterado, repetido o reordenado corta la conexión.
 */
class SecureChannel private constructor(
    input: InputStream,
    output: OutputStream,
    sendKey: ByteArray,
    recvKey: ByteArray,
    /** El id que el otro dispositivo dio en el saludo. */
    val peerId: String,
    val mode: Mode,
) : AutoCloseable {
    enum class Mode(val code: Int) { SYNC(1), PAIR(2) }

    private val inp = DataInputStream(input)
    private val out = DataOutputStream(output)
    private val sendKey = SecretKeySpec(sendKey, "AES")
    private val recvKey = SecretKeySpec(recvKey, "AES")
    private var sent = 0L
    private var received = 0L

    fun send(plain: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, nonce(sent++)))
        val enc = c.doFinal(plain)
        out.writeInt(enc.size)
        out.write(enc)
        out.flush()
    }

    fun receive(): ByteArray {
        val size = inp.readInt()
        if (size < 16 || size > MAX_FRAME) throw SyncAuthException("Mensaje de tamaño inválido")
        val enc = ByteArray(size)
        inp.readFully(enc)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, recvKey, GCMParameterSpec(128, nonce(received++)))
        return try { c.doFinal(enc) } catch (e: java.security.GeneralSecurityException) {
            throw SyncAuthException("No se pudo autenticar el mensaje")
        }
    }

    fun send(m: SyncMessage) = send(SyncProtocol.encode(m))
    fun receiveMessage(): SyncMessage = SyncProtocol.decode(receive())

    override fun close() {
        runCatching { out.flush() }
    }

    companion object {
        /** Tope de un mensaje: un cuaderno largo con todas sus hojas. */
        const val MAX_FRAME = 64 * 1024 * 1024
        private val MAGIC = byteArrayOf('T'.code.toByte(), 'R'.code.toByte(), 'Z'.code.toByte(), '1'.code.toByte())
        private const val NONCE = 32
        private val random = SecureRandom()

        /** Abre la conexión del lado que llama; [key] es la clave del par o la del código. */
        fun client(input: InputStream, output: OutputStream, myId: String, mode: Mode, key: ByteArray): SecureChannel {
            val o = DataOutputStream(output)
            val i = DataInputStream(input)
            val nc = ByteArray(NONCE).also(random::nextBytes)
            o.write(MAGIC); o.writeByte(mode.code); writeId(o, myId); o.write(nc); o.flush()
            val magic = ByteArray(4).also(i::readFully)
            if (!magic.contentEquals(MAGIC)) throw SyncAuthException("No es Trazo")
            val serverId = readId(i)
            val ns = ByteArray(NONCE).also(i::readFully)
            val (c2s, s2c) = sessionKeys(key, mode, myId, serverId, nc, ns)
            return SecureChannel(input, output, c2s, s2c, serverId, mode)
        }

        /**
         * Atiende una conexión: lee el saludo, pide la clave para ese id y modo ([keyFor], null rechaza)
         * y responde. La clave equivocada se nota en el primer mensaje, que no se puede abrir.
         */
        fun server(
            input: InputStream,
            output: OutputStream,
            myId: String,
            keyFor: (mode: Mode, peerId: String) -> ByteArray?,
        ): SecureChannel {
            val i = DataInputStream(input)
            val o = DataOutputStream(output)
            val magic = ByteArray(4).also(i::readFully)
            if (!magic.contentEquals(MAGIC)) throw SyncAuthException("No es Trazo")
            val mode = Mode.entries.firstOrNull { it.code == i.readUnsignedByte() } ?: throw SyncAuthException("Modo desconocido")
            val clientId = readId(i)
            val nc = ByteArray(NONCE).also(i::readFully)
            val key = keyFor(mode, clientId) ?: throw SyncAuthException("Dispositivo no vinculado")
            val ns = ByteArray(NONCE).also(random::nextBytes)
            o.write(MAGIC); writeId(o, myId); o.write(ns); o.flush()
            val (c2s, s2c) = sessionKeys(key, mode, clientId, myId, nc, ns)
            return SecureChannel(input, output, s2c, c2s, clientId, mode)
        }

        private fun writeId(o: DataOutputStream, id: String) {
            val b = id.toByteArray(Charsets.UTF_8)
            require(b.size in 1..MAX_ID) { "Id inválido" }
            o.writeByte(b.size); o.write(b)
        }

        private fun readId(i: DataInputStream): String {
            val n = i.readUnsignedByte()
            if (n !in 1..MAX_ID) throw SyncAuthException("Id inválido")
            return ByteArray(n).also(i::readFully).toString(Charsets.UTF_8)
        }

        private const val MAX_ID = 64

        private fun sessionKeys(key: ByteArray, mode: Mode, clientId: String, serverId: String, nc: ByteArray, ns: ByteArray): Pair<ByteArray, ByteArray> {
            fun derive(label: String): ByteArray {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(key, "HmacSHA256"))
                mac.update("trazo-sync-v1|$label|${mode.code}|".toByteArray())
                mac.update(clientId.toByteArray()); mac.update(0)
                mac.update(serverId.toByteArray()); mac.update(0)
                mac.update(nc); mac.update(ns)
                return mac.doFinal()
            }
            return derive("c2s") to derive("s2c")
        }

        private fun nonce(counter: Long) = ByteArray(12).also { b -> for (k in 0 until 8) b[11 - k] = (counter ushr (8 * k)).toByte() }

        fun newKey(): ByteArray = ByteArray(32).also(random::nextBytes)
    }
}

/**
 * Código de vinculación: 10 letras y números (50 bits) que se muestran como `ABCDE-FGHIJ`. La clave que
 * sale de él se estira con PBKDF2 para que adivinarlo a partir de una conexión espiada cueste caro, y
 * el que muestra el código acepta pocos intentos fallidos.
 */
object PairingCode {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 10
    private const val ITERATIONS = 120_000

    fun generate(random: SecureRandom = SecureRandom()): String =
        (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /** Para mostrar: `ABCDE-FGHIJ`. */
    fun pretty(code: String): String = normalize(code)?.let { it.substring(0, 5) + "-" + it.substring(5) } ?: code

    /** Lo escrito por el usuario sin espacios ni guiones, en mayúsculas; null si no es un código. */
    fun normalize(input: String): String? {
        val s = input.uppercase().filter { it.isLetterOrDigit() }
        return s.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }

    fun key(code: String): ByteArray {
        val c = normalize(code) ?: throw IllegalArgumentException("Código inválido")
        val spec = PBEKeySpec(c.toCharArray(), "trazo-sync-pairing-v1".toByteArray(), ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
