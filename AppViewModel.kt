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
        detector.reset()
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
            calibratedPositions = calibratedPositions + CalibratedPosition(piece, centerRoll, centerAzimuth)

            calibrationIndex += 1
            if (calibrationIndex >= calibrationQueue.size) {
                screen = AppScreen.PLAY
            } else {
                startCalibrationForCurrentPiece()
            }
        }
    }

    // --- Play ---
    var playedHits by mutableStateOf<List<PlayedHit>>(emptyList())
        private set

    private var playHitCounter = 0

    fun onPlayHit(roll: Double, azimuth: Double?) {
        val az = azimuth ?: return // sin azimuth (gesto demasiado rapido/corto) no se puede posicionar: se ignora este golpe para el disparo de sonido
        val closest = PositionMatcher.closestPosition(roll, az, calibratedPositions) ?: return
        playHitCounter += 1
        playedHits = (playedHits + PlayedHit(playHitCounter, closest.piece, roll, az)).takeLast(50)
        audioEngine.play(closest.piece)
    }

    fun restartToSetup() {
        detector.reset()
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
