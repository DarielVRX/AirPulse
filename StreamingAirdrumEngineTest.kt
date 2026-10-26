package com.xdariel.airpulse.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitarios del port Kotlin de `realtime_airdrum.py`. No contamos
 * con los CSV reales de las sesiones de validacion en este entorno, asi
 * que estos tests validan comportamiento especifico y documentado del
 * algoritmo con secuencias sinteticas minimas, en vez de reproducir el
 * conteo exacto de las 6 sesiones de referencia.
 *
 * Roll se mantiene constante y por encima de TH_ROLL en todos los tests
 * (no es el foco de estos tests); lo que se valida es exclusivamente el
 * pipeline de deteccion de picos (confirmacion + anti-rebote + pendiente
 * de ataque).
 */
class StreamingAirdrumEngineTest {

    private val neutralRoll = 0.0 // > TH_ROLL (-35.0) => clasificaria como H, irrelevante aqui

    /** Alimenta una lista de (t, accelX) al engine con roll neutral constante. */
    private fun feed(engine: StreamingAirdrumEngine, frames: List<Pair<Long, Double>>) {
        for ((t, accelX) in frames) {
            engine.onFrame(t, accelX, neutralRoll)
        }
    }

    @Test
    fun `plateau from sensor saturation confirms exactly one hit`() {
        // Simula un pico que satura el sensor: varias muestras identicas
        // en el maximo (156.99 en el caso real documentado en el comentario
        // de tryConfirmPeak). Debe confirmarse solo 1 hit, no uno por cada
        // muestra de la meseta.
        val engine = StreamingAirdrumEngine()
        val frames = mutableListOf<Pair<Long, Double>>()
        var t = 0L
        // baseline bajo antes del golpe
        repeat(6) { frames.add(t to 5.0); t += 3 }
        // ataque: subida rapida y pronunciada (pendiente bien por encima
        // de SLOPE_ABS_MIN=0.20 y no hay hit previo, asi que el filtro
        // relativo tampoco aplica)
        frames.add(t to 50.0); t += 3
        frames.add(t to 120.0); t += 3
        // meseta de saturacion: 5 muestras identicas en el pico
        repeat(5) { frames.add(t to 157.0); t += 3 }
        // caida tras el pico
        frames.add(t to 100.0); t += 3
        frames.add(t to 40.0); t += 3
        repeat(6) { frames.add(t to 5.0); t += 3 }

        feed(engine, frames)

        assertEquals(1, engine.results.size)
    }

    @Test
    fun `distant hit with low slope and no nearby previous hit is confirmed`() {
        // Un golpe real pero con ataque relativamente suave (pendiente
        // baja), MUY alejado en el tiempo de cualquier hit previo (gap muy
        // superior a SLOPE_FILTER_WINDOW_MS=150ms). El filtro relativo no
        // debe aplicarse (no hay "near_last_hit"), y la pendiente, aunque
        // baja, debe seguir superando el piso absoluto SLOPE_ABS_MIN=0.20
        // para confirmarse.
        val engine = StreamingAirdrumEngine()
        val frames = mutableListOf<Pair<Long, Double>>()
        var t = 0L

        // --- primer golpe, bien definido y lejos en el tiempo ---
        repeat(6) { frames.add(t to 5.0); t += 3 }
        frames.add(t to 60.0); t += 3
        frames.add(t to 140.0); t += 3
        frames.add(t to 150.0); t += 3
        frames.add(t to 90.0); t += 3
        repeat(6) { frames.add(t to 5.0); t += 3 }

        // --- gap largo, bien por encima de 150ms ---
        t += 400

        // --- segundo golpe: ataque suave pero sostenido ---
        // base ~5.0, subida gradual durante ~40ms (ATTACK_LOOKBACK_MS) hasta
        // ~40.0 => slope ~ (40-5)/40 = 0.875, bien por encima de 0.20
        val baseT = t
        frames.add(t to 5.0); t += 10
        frames.add(t to 15.0); t += 10
        frames.add(t to 25.0); t += 10
        frames.add(t to 35.0); t += 5
        frames.add(t to 40.0); t += 3
        frames.add(t to 35.0); t += 3
        repeat(6) { frames.add(t to 5.0); t += 10 }

        feed(engine, frames)

        assertEquals(2, engine.results.size)
        val secondHit = engine.results[1]
        assertTrue(
            "el segundo hit deberia estar lejos del primero (gap >> 150ms)",
            secondHit.tHit - engine.results[0].tHit > 150,
        )
    }

    @Test
    fun `nearby low-slope bounce after a real hit is rejected`() {
        // Golpe real bien definido, seguido muy de cerca (gap chico, dentro
        // de SLOPE_FILTER_WINDOW_MS=150ms Y dentro de MERGE_WINDOW_MS=90ms
        // de rebote simple sin valle suficiente) por un "rebote" con
        // pendiente de ataque mucho mas floja. Debe descartarse, quedando
        // 1 solo hit confirmado.
        val engine = StreamingAirdrumEngine()
        val frames = mutableListOf<Pair<Long, Double>>()
        var t = 0L

        // --- golpe real: subida rapida y pronunciada ---
        repeat(6) { frames.add(t to 5.0); t += 3 }
        frames.add(t to 60.0); t += 3
        frames.add(t to 140.0); t += 3
        frames.add(t to 150.0); t += 3
        // caida parcial, pero sin llegar a un valle profundo/sostenido
        // (menor a VALLEY_RATIO=0.6 del minimo de ambos picos, sostenido
        // por VALLEY_MIN_MS=40ms) antes del "rebote"
        frames.add(t to 90.0); t += 3
        frames.add(t to 70.0); t += 3

        // --- "rebote": sube de nuevo pero con pendiente floja, muy cerca
        // en el tiempo (gap chico, bien por debajo de 150ms) ---
        frames.add(t to 72.0); t += 3
        frames.add(t to 74.0); t += 3
        frames.add(t to 76.0); t += 3
        frames.add(t to 75.0); t += 3
        repeat(6) { frames.add(t to 5.0); t += 3 }

        feed(engine, frames)

        assertEquals(1, engine.results.size)
    }

    @Test
    fun `negative accel_x pre-swing is treated as zero magnitude and never becomes a hit`() {
        // El pre-swing (excursion negativa de accel_x) no debe competir
        // como candidato a pico bajo ninguna circunstancia: se trata como
        // magnitud 0.
        val engine = StreamingAirdrumEngine()
        val frames = mutableListOf<Pair<Long, Double>>()
        var t = 0L
        repeat(6) { frames.add(t to 0.0); t += 3 }
        // pre-swing negativo grande (en magnitud), nunca deberia generar hit
        frames.add(t to -40.0); t += 3
        frames.add(t to -90.0); t += 3
        frames.add(t to -140.0); t += 3
        frames.add(t to -80.0); t += 3
        frames.add(t to -30.0); t += 3
        repeat(6) { frames.add(t to 0.0); t += 3 }

        feed(engine, frames)

        assertEquals(0, engine.results.size)
    }

    @Test
    fun `hit below MIN_PEAK_MAG is never confirmed`() {
        val engine = StreamingAirdrumEngine()
        val frames = mutableListOf<Pair<Long, Double>>()
        var t = 0L
        repeat(6) { frames.add(t to 1.0); t += 3 }
        frames.add(t to 10.0); t += 3
        frames.add(t to 20.0); t += 3 // por debajo de MIN_PEAK_MAG=30.0
        frames.add(t to 15.0); t += 3
        frames.add(t to 5.0); t += 3
        repeat(6) { frames.add(t to 1.0); t += 3 }

        feed(engine, frames)

        assertEquals(0, engine.results.size)
    }

    @Test
    fun `classification uses roll at first sample at or after t_hit`() {
        // Verifica que la clasificacion usa el roll disponible en el
        // primer instante >= t_hit (y no, por ejemplo, el roll en el
        // momento exacto del frame que confirma el pico, que llega 2
        // muestras despues del maximo real).
        val engine = StreamingAirdrumEngine()
        var t = 0L

        // baseline con roll alto (H)
        repeat(6) {
            engine.onFrame(t, 1.0, 10.0)
            t += 3
        }
        // golpe
        engine.onFrame(t, 60.0, 10.0); t += 3
        engine.onFrame(t, 140.0, 10.0); t += 3
        engine.onFrame(t, 150.0, -50.0); t += 3 // roll cambia a L (< -35.0) en el pico
        engine.onFrame(t, 90.0, -50.0); t += 3
        engine.onFrame(t, 40.0, -50.0); t += 3
        repeat(6) {
            engine.onFrame(t, 1.0, -50.0)
            t += 3
        }

        assertEquals(1, engine.results.size)
        assertEquals(StreamingAirdrumEngine.HitLabel.L, engine.results[0].label)
    }
}
