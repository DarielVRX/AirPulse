package com.xdariel.airpulse.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.xdariel.airpulse.R
import com.xdariel.airpulse.engine.DrumPiece

/**
 * Motor de audio basado en [SoundPool], elegido sobre MediaPlayer por su
 * latencia mucho menor para disparos cortos y frecuentes (ideal para un
 * golpe de bateria que debe sonar lo antes posible despues de detectado).
 *
 * Los sonidos son WAV sinteticos generados por
 * `tools/generate_synthetic_drums.py` (ver ese script y README.md) porque
 * todavia no hay samples reales de percusion decididos. Reemplazarlos es
 * tan simple como sobreescribir los archivos en `res/raw/` con el mismo
 * nombre (o cambiar el mapeo en [DrumPiece.soundResName] + el `when` de
 * [resIdFor] si se usan nombres distintos).
 */
class AudioEngine(context: Context) {

    private val appContext = context.applicationContext

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8) // hasta 8 posiciones pueden sonar casi simultaneamente
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    // soundId de SoundPool por pieza, cargado una vez en el constructor.
    private val soundIds: Map<DrumPiece, Int> = DrumPiece.values().associateWith { piece ->
        soundPool.load(appContext, resIdFor(piece), /* priority = */ 1)
    }

    private fun resIdFor(piece: DrumPiece): Int = when (piece) {
        DrumPiece.SNARE -> R.raw.drum_snare
        DrumPiece.HI_HAT_CLOSED -> R.raw.drum_hihat_closed
        DrumPiece.HI_HAT_OPEN -> R.raw.drum_hihat_open
        DrumPiece.CRASH -> R.raw.drum_crash
        DrumPiece.RIDE -> R.raw.drum_ride
        DrumPiece.TOM_HIGH -> R.raw.drum_tom_high
        DrumPiece.TOM_MID -> R.raw.drum_tom_mid
        DrumPiece.TOM_LOW -> R.raw.drum_tom_low
    }

    /** Dispara el sonido de [piece] inmediatamente (volumen fijo, sin variacion por velocidad del golpe todavia). */
    fun play(piece: DrumPiece) {
        val soundId = soundIds[piece] ?: return
        soundPool.play(
            soundId,
            /* leftVolume = */ 1.0f,
            /* rightVolume = */ 1.0f,
            /* priority = */ 1,
            /* loop = */ 0,
            /* rate = */ 1.0f,
        )
    }

    /** Libera los recursos nativos de SoundPool. Llamar desde onDestroy()/DisposableEffect. */
    fun release() {
        soundPool.release()
    }
}
