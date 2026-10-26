package com.xdariel.airpulse.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager as AndroidSensorManager
import com.xdariel.airpulse.engine.AirPulseDetector

/**
 * Puente entre los sensores fisicos de Android y el motor de deteccion
 * [StreamingAirdrumEngine] + [AzimuthStreamTracker].
 *
 * Se suscribe a:
 * - [Sensor.TYPE_ACCELEROMETER]: acelerometro crudo (NO linear
 *   acceleration). El script Python de referencia fue calibrado sobre
 *   accel_x crudo del acelerometro (incluye gravedad), asi que para ser
 *   fieles al comportamiento validado usamos el mismo sensor, no
 *   TYPE_LINEAR_ACCELERATION (que resta la gravedad con un filtro propio
 *   de Android, con su propia latencia/comportamiento, nunca validado
 *   contra los datos de referencia).
 * - [Sensor.TYPE_ROTATION_VECTOR]: se convierte a roll/azimuth/pitch con
 *   las funciones estandar de Android (getRotationMatrixFromVector +
 *   getOrientation).
 *
 * UNIDADES DE TIEMPO: `SensorEvent.timestamp` viene en NANOSEGUNDOS, en la
 * base de tiempo del reloj del sistema de eventos de sensores
 * (equivalente a `SystemClock.elapsedRealtimeNanos()`, NO a
 * `System.currentTimeMillis()`). El motor de deteccion espera
 * MILISEGUNDOS (para ser consistente con `timestamp_ms` del CSV
 * original), asi que esta clase divide por 1_000_000 antes de llamar a
 * `onFrame`. Como ambos sensores comparten la misma base de tiempo de
 * eventos, la resta/comparacion de timestamps entre accel y rotation
 * vector sigue siendo valida despues de la conversion (ambos se dividen
 * igual).
 *
 * SINCRONIZACION ENTRE SENSORES: el acelerometro y el rotation vector NO
 * llegan sincronizados ni a la misma tasa (acelerometro ~400Hz en el
 * dispositivo de referencia; rotation vector tipicamente cada ~10ms pero
 * variable segun el fusion sensor del fabricante). Esta clase mantiene
 * siempre el "ultimo valor conocido" de roll/azimuth (actualizado cada vez
 * que llega un evento de TYPE_ROTATION_VECTOR) y lo usa para CADA frame de
 * accel_x que llega, exactamente igual que el CSV de referencia traia
 * columnas de roll/azimuth con valores repetidos entre actualizaciones
 * reales del sensor de rotacion.
 */
class AirPulseSensorListener(
    context: Context,
    private val detector: AirPulseDetector,
) : SensorEventListener {

    private val sensorManager: AndroidSensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as AndroidSensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val rotationVectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // ultimo valor conocido de orientacion, actualizado por cada evento de
    // TYPE_ROTATION_VECTOR y reutilizado para cada frame de accel_x hasta
    // que llegue una actualizacion nueva.
    private var lastRollDeg: Double = 0.0
    private var lastAzimuthDeg: Double = 0.0

    private val rotationMatrix = FloatArray(9)
    private val orientationRad = FloatArray(3)

    /** Callback opcional para exponer roll/azimuth actuales a la UI (debug). */
    var onOrientationUpdated: ((rollDeg: Double, azimuthDeg: Double) -> Unit)? = null

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, AndroidSensorManager.SENSOR_DELAY_FASTEST)
        }
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, AndroidSensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event)
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
        }
    }

    private fun handleRotationVector(event: SensorEvent) {
        AndroidSensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        AndroidSensorManager.getOrientation(rotationMatrix, orientationRad)

        // getOrientation() devuelve [azimuth, pitch, roll] en RADIANES:
        //   orientationRad[0] = azimuth (rotacion alrededor de -Z)
        //   orientationRad[1] = pitch   (rotacion alrededor de X)
        //   orientationRad[2] = roll    (rotacion alrededor de Y)
        val azimuthDeg = Math.toDegrees(orientationRad[0].toDouble())
        val rollDeg = Math.toDegrees(orientationRad[2].toDouble())

        lastAzimuthDeg = azimuthDeg
        lastRollDeg = rollDeg

        onOrientationUpdated?.invoke(rollDeg, azimuthDeg)
    }

    private fun handleAccelerometer(event: SensorEvent) {
        val tMs = event.timestamp / 1_000_000L
        val accelX = event.values[0].toDouble()

        detector.feedFrame(tMs, accelX, lastRollDeg, lastAzimuthDeg)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op: no se necesita reaccionar a cambios de precision para esta app
    }
}
