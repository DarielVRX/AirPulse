package com.xdariel.airpulse.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Modo juego: corre el motor en vivo, y por cada hit confirmado dispara
 * el sonido de la posicion calibrada mas cercana (ver
 * [com.xdariel.airpulse.engine.PositionMatcher] -- SIEMPRE dispara algo,
 * sin rechazo por distancia, segun especificacion). Muestra en pantalla
 * (texto simple, sin diseño) que posicion/pieza sono por cada golpe, para
 * debug visual.
 */
@Composable
fun PlayScreen(
    playedHits: List<PlayedHit>,
    onRestart: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "AirPulse - Modo juego")
        Text(text = "Golpea en el aire en las posiciones calibradas.")

        Button(onClick = onRestart) {
            Text(text = "Volver a Setup")
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            items(playedHits.reversed()) { hit ->
                Text(
                    text = "#${hit.index}: ${hit.piece.displayName}  " +
                        "(roll=${"%.1f".format(hit.roll)}, azimuth=${hit.azimuth?.let { "%.1f".format(it) } ?: "?"})",
                )
            }
        }
    }
}
