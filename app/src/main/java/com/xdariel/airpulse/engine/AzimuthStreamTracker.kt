package com.xdariel.airpulse.engine

// Usamos kotlin.collections.ArrayDeque (scope por defecto), que soporta
// acceso indexado get(i) a diferencia de java.util.ArrayDeque. Ver nota
// equivalente en StreamingAirdrumEngine.kt.
import kotlin.math.abs

/**
 * Tracker de azimuth en modo streaming, frame a frame, sin mirar el futuro.
 *
 * NO esta en el script Python de referencia (`realtime_airdrum.py`): esta
 * clase es nueva y se implementa aqui a partir de una descripcion textual
 * validada manualmente en notebooks ad-hoc (no hay codigo Python final de
 * esto todavia). Fue validada con exito en 2 sesiones reales (5/5 y 4/5
 * tramos correctos, con la unica falla atribuida a ejecucion real del
 * usuario, no al metodo), pero a diferencia del motor de deteccion de
 * golpes, no cuenta con el mismo nivel de prueba exhaustiva contra CSVs.
 *
 * Idea central: cuando el brazo esta en plena fase de ataque (acelerando
 * hacia el golpe), el azimuth que reporta el sensor de rotacion durante
 * ese tramo NO es representativo de "donde" esta la posicion del golpe
 * (el cuerpo/muneca sigue rotando). El azimuth representativo de la
 * posicion es el que se observa en la MESETA -- el tramo mas "plano" (de
 * menor derivada) del movimiento, que tipicamente ocurre justo antes del
 * ataque (el usuario se detiene un instante en la posicion elegida antes
 * de golpear) o inmediatamENTE despues si el gesto es muy rapido.
 *
 * Por eso:
 * 1. Mientras el tracker detecta que accel_x esta en fase de ataque
 *    (pendiente sostenidamente positiva Y accel_x > ATTACK_ACCEL_THRESHOLD,
 *    usando una ventana corta de muestras), se CONGELA la busqueda de
 *    meseta de azimuth: no se actualiza el candidato. Esto evita que la
 *    rotacion del brazo durante el propio golpe contamine la estimacion
 *    de la posicion.
 * 2. Fuera de la fase de ataque, se busca el punto de azimuth con la
 *    derivada mas chica (mas plano) usando una derivada corta suavizada:
 *    la diferencia entre muestras separadas por al menos
 *    [MIN_DERIVATIVE_GAP_MS] (~30ms), para evitar ruido de repeticion del
 *    sensor (el rotation vector puede repetir el mismo valor varias veces
 *    seguidas a la tasa de llegada de SensorEvent sin que eso signifique
 *    una derivada real de 0 instantanea -- hay que mirar un salto minimo
 *    de tiempo para que la diferencia sea significativa).
 * 3. Al confirmarse un nuevo hit (señal externa: el motor de golpes avisa
 *    "hit confirmado en t_hit"), se reporta el candidato de menor derivada
 *    entre los vistos en los ultimos [AZIMUTH_LOOKBACK_MS] antes de ese
 *    instante (NO todo el tramo desde el hit anterior: en Play el usuario
 *    puede tardar en moverse a la proxima pieza, y mesetas de transito
 *    lejanas en el tiempo no representan la posicion del proximo golpe),
 *    y se reinicia la acumulacion para el proximo tramo.
 *
 * Manejo circular: el azimuth es circular (-180..+180 grados, o 0..360
 * segun convencion). Las diferencias usadas para la derivada se calculan
 * con la distancia angular circular mas corta, NO con resta aritmetica
 * directa, para no reportar una derivada gigante espuria al cruzar el
 * limite +-180.
 */
class AzimuthStreamTracker {

    companion object {
        // pendiente de accel_x sostenidamente positiva Y accel_x >
        // ATTACK_ACCEL_THRESHOLD: umbral razonable, bastante por debajo de
        // MIN_PEAK_MAG (30.0) del motor de golpes, para detectar que el
        // ataque ya arranco ANTES de que el pico se confirme (que recien se
        // confirma con 2 muestras de margen despues del maximo real).
        const val ATTACK_ACCEL_THRESHOLD = 20.0

        // tamanio de la ventana corta usada para decidir "pendiente
        // sostenidamente positiva" (todas las diferencias consecutivas
        // dentro de la ventana deben ser >= 0, con al menos una > 0).
        const val ATTACK_WINDOW_SIZE = 5

        // separacion minima en ms entre dos muestras para que su diferencia
        // de azimuth cuente como derivada valida (evita ruido de
        // repeticion del sensor de rotacion, que puede entregar el mismo
        // valor en eventos consecutivos muy cercanos en el tiempo).
        const val MIN_DERIVATIVE_GAP_MS = 30L

        // ventana de buffer de azimuth: suficiente para cubrir el gap
        // minimo de derivada mas algo de margen, sin crecer sin limite.
        private const val AZIMUTH_BUF_CAPACITY = 60
        private const val ACCEL_WINDOW_BUF_CAPACITY = ATTACK_WINDOW_SIZE + 2

        // BUG FIX: el candidato "mas plano" se buscaba sobre TODO el tramo
        // desde el hit anterior, sin limite de tiempo. En Play, entre un
        // golpe y el siguiente el usuario mueve el brazo a otra posicion
        // (otra pieza); ese transito puede incluir mesetas espurias (fuera
        // de fase de ataque segun el filtro) que compiten -y a veces ganan-
        // contra la meseta real justo antes del proximo golpe, reportando
        // la posicion de una pieza distinta. Se limita la busqueda a los
        // ultimos AZIMUTH_LOOKBACK_MS antes del momento de reporte, misma
        // magnitud que SLOPE_FILTER_WINDOW_MS del motor de golpes.
        const val AZIMUTH_LOOKBACK_MS = 150L
    }

    private data class Sample(val t: Long, val v: Double)

    /** Mejor candidato visto en el tramo actual: azimuth y la derivada (abs) que lo justifico. */
    private data class Candidate(val azimuth: Double, val derivative: Double, val t: Long)

    private val accelWindow = ArrayDeque<Sample>()   // ultimas N muestras de accel_x (con signo) para detectar fase de ataque
    private val azimuthBuf = ArrayDeque<Sample>()    // ultimas muestras de azimuth, para calcular la derivada corta

    // ahora se guardan TODOS los candidatos vistos en el tramo (no solo el
    // mejor global), para poder filtrar por tiempo al momento de reportar
    // (ver AZIMUTH_LOOKBACK_MS). La lista se acota igual que azimuthBuf.
    private val candidates = ArrayDeque<Candidate>()

    /** Azimuth reportado para el ultimo tramo cerrado (consumido por quien llama a [onHitConfirmed]). */
    var lastReportedAzimuth: Double? = null
        private set

    private fun pushBounded(buf: ArrayDeque<Sample>, capacity: Int, sample: Sample) {
        buf.addLast(sample)
        while (buf.size > capacity) {
            buf.removeFirst()
        }
    }

    /**
     * True si, mirando la ventana corta de accel_x, el movimiento esta en
     * fase de ataque: pendiente sostenidamente positiva (ninguna caida
     * entre muestras consecutivas) Y la ultima muestra supera
     * [ATTACK_ACCEL_THRESHOLD].
     */
    private fun isInAttackPhase(): Boolean {
        if (accelWindow.size < ATTACK_WINDOW_SIZE) return false

        val list = accelWindow
        val n = list.size
        val startIdx = n - ATTACK_WINDOW_SIZE

        var sawIncrease = false
        for (i in startIdx until n) {
            if (i == startIdx) continue
            val prev = list[i - 1].v
            val cur = list[i].v
            if (cur < prev) {
                return false // pendiente no sostenidamente positiva: hubo una caida
            }
            if (cur > prev) sawIncrease = true
        }
        val lastVal = list[n - 1].v
        return sawIncrease && lastVal > ATTACK_ACCEL_THRESHOLD
    }

    /** Distancia angular circular mas corta entre dos azimuths en grados (-180..180). */
    private fun circularDiff(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    /**
     * Busca, entre las muestras separadas por al menos
     * [MIN_DERIVATIVE_GAP_MS] de la muestra actual, la derivada corta
     * suavizada (|diferencia angular| / dt) y, si es la mas chica vista en
     * este tramo, lo agrega a [candidates].
     */
    private fun updateFlattestCandidate(currentT: Long, currentAzimuth: Double) {
        // buscar la muestra mas reciente que este al menos
        // MIN_DERIVATIVE_GAP_MS en el pasado (recorriendo el buffer de atras
        // hacia adelante, que es mas eficiente ya que esta ordenado por t
        // ascendente y buscamos la mas cercana que cumpla el gap minimo).
        var reference: Sample? = null
        for (sample in azimuthBuf) {
            if (currentT - sample.t >= MIN_DERIVATIVE_GAP_MS) {
                reference = sample // se queda con la ultima (mas cercana) que cumpla
            }
        }
        if (reference == null) return

        val dt = (currentT - reference.t).toDouble()
        if (dt <= 0) return

        val diff = abs(circularDiff(currentAzimuth, reference.v))
        val derivative = diff / dt

        pushBounded(candidates, AZIMUTH_BUF_CAPACITY, Candidate(azimuth = currentAzimuth, derivative = derivative, t = currentT))
    }

    // sobrecarga generica de pushBounded para la lista de candidatos (no
    // son Sample, reutilizamos la misma logica de tope de capacidad).
    private fun <T> pushBounded(buf: ArrayDeque<T>, capacity: Int, item: T) {
        buf.addLast(item)
        while (buf.size > capacity) {
            buf.removeFirst()
        }
    }

    /**
     * Alimenta un frame de accel_x (con signo, crudo) y azimuth (ultimo
     * valor conocido, en grados). Debe llamarse en el mismo frame-rate /
     * orden que [StreamingAirdrumEngine.onFrame] para los mismos datos de
     * accel_x, de forma que la deteccion de fase de ataque quede
     * sincronizada con el motor de golpes.
     */
    fun onFrame(t: Long, accelX: Double, azimuth: Double) {
        pushBounded(accelWindow, ACCEL_WINDOW_BUF_CAPACITY, Sample(t, accelX))

        if (isInAttackPhase()) {
            // fase de ataque: se congela la busqueda de meseta, no se
            // actualiza el candidato ni siquiera se agrega la muestra de
            // azimuth al buffer de derivada (para no sesgar derivadas
            // futuras usando una muestra tomada en pleno giro de ataque).
            return
        }

        updateFlattestCandidate(t, azimuth)
        pushBounded(azimuthBuf, AZIMUTH_BUF_CAPACITY, Sample(t, azimuth))
    }

    /**
     * Se llama cuando el motor de golpes confirma un nuevo hit. Devuelve el
     * mejor candidato de azimuth acumulado desde el hit anterior (o null si
     * no hubo ninguna muestra fuera de fase de ataque en el tramo, caso
     * borde improbable pero posible con gestos muy rapidos), y reinicia la
     * acumulacion para el proximo tramo.
     */
    fun onHitConfirmed(hitT: Long): Double? {
        // solo candidatos dentro de AZIMUTH_LOOKBACK_MS antes del hit (ver
        // comentario en companion object): descarta mesetas de transito
        // lejanas en el tiempo, que no representan la posicion de este golpe.
        val inWindow = candidates.filter { hitT - it.t <= AZIMUTH_LOOKBACK_MS }
        val azimuth = inWindow.minByOrNull { it.derivative }?.azimuth
        lastReportedAzimuth = azimuth
        candidates.clear()
        return azimuth
    }

    /** Resetea todo el estado interno. */
    fun reset() {
        accelWindow.clear()
        azimuthBuf.clear()
        candidates.clear()
        lastReportedAzimuth = null
    }
}
