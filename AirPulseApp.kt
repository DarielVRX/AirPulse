package com.xdariel.airpulse.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Navegacion simple entre las 3 pantallas (Setup -> Calibration -> Play),
 * controlada por [AppViewModel.screen]. No se usa Navigation Compose
 * (innecesario para 3 pantallas lineales en esta v1 minima).
 */
@Composable
fun AirPulseApp(viewModel: AppViewModel = viewModel()) {
    when (viewModel.screen) {
        AppScreen.SETUP -> SetupScreen(
            selectedPieces = viewModel.selectedPieces,
            onTogglePiece = viewModel::togglePiece,
            onContinue = viewModel::confirmSetupAndGoToCalibration,
        )

        AppScreen.CALIBRATION -> CalibrationScreen(
            currentPiece = viewModel.currentCalibrationPiece,
            hitsForCurrentPiece = viewModel.hitsForCurrentPiece,
            hitsRequired = AppViewModel.HITS_PER_PIECE_CALIBRATION,
            pieceIndex = viewModel.calibrationIndex,
            totalPieces = viewModel.calibrationQueue.size,
        )

        AppScreen.PLAY -> PlayScreen(
            playedHits = viewModel.playedHits,
            onRestart = viewModel::restartToSetup,
        )
    }
}
