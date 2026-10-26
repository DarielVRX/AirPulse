package com.xdariel.airpulse.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AzimuthStreamTrackerTest {

    @Test
    fun `flat azimuth before attack is reported, attack phase samples are ignored`() {
        val tracker = AzimuthStreamTracker()
        var t = 0L

        // meseta: azimuth estable en 45.0 durante un rato (espaciado >=30ms
        // para que la derivada corta sea valida), accel_x bajo (sin ataque)
        repeat(6) {
            tracker.onFrame(t, 5.0, 45.0)
            t += 35
        }

        // fase de ataque: accel_x sube rapido y supera el umbral, azimuth
        // "se mueve" durante el ataque (no deberia contarse)
        tracker.onFrame(t, 25.0, 100.0); t += 10
        tracker.onFrame(t, 60.0, 150.0); t += 10
        tracker.onFrame(t, 140.0, 170.0); t += 10
        tracker.onFrame(t, 150.0, 175.0); t += 10
        tracker.onFrame(t, 155.0, 178.0); t += 10

        val reported = tracker.onHitConfirmed()

        assertNotNull(reported)
        // el mejor candidato deberia venir de la meseta inicial (45.0), no
        // de los valores durante el ataque
        assertTrue(
            "azimuth reportado deberia estar cerca de la meseta (45.0), fue $reported",
            abs(reported!! - 45.0) < 1.0,
        )
    }

    @Test
    fun `tracker resets accumulated candidate after reporting`() {
        val tracker = AzimuthStreamTracker()
        var t = 0L
        repeat(6) {
            tracker.onFrame(t, 1.0, 10.0)
            t += 35
        }
        val first = tracker.onHitConfirmed()
        assertNotNull(first)

        // sin alimentar nada nuevo, el segundo reporte no deberia repetir
        // candidato viejo (deberia ser null, tramo vacio)
        val second = tracker.onHitConfirmed()
        assertEquals(null, second)
    }
}
