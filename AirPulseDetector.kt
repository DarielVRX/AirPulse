package com.xdariel.airpulse.engine

/**
 * Combina [StreamingAirdrumEngine] (deteccion de golpes + roll@t_hit) con
 * [AzimuthStreamTracker] (mejor azimuth del tramo) para producir un evento
 * unico por golpe confirmado: (roll, azimuth, label L/H).
 *
 * El orden de alimentacion es importante: por cada frame de accel_x hay
 * que llamar primero a [feedFrame], que a su vez llama a
 * `engine.onFrame(...)` (lo que puede confirmar un hit sincronicamente) y
 * recien despues consulta `azimuthTracker.onHitConfirmed()` para ese
 * mismo instante, de forma que el tramo de azimuth que se cierra sea
 * exactamente el que precede a este hit.
 */
class AirPulseDetector(
    val engine: StreamingAirdrumEngine = StreamingAirdrumEngine(),
    val azimuthTracker: AzimuthStreamTracker = AzimuthStreamTracker(),
) {

    data class PositionedHit(
        val tHit: Long,
        val roll: Double,
        val azimuth: Double?,
        val label: StreamingAirdrumEngine.HitLabel,
    )

    /** Invocado por cada golpe confirmado y clasificado, con su azimuth asociado. */
    var onPositionedHit: ((PositionedHit) -> Unit)? = null

    private var pendingHitCount = 0

    init {
        engine.onHitClassified = { classified ->
            val azimuth = azimuthTracker.onHitConfirmed()
            onPositionedHit?.invoke(
                PositionedHit(
                    tHit = classified.tHit,
                    roll = classified.rollUsed,
                    azimuth = azimuth,
                    label = classified.label,
                )
            )
        }
    }

    /** Alimenta un frame con el mismo accel_x para ambos sub-sistemas y el ultimo roll/azimuth conocidos. */
    fun feedFrame(t: Long, accelX: Double, roll: Double, azimuth: Double) {
        // engine.onFrame puede disparar sincronicamente onHitClassified (y,
        // dentro de ese callback, azimuthTracker.onHitConfirmed()), asi que
        // azimuthTracker.onFrame debe correr ANTES para que el tramo que se
        // cierra incluya la muestra actual.
        azimuthTracker.onFrame(t, accelX, azimuth)
        engine.onFrame(t, accelX, roll)
    }

    fun reset() {
        engine.reset()
        azimuthTracker.reset()
        pendingHitCount = 0
    }
}
