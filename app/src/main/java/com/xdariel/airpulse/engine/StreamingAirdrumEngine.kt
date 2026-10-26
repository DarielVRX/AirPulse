package com.xdariel.airpulse.engine

// Nota: usamos kotlin.collections.ArrayDeque (no java.util.ArrayDeque), ya
// que esta en el scope por defecto de Kotlin y SI soporta acceso indexado
// (get(i), operador []), necesario en tryConfirmPeak() para leer
// posiciones relativas al final del buffer (equivalente a buf[-3] en
// Python). java.util.ArrayDeque no ofrece get(index).

/**
 * Airdrum - deteccion + clasificacion en modo streaming (frame por frame).
 *
 * Port fiel de `realtime_airdrum.py` (version final validada, 6/6 sesiones
 * de prueba con conteo exacto de golpes, 0 falsos positivos/negativos).
 *
 * Simula lo que pasaria en tiempo real: procesa cada frame (muestra de
 * acelerometro) uno a uno, sin mirar nunca el futuro mas alla de 2 muestras
 * (necesarias para confirmar que un candidato a pico realmente fue un
 * maximo y no sigue subiendo), y sin usar estadisticas de toda la sesion
 * (reemplazadas por una mediana corriendo de los ultimos N golpes).
 *
 * Diferencias clave respecto al pipeline offline (detect_hits v4 + classify
 * v7), preservadas aqui tal como fueron validadas:
 *
 * 1. Confirmacion de pico: igual que offline (2 muestras de margen a cada
 *    lado), es el retraso minimo inevitable (~5-8ms a ~400Hz) para saber
 *    que el pico ya paso.
 *
 * 2. Filtro anti-rebote / fusion de golpes pegados: el offline mira el pico
 *    SIGUIENTE para decidir si el actual fue un rebote (fusion por valle
 *    insuficiente). En streaming eso no existe todavia, asi que el filtro
 *    aqui es causal: al confirmar un candidato a pico, se compara contra el
 *    ULTIMO golpe ya confirmado. Si estan dentro de [MERGE_WINDOW_MS] Y no
 *    hubo un valle suficientemente profundo/sostenido entre ambos, se trata
 *    como rebote del mismo golpe (se descarta el candidato). Una vez
 *    confirmado un hit, es definitivo: no se "deshace" retroactivamente.
 *
 * 3. Filtro de pendiente de ataque: el offline usa la mediana de pendientes
 *    de TODA la sesion. En streaming se usa una mediana corriendo de los
 *    ultimos N golpes confirmados (ring buffer), con un piso fijo de
 *    respaldo mientras no hay suficiente historial.
 *
 * 3b. Signo de accel_x (hallazgo validado sobre sesiones reales 031/032):
 *     los golpes reales tienen accel_x POSITIVO en el pico. Las excursiones
 *     negativas corresponden al pre-swing / preparacion del gesto (el brazo
 *     se mueve en sentido contrario antes de golpear), no al impacto. Usar
 *     abs(accel_x) (como hacia el offline v4) mezcla ambas fases y genera
 *     falsos candidatos a pico en el pre-swing cuando la cadencia sube y el
 *     pre-swing de un golpe queda cerca del golpe anterior. Esta version
 *     detecta picos directamente sobre accel_x con signo y descarta toda
 *     excursion negativa: el pre-swing deja de competir como candidato a
 *     golpe.
 *
 * 4. Clasificacion L/H: se lee el valor de roll disponible en el primer
 *    instante >= t_hit (sin esperar ninguna ventana fija: el valor que haya
 *    en ese momento, aunque sea el ultimo actualizado por el sensor de
 *    rotacion antes del golpe) y se compara contra un umbral fijo
 *    TH_ROLL=-35.0. Validado sobre datos reales (roll@t_hit, sin ventana):
 *    separa 23/26 golpes de las sesiones 031/032 (optimo real esta entre
 *    -48 y -52.5 con 25/26, pero se deja en -35 por decision explicita:
 *    margen mas amplio, sin afinar por 2-3 casos ya identificados como
 *    ejecucion atipica del gesto, no falla del metodo).
 *
 *    TODO: en la app real, TH_ROLL debe volverse configurable por
 *    calibracion por usuario/posicion en vez de ser una constante fija
 *    global. Por ahora se porta el valor fijo tal cual del script Python
 *    validado (ver [HitClassifier]).
 *
 * Latencia total esperada: solo la de confirmar el pico (~5-8ms a ~400Hz).
 * La clasificacion no agrega retraso propio.
 *
 * Unidades: todos los tiempos (t, timestamps) que recibe este motor son en
 * MILISEGUNDOS (Long o Int segun conveniencia), para ser consistente con
 * el CSV original (`timestamp_ms`). La capa de sensores (ver
 * `com.xdariel.airpulse.sensors`) es responsable de convertir los
 * timestamps de `SensorEvent` (nanosegundos, reloj de `SystemClock
 * .elapsedRealtimeNanos` aproximadamente) a milisegundos antes de llamar a
 * [onFrame].
 */
class StreamingAirdrumEngine {

    companion object {
        // umbral sobre roll leido en el primer instante >= t_hit, sin ventana
        const val TH_ROLL = -35.0

        const val MIN_PEAK_MAG = 30.0
        const val MERGE_WINDOW_MS = 90L
        const val VALLEY_RATIO = 0.6
        const val VALLEY_MIN_MS = 40L
        const val SLOPE_REL_THRESH = 0.35
        const val SLOPE_MEDIAN_WINDOW = 8          // golpes confirmados usados para la mediana corriente
        const val SLOPE_FALLBACK = 0.5             // piso conservador mientras no hay historial suficiente

        // dos mecanismos distintos para el filtro de pendiente de ataque (ver
        // confirmHit), en vez de una sola ventana ancha:
        //
        // 1. SLOPE_FILTER_WINDOW_MS: el filtro RELATIVO (contra la mediana
        //    corriente) solo aplica si el candidato esta cerca en tiempo del
        //    ultimo hit confirmado -- ahi es donde "pendiente mucho mas floja
        //    que la mediana" realmente distingue cola de rebote de golpe
        //    nuevo. 150ms cubre los rebotes simples observados (028.csv:
        //    gap=91ms, 034.csv primer rebote: gap=102ms) sin tocar golpes
        //    reales de cadencia alta (030.csv tiene gaps reales de hasta
        //    118ms, por debajo de 150 pero esos nunca fallaron el chequeo
        //    relativo porque su pendiente SI esta a la altura de la
        //    mediana).
        // 2. SLOPE_ABS_MIN: piso absoluto, siempre activo sin importar la
        //    distancia temporal. Cubre el caso que la ventana corta no
        //    alcanza (034.csv, segundo rebote de una cadena: gap=207ms,
        //    fuera de los 150ms pero con slope=0.17, muy por debajo del
        //    golpe real mas suave conocido, slope=0.254 en 038.csv). 0.20
        //    separa ambos con margen.
        const val SLOPE_FILTER_WINDOW_MS = 150L
        const val SLOPE_ABS_MIN = 0.20

        private const val MAG_BUF_CAPACITY = 30
        private const val ROLL_BUF_CAPACITY = 50
        private const val ATTACK_LOOKBACK_MS = 40L
    }

    /** Una muestra generica (t, valor). */
    private data class Sample(val t: Long, val v: Double)

    /** Hit ya confirmado (definitivo), con su pendiente de ataque. */
    data class ConfirmedHit(val t: Long, val mag: Double, val slope: Double?)

    /** Resultado final de clasificacion de un hit, listo para consumo externo. */
    data class ClassifiedHit(
        val tHit: Long,
        val label: HitLabel,
        val rollUsed: Double,
        val decisionLatencyMs: Long,
    )

    enum class HitLabel { L, H }

    // buffers cortos: solo lo necesario para confirmar picos y calcular
    // pendiente de ataque (ninguno crece sin limite). Kotlin no tiene un
    // deque(maxlen=N) nativo como Python, asi que se implementa el tope de
    // capacidad a mano sobre ArrayDeque (removeFirst() al superarla).
    private val magBuf = ArrayDeque<Sample>()   // (t, mag) ultimas muestras de accel_x con signo filtrado
    private val rollBuf = ArrayDeque<Sample>()  // (t, roll) ultimas muestras de roll

    private var lastHit: ConfirmedHit? = null
    private val recentSlopes = ArrayDeque<Double>() // ring buffer de tamanio SLOPE_MEDIAN_WINDOW

    private var pendingClassification: ConfirmedHit? = null

    private val _results = mutableListOf<ClassifiedHit>()
    val results: List<ClassifiedHit> get() = _results

    /** Callback opcional invocado apenas se clasifica un hit (modo streaming real). */
    var onHitClassified: ((ClassifiedHit) -> Unit)? = null

    private fun pushBounded(buf: ArrayDeque<Sample>, capacity: Int, sample: Sample) {
        buf.addLast(sample)
        while (buf.size > capacity) {
            buf.removeFirst()
        }
    }

    private fun pushSlope(slope: Double) {
        recentSlopes.addLast(slope)
        while (recentSlopes.size > SLOPE_MEDIAN_WINDOW) {
            recentSlopes.removeFirst()
        }
    }

    /**
     * Busca el minimo local en el buffer hacia atras desde el pico, dentro
     * de [lookbackMs], y devuelve la pendiente de ataque (mag/ms) entre esa
     * base y el pico. Null si no hay suficiente historial.
     */
    private fun attackSlope(peakT: Long, peakMag: Double, lookbackMs: Long = ATTACK_LOOKBACK_MS): Double? {
        val window = magBuf.filter { it.t in (peakT - lookbackMs)..peakT }
        if (window.size < 2) return null
        val base = window.minByOrNull { it.v } ?: return null
        if (base.t == peakT) return null
        val dt = peakT - base.t
        if (dt <= 0) return null
        return (peakMag - base.v) / dt
    }

    /** Mediana de las pendientes recientes (de los ultimos hits confirmados). */
    private fun medianSlope(): Double? {
        if (recentSlopes.size >= 2) {
            val sorted = recentSlopes.sorted()
            val n = sorted.size
            return if (n % 2 == 0) {
                (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
            } else {
                sorted[n / 2]
            }
        }
        if (recentSlopes.isNotEmpty()) return recentSlopes.first()
        return null
    }

    /**
     * Mira si la muestra en posicion -3 del buffer (indice i-2 relativo al
     * frame mas reciente) es un pico local confirmado por 2 muestras a cada
     * lado. Devuelve (t, mag) o null.
     */
    private fun tryConfirmPeak(): Sample? {
        val buf = magBuf
        if (buf.size < 5) return null

        // kotlin.collections.ArrayDeque no tiene indexado negativo tipo
        // Python; lo emulamos accediendo por posicion absoluta desde el
        // final via get(index), que si soporta (a diferencia de
        // java.util.ArrayDeque).
        val list = buf
        val n = list.size
        val candidate = list[n - 3]   // buf[-3]  (i-2)
        val m2 = list[n - 5]          // buf[-5]  (i-4)
        val m1 = list[n - 4]          // buf[-4]  (i-3)
        val p1 = list[n - 2]          // buf[-2]  (i-1)
        val p2 = list[n - 1]          // buf[-1]  (i)

        val mC = candidate.v
        val mM2 = m2.v
        val mM1 = m1.v
        val mP1 = p1.v
        val mP2 = p2.v

        // mC > mM2 (estricto) sigue garantizando que solo la PRIMERA
        // muestra de una meseta se confirme (las siguientes tienen
        // mM2 == mC). mC >= mP2 (antes estricto) se relaja para tolerar
        // saturacion del sensor: cuando accel_x clippea en su rango
        // maximo, el pico real queda como una meseta de varias muestras
        // identicas y exigir mC > mP2 estricto nunca se cumple en ningun
        // punto de la meseta, perdiendo el golpe por completo.
        if (mC >= mM1 && mC >= mP1 && mC > mM2 && mC >= mP2 && mC >= MIN_PEAK_MAG) {
            return Sample(candidate.t, mC)
        }
        return null
    }

    /**
     * True si el valle entre el hit previo confirmado y el candidato es
     * suficientemente profundo/sostenido como para tratarlos como dos
     * golpes distintos (en vez de rebote del mismo).
     */
    private fun valleyOkToSeparate(prevHit: ConfirmedHit, candT: Long, candMag: Double): Boolean {
        val prevT = prevHit.t
        val seg = magBuf.filter { it.t in prevT..candT }
        if (seg.isEmpty()) return true

        val vals = seg.map { it.v }
        val ts = seg.map { it.t }
        val valleyVal = vals.min()
        val thresh = VALLEY_RATIO * minOf(prevHit.mag, candMag)

        val belowIdx = vals.indices.filter { vals[it] <= thresh }
        val sustainedMs = if (belowIdx.isNotEmpty()) {
            ts[belowIdx.last()] - ts[belowIdx.first()]
        } else {
            0L
        }

        return (valleyVal <= thresh) && (sustainedMs >= VALLEY_MIN_MS)
    }

    /**
     * Un candidato pasa los filtros anti-rebote y de pendiente -> se
     * confirma como hit definitivo.
     */
    private fun confirmHit(tHit: Long, mag: Double) {
        val slope = attackSlope(tHit, mag)

        // 1. filtro RELATIVO (contra la mediana corriente): solo tiene
        // sentido cerca en tiempo del ultimo hit confirmado, que es donde
        // "pendiente mucho mas floja que la mediana" distingue cola de
        // rebote de golpe nuevo. Lejos de esa ventana, una pendiente lenta
        // ya no puede ser rebote de nada -- es un golpe real mas suave.
        val nearLastHit = lastHit != null && (tHit - lastHit!!.t) <= SLOPE_FILTER_WINDOW_MS
        if (nearLastHit) {
            val median = medianSlope()
            if (median != null && slope != null) {
                if (slope < SLOPE_REL_THRESH * median) {
                    return // descartado: pendiente de ataque demasiado lenta (cola de rebote)
                }
            } else if (median == null && slope != null) {
                if (slope < SLOPE_REL_THRESH * SLOPE_FALLBACK) {
                    return
                }
            }
        }

        // 2. piso ABSOLUTO, siempre activo sin importar la distancia
        // temporal: cubre rebotes que caen fuera de SLOPE_FILTER_WINDOW_MS
        // pero cuya pendiente sigue siendo claramente de cola de rebote, no
        // de golpe real.
        if (slope != null && slope < SLOPE_ABS_MIN) {
            return
        }

        val hit = ConfirmedHit(t = tHit, mag = mag, slope = slope)
        lastHit = hit
        if (slope != null && slope > 0) {
            pushSlope(slope)
        }

        // arranca la espera de roll fresco para clasificar este hit
        pendingClassification = hit
    }

    /**
     * Si hay un hit confirmado esperando clasificacion y ya llego una
     * muestra de roll con t >= t_hit, lo clasifica y lo agrega a
     * [results] (y dispara [onHitClassified]).
     */
    private fun maybeClassifyPending(currentT: Long) {
        val pending = pendingClassification ?: return
        val tHit = pending.t
        if (currentT < tHit) return

        // tomar la primera muestra de roll disponible en t >= t_hit (el
        // valor que haya en ese instante, sin esperar ninguna ventana)
        var rollVal: Double? = null
        var rollT: Long? = null
        for (sample in rollBuf) {
            if (sample.t >= tHit) {
                rollVal = sample.v
                rollT = sample.t
                break
            }
        }
        if (rollVal == null || rollT == null) {
            return // todavia no llego esa muestra, esperar al proximo frame
        }

        val label = if (rollVal < TH_ROLL) HitLabel.L else HitLabel.H
        val latencyMs = rollT - tHit
        val classified = ClassifiedHit(
            tHit = tHit,
            label = label,
            rollUsed = rollVal,
            decisionLatencyMs = latencyMs,
        )
        _results.add(classified)
        pendingClassification = null
        onHitClassified?.invoke(classified)
    }

    /**
     * Alimenta un frame de datos al motor: timestamp en ms, accel_x con
     * signo (crudo del acelerometro, sin convertir a magnitud antes de
     * llamar a esta funcion), y el ultimo roll conocido (en grados, de
     * -180 a 180 o el rango que entregue el sensor de rotacion).
     *
     * NOTA sobre tasas distintas: accel_x y roll pueden -y van a- llegar a
     * tasas distintas (acelerometro ~400Hz, rotation vector ~10ms/~100Hz
     * tipico, pero variable). Esta funcion espera que quien la llama
     * (la capa de sensores) ya resolvio eso manteniendo el "ultimo roll
     * conocido" y pasandolo en cada frame de accel_x, igual que hacia el
     * CSV original con valores de roll repetidos entre actualizaciones.
     */
    fun onFrame(t: Long, accelX: Double, roll: Double) {
        // usar accel_x con signo: solo el lobulo positivo corresponde al
        // golpe real (ver nota 3b en el doc de la clase). El pre-swing
        // negativo se descarta tratandolo como magnitud 0, asi nunca
        // compite como candidato a pico ni distorsiona el valle.
        val mag = if (accelX > 0) accelX else 0.0
        pushBounded(magBuf, MAG_BUF_CAPACITY, Sample(t, mag))
        pushBounded(rollBuf, ROLL_BUF_CAPACITY, Sample(t, roll))

        val cand = tryConfirmPeak()
        if (cand != null) {
            val (candT, candMag) = cand
            val prev = lastHit
            if (prev == null) {
                confirmHit(candT, candMag)
            } else {
                val withinMerge = (candT - prev.t) <= MERGE_WINDOW_MS
                if (withinMerge && !valleyOkToSeparate(prev, candT, candMag)) {
                    // rebote del mismo golpe: si el candidato es mayor, lo
                    // que ya se reporto como hit queda igual (ya fue
                    // clasificado o esta en cola) - no se deshace. Solo
                    // evitamos reportar un segundo hit espurio.
                } else {
                    confirmHit(candT, candMag)
                }
            }
        }

        maybeClassifyPending(t)
    }

    /** Resetea todo el estado interno (util para empezar una nueva sesion de calibracion/juego). */
    fun reset() {
        magBuf.clear()
        rollBuf.clear()
        lastHit = null
        recentSlopes.clear()
        pendingClassification = null
        _results.clear()
    }
}
