package com.xdariel.airpulse.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xdariel.airpulse.audio.AudioEngine
import com.xdariel.airpulse.engine.AirPulseDetector
import com.xdariel.airpulse.engine.CalibratedPosition
import com.xdariel.airpulse.engine.DrumPiece
import com.xdariel.airpulse.engine.PositionMatcher
import com.xdariel.airpulse.logging.PlaySessionLogger
import com.xdariel.airpulse.sensors.AirPulseSensorListener

/**
 * Pantalla actual de la navegacion simple (Setup -> Calibration -> Play).
 */
enum class AppScreen { SETUP, CALIBRATION, PLAY }

/** Un hit ya resuelto a una pieza, para mostrar en [PlayScreen] como debug visual. */
data class PlayedHit(
    val index: Int,
    val piece: DrumPiece,
    val roll: Double,
    val azimuth: Double?,
)

/**
 * ViewModel compartido entre las 3 pantallas. Mantiene la calibracion
 * SOLO en memoria de la sesion (no hay persistencia a disco todavia, ver
 * README.md "Pendiente de diseño").
 *
 * NO persiste `detector`/`sensorListener` entre rotaciones de pantalla de
 * forma especial mas alla de lo que ya da AndroidViewModel: al ser una v1
 * minima con `android:screenOrientation="portrait"` fijo en el manifest,
 * no hay reconfiguracion de orientacion que gestionar.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    var screen by mutableStateOf(AppScreen.SETUP)
        private set

    // --- Setup ---
    var selectedPieces by mutableStateOf<Set<DrumPiece>>(emptySet())
        private set

    fun togglePiece(piece: DrumPiece) {
        selectedPieces = if (piece in selectedPieces) {
            selectedPieces - piece
        } else {
            selectedPieces + piece
        }
    }

    fun confirmSetupAndGoToCalibration() {
        if (selectedPieces.isEmpty()) return
        calibrationQueue = selectedPieces.toList()
        calibrationIndex = 0
        calibratedPositions = emptyList()
        startCalibrationForCurrentPiece()
        screen = AppScreen.CALIBRATION
    }

    // --- Calibration ---
    var calibrationQueue by mutableStateOf<List<DrumPiece>>(emptyList())
        private set
    var calibrationIndex by mutableStateOf(0)
        private set
    var hitsForCurrentPiece by mutableStateOf(0)
        private set
    var calibratedPositions by mutableStateOf<List<CalibratedPosition>>(emptyList())
        private set

    /**
     * Pieza con la que la ultima calibracion intentada quedo "solapada"
     * (distancia menor a [PositionMatcher.MIN_SEPARATION_DEG]), o null si
     * no hay conflicto activo. La UI (CalibrationScreen) usa esto para
     * mostrar el indicador visual y el mensaje de "repeti el gesto".
     * Se limpia automaticamente al empezar a recibir golpes de nuevo para
     * la misma pieza.
     */
    var conflictWithPiece by mutableStateOf<DrumPiece?>(null)
        private set

    // acumuladores circulares/lineales de los golpes recibidos para la pieza actual
    private val currentRolls = mutableListOf<Double>()
    private val currentAzimuths = mutableListOf<Double>()

    companion object {
        const val HITS_PER_PIECE_CALIBRATION = 3
    }

    val currentCalibrationPiece: DrumPiece?
        get() = calibrationQueue.getOrNull(calibrationIndex)

    private fun startCalibrationForCurrentPiece() {
        currentRolls.clear()
        currentAzimuths.clear()
        hitsForCurrentPiece = 0
        conflictWithPiece = null
        // NO se llama detector.reset() aqui: eso borraria lastHit del motor
        // de golpes justo en la transicion entre piezas, que es donde mas
        // falta hace el filtro anti-rebote (el golpe de cierre de una pieza
        // suele ser mas fuerte y es cuando mas probable es un rebote fisico
        // real). Sin lastHit, el primer candidato tras el reset se confirma
        // sin pasar por el filtro (ver StreamingAirdrumEngine.onFrame: rama
        // "prev == null"), generando un golpe fantasma contado para la
        // pieza siguiente. El motor de golpes sigue vivo entre piezas; solo
        // se reinicia de verdad al volver a Setup (ver restartToSetup()).
    }

    /** Llamado desde CalibrationScreen por cada golpe detectado en vivo. */
    fun onCalibrationHit(roll: Double, azimuth: Double?) {
        val piece = currentCalibrationPiece ?: return
        currentRolls.add(roll)
        if (azimuth != null) {
            currentAzimuths.add(azimuth)
        }
        hitsForCurrentPiece += 1

        if (hitsForCurrentPiece >= HITS_PER_PIECE_CALIBRATION) {
            val centerRoll = currentRolls.average()
            // promedio CIRCULAR de azimuth (no aritmetico simple): azimuth
            // es circular ±180°, y un promedio aritmetico naive falla cerca
            // de ese limite (ver PositionMatcher.circularMeanAzimuth).
            val centerAzimuth = if (currentAzimuths.isNotEmpty()) {
                PositionMatcher.circularMeanAzimuth(currentAzimuths)
            } else {
                0.0
            }
            val candidate = CalibratedPosition(piece, centerRoll, centerAzimuth)

            // chequeo anti-solape: si la posicion recien calibrada queda
            // demasiado cerca de una ya calibrada (ver
            // PositionMatcher.MIN_SEPARATION_DEG), se descarta y se pide
            // repetir el gesto para ESTA MISMA pieza en vez de avanzar.
            // No se empuja/ajusta nada automaticamente: el usuario decide
            // donde reubicarla.
            val conflict = PositionMatcher.findConflict(candidate, calibratedPositions)
            if (conflict != null) {
                conflictWithPiece = conflict.piece
                currentRolls.clear()
                currentAzimuths.clear()
                hitsForCurrentPiece = 0
                return
            }

            calibratedPositions = calibratedPositions + candidate

            calibrationIndex += 1
            if (calibrationIndex >= calibrationQueue.size) {
                startPlaySession()
            } else {
                startCalibrationForCurrentPiece()
            }
        }
    }

    // --- Play ---
    var playedHits by mutableStateOf<List<PlayedHit>>(emptyList())
        private set

    private var playHitCounter = 0

    /**
     * Log de diagnostico de la sesion de Play actual: timestamp_ms,
     * accel_x, roll, azimuth, pieza_sonada (solo en frames de hit). Se
     * exporta automaticamente a Descargas/airpulse-logger/ al salir de
     * Play (ver [restartToSetup]), con nombre unico por timestamp. Pensado
     * para comparar, offline, el comportamiento real de Play contra las
     * grabaciones de referencia (00Az.csv, 038.csv) cuando el matching da
     * resultados peores de lo esperado.
     */
    private val playSessionLogger = PlaySessionLogger()

    /** Ultima ruta donde se guardo el log de Play (para mostrar en PlayScreen), o null si aun no se exporto nada. */
    var lastExportedLogPath by mutableStateOf<String?>(null)
        private set

    private fun startPlaySession() {
        playSessionLogger.reset()
        sensorListener.sessionLogger = playSessionLogger
        lastExportedLogPath = null
        screen = AppScreen.PLAY
    }

    fun onPlayHit(roll: Double, azimuth: Double?) {
        val az = azimuth ?: return // sin azimuth (gesto demasiado rapido/corto) no se puede posicionar: se ignora este golpe para el disparo de sonido
        val closest = PositionMatcher.closestPosition(roll, az, calibratedPositions) ?: return
        playHitCounter += 1
        playedHits = (playedHits + PlayedHit(playHitCounter, closest.piece, roll, az)).takeLast(50)
        playSessionLogger.markLastFrameAsHit(closest.piece.displayName)
        audioEngine.play(closest.piece)
    }

    fun restartToSetup() {
        detector.reset()
        // exporta el log de la sesion de Play que termina (si hubo alguna)
        // ANTES de limpiar/desactivar el logger, con timestamp unico en
        // Descargas/airpulse-logger/. No bloquea ni falla el flujo si no
        // hay nada que exportar o si la escritura falla (ver
        // PlaySessionLogger.writeToDownloads).
        if (screen == AppScreen.PLAY) {
            lastExportedLogPath = playSessionLogger.writeToDownloads(getApplication())
        }
        sensorListener.sessionLogger = null
        selectedPieces = emptySet()
        calibrationQueue = emptyList()
        calibrationIndex = 0
        calibratedPositions = emptyList()
        playedHits = emptyList()
        playHitCounter = 0
        screen = AppScreen.SETUP
    }

    // --- Motor + audio + sensores, compartidos entre Calibration y Play ---
    val detector = AirPulseDetector()
    val audioEngine = AudioEngine(application)
    val sensorListener = AirPulseSensorListener(application, detector)

    init {
        detector.onPositionedHit = { hit ->
            when (screen) {
                AppScreen.CALIBRATION -> onCalibrationHit(hit.roll, hit.azimuth)
                AppScreen.PLAY -> onPlayHit(hit.roll, hit.azimuth)
                AppScreen.SETUP -> Unit
            }
        }
    }

    fun startSensors() = sensorListener.start()
    fun stopSensors() = sensorListener.stop()

    override fun onCleared() {
        super.onCleared()
        sensorListener.stop()
        audioEngine.release()
    }
}
