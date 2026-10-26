package io.trazo.core

import kotlin.math.hypot

/**
 * Detecta "lápiz apoyado y quieto" durante [durationMs].
 *
 * Máquina de estados pura (sin temporizadores): la capa Android programa ticks no bloqueantes
 * y llama a [check]. La pausa solo cuenta mientras hay contacto: [up] o [cancel] antes del
 * umbral impiden la conversión. Dispara como máximo una vez por contacto.
 *
 * Las coordenadas pueden estar en cualquier espacio siempre que [radius] y la longitud de tinta
 * pasada a [check] usen el mismo.
 */
class DwellDetector(
    var durationMs: Long = 1000L,
    var radius: Float = 8f,
    var minInkLength: Float = 20f,
) {
    private var touching = false
    private var fired = false
    private var anchorX = 0f
    private var anchorY = 0f

    /** Momento en que empezó la quietud actual (último movimiento fuera del radio). */
    var stableFromMs: Long = 0L
        private set

    fun down(x: Float, y: Float, timeMs: Long) {
        touching = true; fired = false; anchorX = x; anchorY = y; stableFromMs = timeMs
    }

    /** El temblor dentro de [radius] no reinicia la pausa; salir del radio sí. */
    fun move(x: Float, y: Float, timeMs: Long) {
        if (!touching || fired) return
        if (hypot(x - anchorX, y - anchorY) > radius) {
            anchorX = x; anchorY = y; stableFromMs = timeMs
        }
    }

    fun check(nowMs: Long, inkLength: Float): Boolean {
        if (!isStill(nowMs, durationMs, inkLength)) return false
        fired = true
        return true
    }

    /** Consulta sin consumir: ¿hay contacto, tinta suficiente y quietud de al menos [duration]? */
    fun isStill(nowMs: Long, duration: Long, inkLength: Float): Boolean =
        touching && !fired && inkLength >= minInkLength && nowMs - stableFromMs >= duration

    /** Marca la pausa como usada: no habrá otra conversión en este contacto. */
    fun consume() { if (touching) fired = true }

    /** Avance 0..1 de la pausa, para el indicador visual. */
    fun progress(nowMs: Long): Float =
        if (!touching || fired) 0f else ((nowMs - stableFromMs).toFloat() / durationMs).coerceIn(0f, 1f)

    fun up() { touching = false }
    fun cancel() { touching = false; fired = false }
    val isTouching: Boolean get() = touching
    val hasFired: Boolean get() = fired
}
