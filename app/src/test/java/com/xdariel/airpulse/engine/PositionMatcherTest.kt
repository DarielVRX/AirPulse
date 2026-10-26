package com.xdariel.airpulse.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PositionMatcherTest {

    @Test
    fun `circular mean near wraparound averages correctly`() {
        // promedio aritmetico simple de -179 y 179 da 0, pero el promedio
        // circular correcto esta cerca de ±180 (son casi opuestos, cruzando
        // el limite).
        val mean = PositionMatcher.circularMeanAzimuth(listOf(-179.0, 179.0))
        assertTrue(
            "promedio circular deberia estar cerca de +-180, fue $mean",
            abs(mean) > 170.0,
        )
    }

    @Test
    fun `circular mean of clustered values away from wraparound matches arithmetic mean`() {
        val mean = PositionMatcher.circularMeanAzimuth(listOf(10.0, 12.0, 8.0))
        assertTrue(abs(mean - 10.0) < 0.5)
    }

    @Test
    fun `closest position always returns something when positions exist`() {
        val positions = listOf(
            CalibratedPosition(DrumPiece.SNARE, centerRoll = -60.0, centerAzimuth = 0.0),
            CalibratedPosition(DrumPiece.CRASH, centerRoll = 40.0, centerAzimuth = 170.0),
        )
        // un hit lejos de ambas posiciones igual debe devolver la mas
        // cercana (sin rechazo por distancia)
        val closest = PositionMatcher.closestPosition(hitRoll = 1000.0, hitAzimuth = -170.0, positions)
        assertEquals(DrumPiece.CRASH, closest?.piece)
    }

    @Test
    fun `closest position returns null for empty calibration`() {
        val closest = PositionMatcher.closestPosition(0.0, 0.0, emptyList())
        assertEquals(null, closest)
    }
}
