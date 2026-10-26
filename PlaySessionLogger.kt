package com.xdariel.airpulse.logging

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger simple para sesiones de Play: guarda, en memoria, una fila por
 * cada frame de accel_x recibido (con el roll/azimuth asociado en ese
 * instante) + que pieza sono en los frames que correspondan a un hit
 * confirmado. Formato reducido respecto al logger original
 * (airdrum-logger): solo las columnas que la app realmente usa
 * (timestamp_ms, accel_x, roll, azimuth, pieza_sonada), ya que roll+azimuth
 * solos dieron buenos resultados en las pruebas de validacion -- no hace
 * falta accel_y/z ni magnetometro crudo para diagnosticar.
 *
 * Pensado para diagnosticar, offline, por que una sesion de Play da
 * resultados peores que las sesiones grabadas con el logger original
 * (00Az.csv, 038.csv, etc.): permite re-correr el mismo tipo de analisis
 * (ver /tmp/airdrum_az/analyze.py de la sesion de diseño) sobre datos
 * reales de Play, no solo sobre grabaciones estaticas controladas.
 *
 * El archivo se escribe SOLO al terminar la sesion de Play (ver
 * [writeToDownloads]), no incrementalmente, para no competir por I/O con
 * el procesamiento en tiempo real del motor de deteccion.
 */
class PlaySessionLogger {

    private data class Row(
        val timestampMs: Long,
        val accelX: Double,
        val roll: Double,
        val azimuth: Double,
        val piezaSonada: String,
    )

    private val rows = mutableListOf<Row>()

    /** Registra un frame de accel_x con su roll/azimuth asociado (sin hit). */
    fun logFrame(timestampMs: Long, accelX: Double, roll: Double, azimuth: Double) {
        rows.add(Row(timestampMs, accelX, roll, azimuth, ""))
    }

    /**
     * Marca el ULTIMO frame registrado como el que correspondio a un hit
     * confirmado que sono como [pieceName]. Se llama justo despues de
     * [logFrame] para el mismo frame (ver AirPulseSensorListener), asi que
     * siempre existe al menos una fila para marcar.
     */
    fun markLastFrameAsHit(pieceName: String) {
        val lastIndex = rows.lastIndex
        if (lastIndex < 0) return
        rows[lastIndex] = rows[lastIndex].copy(piezaSonada = pieceName)
    }

    fun reset() {
        rows.clear()
    }

    val frameCount: Int get() = rows.size

    /**
     * Escribe el log acumulado a Downloads/airpulse-logger/, con nombre
     * unico por timestamp para no pisar sesiones anteriores. Usa
     * MediaStore (API 29+) para no requerir permisos de almacenamiento
     * legacy; en API 26-28 escribe directo a la carpeta publica de
     * Downloads (todavia permitido sin scoped storage en esas versiones).
     * No falla la app si algo sale mal: devuelve null y el llamador decide
     * que informar.
     */
    fun writeToDownloads(context: Context): String? {
        if (rows.isEmpty()) return null

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "airpulse_play_$timestamp.csv"

        val csv = buildString {
            append("timestamp_ms,accel_x,roll,azimuth,pieza_sonada\n")
            for (r in rows) {
                append(r.timestampMs).append(',')
                append(r.accelX).append(',')
                append(r.roll).append(',')
                append(r.azimuth).append(',')
                append(r.piezaSonada).append('\n')
            }
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(context, fileName, csv)
            } else {
                writeLegacy(fileName, csv)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeViaMediaStore(context: Context, fileName: String, csv: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            // subdirectorio dentro de Downloads publico: Download/airpulse-logger
            // (MediaStore usa "Download" sin la "s" final como nombre interno
            // de la coleccion publica, pero el path visible para el usuario
            // sigue siendo la carpeta "Download"/"Descargas" del dispositivo)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/airpulse-logger")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            out.write(csv.toByteArray())
        } ?: return null
        return "Descargas/airpulse-logger/$fileName"
    }

    private fun writeLegacy(fileName: String, csv: String): String? {
        // API 26-28: fallback por si el dispositivo real cae en ese rango
        // (minSdk=26). Intenta "Download" (nombre interno de Android) y,
        // si el directorio no es escribible, cae a "Downloads" con "s"
        // como ultimo recurso (algunos fabricantes/custom ROMs viejas
        // difieren en el nombre visible de la carpeta).
        val candidates = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStorageDirectory(), "Downloads"),
        )
        for (base in candidates) {
            val dir = File(base, "airpulse-logger")
            if (dir.exists() || dir.mkdirs()) {
                val file = File(dir, fileName)
                return try {
                    FileOutputStream(file).use { it.write(csv.toByteArray()) }
                    "${base.name}/airpulse-logger/$fileName"
                } catch (e: Exception) {
                    null
                }
            }
        }
        return null
    }
}
