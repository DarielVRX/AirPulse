package com.xdariel.airpulse.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Utilidades de geometria circular + matching de posicion mas cercana.
 *
 * DECISION DE DISEÑO (no cerrada en la sesion original, tomada por el
 * autor del port, pendiente de validar con datos reales -- ver README.md):
 *
 * La metrica de distancia combinada roll+azimuth usada en [PlayScreen] no
 * quedo definida en la sesion de diseño original. Azimuth es circular
 * (±180°) y roll no, asi que no se pueden combinar con una distancia
 * euclidea ingenua sin normalizar. Se implementa aqui una metrica
 * razonable de primer intento:
 *
 *   distancia = sqrt(deltaRoll^2 + deltaAzimuthCircular^2)
 *
 * donde deltaAzimuthCircular es la distancia angular circular mas corta
 * (nunca mayor a 180°), y NO se aplica ningun factor de escala adicional
 * entre roll y azimuth (se asume que ambos estan en grados y en rangos
 * comparables: roll tipicamente ±90°, azimuth ±180°). Esto es un primer
 * intento razonable, pendiente de validar/ajustar con datos reales de
 * calibracion (podria necesitar pesos distintos por eje, o no ser
 * euclidea en absoluto).
 */
object PositionMatcher {

    /** Distancia angular circular mas corta entre dos azimuths en grados, siempre en [0, 180]. */
    fun circularAzimuthDistance(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return kotlin.math.abs(d)
    }

    /**
     * Promedio circular de una lista de azimuths (grados), usando promedio
     * vectorial con seno/coseno. Un promedio aritmetico simple falla cerca
     * del limite ±180° (p.ej. promedio aritmetico de -179 y 179 da 0,
     * cuando el promedio circular correcto es ±180).
     */
    fun circularMeanAzimuth(azimuths: List<Double>): Double {
        require(azimuths.isNotEmpty()) { "circularMeanAzimuth requiere al menos un valor" }
        var sumSin = 0.0
        var sumCos = 0.0
        for (az in azimuths) {
            val rad = Math.toRadians(az)
            sumSin += sin(rad)
            sumCos += cos(rad)
        }
        val meanRad = atan2(sumSin / azimuths.size, sumCos / azimuths.size)
        return Math.toDegrees(meanRad)
    }

    /** Distancia combinada roll+azimuth entre un hit y una posicion calibrada. Ver TODO de la clase. */
    fun distance(hitRoll: Double, hitAzimuth: Double, position: CalibratedPosition): Double {
        val deltaRoll = hitRoll - position.centerRoll
        val deltaAzimuth = circularAzimuthDistance(hitAzimuth, position.centerAzimuth)
        return hypot(deltaRoll, deltaAzimuth)
    }

    /**
     * Devuelve la posicion calibrada mas cercana al hit. SIEMPRE devuelve
     * algo si [positions] no esta vacia (sin rechazo por distancia,
     * segun especificacion: el sistema siempre dispara un sonido).
     */
    fun closestPosition(hitRoll: Double, hitAzimuth: Double, positions: List<CalibratedPosition>): CalibratedPosition? {
        if (positions.isEmpty()) return null
        var best: CalibratedPosition? = null
        var bestDist = Double.MAX_VALUE
        for (pos in positions) {
            val d = distance(hitRoll, hitAzimuth, pos)
            if (d < bestDist) {
                bestDist = d
                best = pos
            }
        }
        return best
    }
}
