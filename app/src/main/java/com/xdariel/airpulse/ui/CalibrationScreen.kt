package com.xdariel.airpulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 *
 * Indicador anti-solape: si los 3 golpes recien dados quedan demasiado
 * cerca de una pieza ya calibrada (ver
 * [com.xdariel.airpulse.engine.PositionMatcher.MIN_SEPARATION_DEG]), el
 * ViewModel descarta esa calibracion y pide repetirla (ver
 * [AppViewModel.conflictWithPiece]); esta pantalla lo muestra con un
 * fondo rojizo minimalista + mensaje, cubriendo tanto la pieza actual
 * como la pieza con la que choco.
 */
@Composable
fun CalibrationScreen(
    currentPiece: DrumPiece?,
    hitsForCurrentPiece: Int,
    hitsRequired: Int,
    pieceIndex: Int,
    totalPieces: Int,
    conflictWithPiece: DrumPiece?,
) {
    val backgroundColor = if (conflictWithPiece != null) Color(0xFFFFE0E0) else Color.Unspecified

    Column(modifier = Modifier.fillMaxSize().background(backgroundColor).padding(16.dp)) {
        Text(text = "Calibracion (${pieceIndex + 1}/$totalPieces)")

        if (currentPiece != null) {
            Text(text = "Golpea 3 veces en la posicion donde quieras: ${currentPiece.displayName}")
            Text(text = "Golpes recibidos: $hitsForCurrentPiece / $hitsRequired")

            if (conflictWithPiece != null) {
                Text(text = "Muy cerca de ${conflictWithPiece.displayName}: repeti el gesto en otra posicion")
            }
        } else {
            Text(text = "Calibracion completa.")
        }
    }
}
