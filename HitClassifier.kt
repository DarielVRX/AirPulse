package com.xdariel.airpulse.engine

/**
 * Clasificacion L/H de un golpe en base al roll leido en el primer
 * instante >= t_hit (sin ventana), igual que hace internamente
 * [StreamingAirdrumEngine].
 *
 * Esta clase se expone por separado (ademas de la logica ya embebida en el
 * motor) porque las pantallas de calibracion/juego necesitan reclasificar
 * o recalcular con umbrales distintos sin tener que re-instanciar todo el
 * motor de deteccion de picos.
 *
 * TODO: TH_ROLL es hoy una constante global fija (-35.0), portada tal cual
 * del script Python validado. En la app final debe volverse configurable
 * por calibracion (potencialmente por usuario, o incluso reemplazada por
 * completo por el esquema de posiciones libres roll x azimuth descrito en
 * README.md, que generaliza esta clasificacion binaria L/H a N posiciones).
 */
object HitClassifier {

    const val TH_ROLL: Double = StreamingAirdrumEngine.TH_ROLL

    enum class Label { L, H }

    fun classify(roll: Double, threshold: Double = TH_ROLL): Label {
        return if (roll < threshold) Label.L else Label.H
    }
}
