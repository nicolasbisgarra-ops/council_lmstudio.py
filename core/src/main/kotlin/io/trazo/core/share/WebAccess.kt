package io.trazo.core.share

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable

/** Un navegador vinculado. De su clave solo se guarda el SHA-256: con el archivo no se puede entrar. */
@Serializable
data class WebDevice(val id: String, val hash: String, val name: String, val createdAt: Long, val seenAt: Long)

/** Lo que se guarda del acceso web: si está prendido, el puerto fijo y los navegadores vinculados. */
@Serializable
data class WebAccessConfig(val enabled: Boolean = false, val port: Int = 0, val devices: List<WebDevice> = emptyList())

/**
 * Quién puede entrar a la web de revisión. Un navegador se vincula una sola vez con el código que muestra
 * Trazo (el del QR) y recibe una clave propia en una cookie; desde ahí entra directo hasta que se lo
 * desvincula. El código dura [CODE_MILLIS] y se anula a los [MAX_FAILURES] intentos fallidos.
 */
class WebAccess(
    private val load: () -> WebAccessConfig,
    private val save: (WebAccessConfig) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    class Code(val code: String, val expiresAt: Long)

    sealed interface Pairing {
        class Linked(val cookie: String, val device: WebDevice) : Pairing
        class Wrong(val triesLeft: Int) : Pairing
        /** No hay un código vigente: hay que pedir uno en Trazo. */
        object Closed : Pairing
    }

    /** Avisa cuando cambian los vinculados o el código (para refrescar la pantalla). */
    @Volatile var onChange: (() -> Unit)? = null

    private var cached: WebAccessConfig? = null
    private var code: Code? = null
    private var failures = 0

    @Synchronized
    fun config(): WebAccessConfig = cached ?: runCatching(load).getOrDefault(WebAccessConfig()).also { cached = it }

    @Synchronized
    fun update(change: (WebAccessConfig) -> WebAccessConfig) {
        val next = change(config())
        cached = next
        runCatching { save(next) }
    }

    /** Un código nuevo; el anterior deja de servir. */
    fun newCode(): Code {
        val c = synchronized(this) {
            failures = 0
            Code("%08d".format(random.nextInt(100_000_000)), clock() + CODE_MILLIS).also { code = it }
        }
        onChange?.invoke()
        return c
    }

    @Synchronized
    fun code(): Code? = code?.takeIf { clock() < it.expiresAt }

    fun cancelCode() {
        synchronized(this) { code = null }
        onChange?.invoke()
    }

    /** Vincula el navegador que mandó [given]; [userAgent] solo sirve para ponerle nombre. */
    fun pair(given: String, userAgent: String?): Pairing {
        val result: Pairing = synchronized(this) {
            val current = code() ?: return@synchronized Pairing.Closed
            val typed = given.filter { it.isDigit() }
            if (!MessageDigest.isEqual(typed.toByteArray(Charsets.US_ASCII), current.code.toByteArray(Charsets.US_ASCII))) {
                failures++
                if (failures >= MAX_FAILURES) code = null
                return@synchronized Pairing.Wrong((MAX_FAILURES - failures).coerceAtLeast(0))
            }
            val secret = token(32)
            val now = clock()
            val device = WebDevice(token(8), sha256(secret), deviceName(userAgent), now, now)
            update { c -> c.copy(devices = (c.devices + device).sortedByDescending { it.seenAt }.take(MAX_DEVICES)) }
            Pairing.Linked("${device.id}.$secret", device)
        }
        onChange?.invoke()
        return result
    }

    /** El navegador dueño de la cookie [value], o null. */
    fun authenticate(value: String?): WebDevice? {
        var changed = false
        val device: WebDevice? = synchronized(this) {
            val v = value ?: return@synchronized null
            val dot = v.indexOf('.')
            if (dot <= 0 || v.length > 200) return@synchronized null
            val id = v.substring(0, dot)
            val found = config().devices.firstOrNull { it.id == id } ?: return@synchronized null
            if (!MessageDigest.isEqual(sha256(v.substring(dot + 1)).toByteArray(Charsets.US_ASCII), found.hash.toByteArray(Charsets.US_ASCII))) {
                return@synchronized null
            }
            val now = clock()
            if (now - found.seenAt >= SEEN_MILLIS) {
                update { c -> c.copy(devices = c.devices.map { if (it.id == id) it.copy(seenAt = now) else it }) }
                changed = true
            }
            found
        }
        if (changed) onChange?.invoke()
        return device
    }

    fun forget(id: String) {
        update { c -> c.copy(devices = c.devices.filterNot { it.id == id }) }
        onChange?.invoke()
    }

    fun forgetAll() {
        update { it.copy(devices = emptyList()) }
        onChange?.invoke()
    }

    private fun token(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    companion object {
        const val COOKIE = "trazo_web"
        const val CODE_MILLIS = 10 * 60_000L
        const val MAX_FAILURES = 5
        const val MAX_DEVICES = 12
        /** Cada cuánto se anota que un navegador sigue entrando. */
        const val SEEN_MILLIS = 5 * 60_000L
        /** Lo que dura la cookie (el máximo que acepta Chrome); se renueva cada vez que se entra. */
        const val COOKIE_SECONDS = 400L * 24 * 3600

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** «Chrome · Windows» a partir del User-Agent; nunca más que un rótulo. */
        fun deviceName(userAgent: String?): String {
            val ua = userAgent.orEmpty()
            val browser = when {
                "Edg/" in ua || "EdgA/" in ua -> "Edge"
                "OPR/" in ua -> "Opera"
                "SamsungBrowser/" in ua -> "Samsung Internet"
                "Firefox/" in ua || "FxiOS/" in ua -> "Firefox"
                "Chrome/" in ua || "CriOS/" in ua -> "Chrome"
                "Safari/" in ua -> "Safari"
                else -> "Navegador"
            }
            val system = when {
                "Android" in ua -> "Android"
                "iPhone" in ua -> "iPhone"
                "iPad" in ua -> "iPad"
                "Windows" in ua -> "Windows"
                "CrOS" in ua -> "ChromeOS"
                "Mac OS X" in ua || "Macintosh" in ua -> "Mac"
                "Linux" in ua -> "Linux"
                else -> null
            }
            return if (system == null) browser else "$browser · $system"
        }
    }
}
