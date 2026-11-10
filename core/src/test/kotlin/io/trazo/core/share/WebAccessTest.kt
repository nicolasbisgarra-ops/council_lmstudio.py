package io.trazo.core.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAccessTest {
    private var saved = WebAccessConfig()
    private var saves = 0
    private var now = 1_000_000L
    private var changes = 0
    private fun access() = WebAccess({ saved }, { saved = it; saves++ }, { now }).also { it.onChange = { changes++ } }

    private fun link(a: WebAccess, ua: String? = "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0 Safari/537.36"): WebAccess.Pairing.Linked =
        a.pair(a.code()!!.code, ua) as WebAccess.Pairing.Linked

    @Test fun pairsWithTheCodeAndEntersWithTheCookie() {
        val a = access()
        assertNull(a.code())
        assertTrue(a.pair("12345678", null) is WebAccess.Pairing.Closed)
        val code = a.newCode()
        assertTrue(code.code.matches(Regex("\\d{8}")))
        assertEquals(now + WebAccess.CODE_MILLIS, code.expiresAt)
        // Con espacios o guiones también vale; sirve para más de un dispositivo mientras dure.
        val pc = a.pair(code.code.chunked(4).joinToString(" "), "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0 Safari/537.36") as WebAccess.Pairing.Linked
        val phone = a.pair(code.code, "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36") as WebAccess.Pairing.Linked
        assertEquals("Chrome · Windows", pc.device.name)
        assertEquals("Chrome · Android", phone.device.name)
        assertNotEquals(pc.cookie, phone.cookie)
        assertEquals(2, saved.devices.size)
        // En el archivo no queda la clave, solo su huella.
        val secret = pc.cookie.substringAfter('.')
        assertTrue(secret.length >= 40)
        assertFalse(saved.toString().contains(secret))
        assertEquals(WebAccess.sha256(secret), saved.devices.first { it.id == pc.device.id }.hash)
        assertEquals(pc.device.id, a.authenticate(pc.cookie)!!.id)
        assertEquals(phone.device.id, a.authenticate(phone.cookie)!!.id)
        // Otro arranque de la app lee lo guardado.
        assertEquals(pc.device.id, access().authenticate(pc.cookie)!!.id)
        assertTrue(changes >= 3)
    }

    @Test fun rejectsBadCookies() {
        val a = access()
        a.newCode()
        val ok = link(a)
        val id = ok.device.id
        for (bad in listOf(null, "", ".", "x", "$id.", "$id.mala", "otro.${ok.cookie.substringAfter('.')}", ok.cookie + "x", ok.cookie.dropLast(1), "$id." + "a".repeat(500))) {
            assertNull(bad, a.authenticate(bad))
        }
        assertNotNull(a.authenticate(ok.cookie))
    }

    @Test fun wrongCodesCloseThePairing() {
        val a = access()
        val code = a.newCode().code
        val wrong = if (code == "00000000") "00000001" else "00000000"
        for (left in WebAccess.MAX_FAILURES - 1 downTo 0) {
            assertEquals(left, (a.pair(wrong, null) as WebAccess.Pairing.Wrong).triesLeft)
        }
        assertNull(a.code())
        assertTrue(a.pair(code, null) is WebAccess.Pairing.Closed)
        assertTrue(saved.devices.isEmpty())
        // Un código nuevo vuelve a contar desde cero, y el viejo ya no sirve.
        val next = a.newCode().code
        assertTrue(a.pair("", null) is WebAccess.Pairing.Wrong)
        assertTrue(a.pair(next + "9", null) is WebAccess.Pairing.Wrong)
        assertTrue(a.pair(next, null) is WebAccess.Pairing.Linked)
    }

    @Test fun codeExpiresAndCanBeCancelled() {
        val a = access()
        val code = a.newCode().code
        now += WebAccess.CODE_MILLIS - 1
        assertNotNull(a.code())
        now += 1
        assertNull(a.code())
        assertTrue(a.pair(code, null) is WebAccess.Pairing.Closed)
        val other = a.newCode().code
        a.cancelCode()
        assertTrue(a.pair(other, null) is WebAccess.Pairing.Closed)
    }

    @Test fun forgetAndLimits() {
        val a = access()
        a.newCode()
        val first = link(a)
        val second = link(a)
        a.forget(first.device.id)
        assertNull(a.authenticate(first.cookie))
        assertNotNull(a.authenticate(second.cookie))
        a.forgetAll()
        assertNull(a.authenticate(second.cookie))
        assertTrue(saved.devices.isEmpty())
        // Se quedan los últimos que entraron.
        val linked = (0 until WebAccess.MAX_DEVICES + 3).map { now += 1000; a.newCode(); link(a) }
        assertEquals(WebAccess.MAX_DEVICES, saved.devices.size)
        assertNull(a.authenticate(linked.first().cookie))
        assertNotNull(a.authenticate(linked.last().cookie))
    }

    @Test fun seenIsSavedOnlyEveryFewMinutes() {
        val a = access()
        a.newCode()
        val d = link(a)
        val before = saves
        repeat(50) { now += 1000; a.authenticate(d.cookie) }
        assertEquals(before, saves)
        now += WebAccess.SEEN_MILLIS
        a.authenticate(d.cookie)
        assertEquals(before + 1, saves)
        assertEquals(now, saved.devices.single().seenAt)
    }

    @Test fun settingsAreKept() {
        val a = access()
        assertFalse(a.config().enabled)
        a.update { it.copy(enabled = true, port = 8787) }
        assertTrue(saved.enabled)
        assertEquals(8787, access().config().port)
        // Si no se puede leer, arranca apagado en vez de fallar.
        val broken = WebAccess({ error("roto") }, { error("roto") })
        assertFalse(broken.config().enabled)
        broken.update { it.copy(enabled = true) }
        assertTrue(broken.config().enabled)
    }

    @Test fun deviceNames() {
        assertEquals("Edge · Windows", WebAccess.deviceName("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36 Edg/130.0"))
        assertEquals("Safari · iPhone", WebAccess.deviceName("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1"))
        assertEquals("Firefox · Linux", WebAccess.deviceName("Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0"))
        assertEquals("Samsung Internet · Android", WebAccess.deviceName("Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 SamsungBrowser/25.0 Chrome/121.0 Mobile Safari/537.36"))
        assertEquals("Chrome · Mac", WebAccess.deviceName("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/130.0 Safari/537.36"))
        assertEquals("Navegador", WebAccess.deviceName(null))
        assertEquals("Navegador", WebAccess.deviceName("<script>"))
    }
}
