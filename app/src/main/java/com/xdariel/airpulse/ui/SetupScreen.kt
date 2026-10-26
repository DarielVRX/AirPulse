package com.xdariel.airpulse.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xdariel.airpulse.engine.DrumPiece

/**
 * Pantalla de seleccion de piezas (lista fija de 8, sin kick ni pedal de
 * hi-hat -- ver comentario en [DrumPiece]). Sin diseño, solo funcional.
 */
@Composable
fun SetupScreen(
    selectedPieces: Set<DrumPiece>,
    onTogglePiece: (DrumPiece) -> Unit,
    onContinue: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "AirPulse - Elegi tus piezas")
        Text(text = "(Kick y pedal de hi-hat no estan disponibles: se tocan con el pie, no con un golpe en el aire)")

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp)) {
            items(DrumPiece.values().toList()) { piece ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    Checkbox(
                        checked = piece in selectedPieces,
                        onCheckedChange = { onTogglePiece(piece) },
                    )
                    Text(text = piece.displayName)
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Seleccionadas: ${selectedPieces.size}")
            Button(
                onClick = onContinue,
                enabled = selectedPieces.isNotEmpty(),
            ) {
                Text(text = "Continuar a calibracion")
            }
        }
    }
}
