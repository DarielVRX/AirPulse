package com.xdariel.airpulse.engine

/**
 * Las 8 piezas de percusion seleccionables en [SetupScreen].
 *
 * Kick (bombo) y pedal de hi-hat quedan EXCLUIDOS explicitamente: ambos se
 * tocan con el pie en una bateria real, no con un gesto de golpe en el
 * aire con la mano, que es el unico gesto que este dispositivo (acelerometro
 * + rotation vector sostenidos en la mano) puede capturar. Incluirlos
 * confundiria al usuario pidiendole "golpear en el aire" algo que
 * naturalmente se toca con el pie.
 */
enum class DrumPiece(val displayName: String, val soundResName: String) {
    SNARE("Snare", "drum_snare"),
    HI_HAT_CLOSED("Hi-Hat Cerrado", "drum_hihat_closed"),
    HI_HAT_OPEN("Hi-Hat Abierto", "drum_hihat_open"),
    CRASH("Crash", "drum_crash"),
    RIDE("Ride", "drum_ride"),
    TOM_HIGH("Tom Alto", "drum_tom_high"),
    TOM_MID("Tom Medio", "drum_tom_mid"),
    TOM_LOW("Tom Bajo", "drum_tom_low"),
}

/**
 * Una posicion calibrada: una pieza asignada a un punto libre en el plano
 * roll x azimuth (sin estructura de grilla fija). El centro se calcula
 * durante la calibracion a partir de los golpes recibidos para esa pieza.
 */
data class CalibratedPosition(
    val piece: DrumPiece,
    val centerRoll: Double,
    val centerAzimuth: Double,
)
