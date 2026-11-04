package io.trazo.core.sync

/**
 * Qué direcciones puede usar la sincronización: solo la red de casa y Tailscale. Se revisan los bytes de
 * la dirección (4 de IPv4 o 16 de IPv6), tanto al llamar como al atender.
 */
object SyncAddresses {
    fun isAllowed(addr: ByteArray): Boolean = when (addr.size) {
        4 -> isAllowed4(addr)
        16 -> {
            val b0 = addr[0].toInt() and 0xff
            val b1 = addr[1].toInt() and 0xff
            val mapped = (0 until 10).all { addr[it].toInt() == 0 } && (addr[10].toInt() and 0xff) == 0xff && (addr[11].toInt() and 0xff) == 0xff
            when {
                mapped -> isAllowed4(addr.copyOfRange(12, 16))
                // fc00::/7: redes privadas IPv6, incluida la de Tailscale (fd7a:115c:a1e0::/48).
                (b0 and 0xfe) == 0xfc -> true
                // fe80::/10: enlace local (misma red Wi-Fi).
                b0 == 0xfe && (b1 and 0xc0) == 0x80 -> true
                else -> false
            }
        }
        else -> false
    }

    private fun isAllowed4(a: ByteArray): Boolean {
        val b0 = a[0].toInt() and 0xff
        val b1 = a[1].toInt() and 0xff
        return when {
            b0 == 10 -> true
            b0 == 172 && b1 in 16..31 -> true
            b0 == 192 && b1 == 168 -> true
            b0 == 169 && b1 == 254 -> true
            // 100.64.0.0/10: las direcciones de Tailscale.
            b0 == 100 && b1 in 64..127 -> true
            else -> false
        }
    }

    /** Las de Tailscale (100.64/10 o fd7a:115c:a1e0::/48), para mostrarlas al vincular. */
    fun isTailscale(addr: ByteArray): Boolean = when (addr.size) {
        4 -> (addr[0].toInt() and 0xff) == 100 && (addr[1].toInt() and 0xff) in 64..127
        16 -> (addr[0].toInt() and 0xff) == 0xfd && addr[1].toInt() == 0x7a && addr[2].toInt() == 0x11 &&
            addr[3].toInt() == 0x5c && (addr[4].toInt() and 0xff) == 0xa1 && (addr[5].toInt() and 0xff) == 0xe0
        else -> false
    }
}
