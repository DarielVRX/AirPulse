package com.xdariel.airpulse.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xdariel.airpulse.engine.DrumPiece

/**
 * Pantalla de calibracion: por cada pieza elegida en Setup, pide golpear
 * 3 veces en la posicion deseada. Los golpes llegan via
 * [AppViewModel.onCalibrationHit] (alimentado por el motor en vivo +
 * [com.xdariel.airpulse.engine.AzimuthStreamTracker]); esta pantalla solo
 * MUESTRA el progreso, no procesa los golpes ella misma.
 *
 * Avanza automaticamente a la siguiente pieza tras 3 golpes validos
 * (logica en el ViewModel), y a PlayScreen cuando termina la ultima
 * pieza de la cola.
 */
@Composable
fun CalibrationScreen(
    currentPiece: DrumPiece?,
    hitsForCurrentPiece: Int,
    hitsRequired: Int,
    pieceIndex: Int,
    totalPieces: Int,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Calibracion (${pieceIndex + 1}/$totalPieces)")

        if (currentPiece != null) {
            Text(text = "Golpea 3 veces en la posicion donde quieras: ${currentPiece.displayName}")
            Text(text = "Golpes recibidos: $hitsForCurrentPiece / $hitsRequired")
        } else {
            Text(text = "Calibracion completa.")
        }
    }
}
