# AirPulse

AirPulse es una app de "air drumming" para Android: sostenes el telefono
en la mano, haces un gesto de golpe en el aire, y la app:

1. Detecta el golpe usando el acelerometro (motor `StreamingAirdrumEngine`,
   port fiel de un script Python validado end-to-end contra datos reales
   de sensor, 6/6 sesiones de prueba, 0 falsos positivos/negativos).
2. Determina en que "posicion" espacial ocurrio el golpe, usando el sensor
   de rotacion (roll + azimuth, via `AzimuthStreamTracker` + clasificacion
   por roll).
3. Reproduce el sonido de percusion asignado a esa posicion.

Hasta 8 posiciones libres (una por pieza de bateria elegida), calibradas
por el usuario golpeando 3 veces en la posicion que elija para cada pieza
-- sin estructura de grilla fija, cada posicion es un punto libre en el
plano roll x azimuth.

## Estructura del proyecto

```
app/src/main/java/com/xdariel/airpulse/
  engine/     - deteccion de golpes, tracking de azimuth, clasificacion,
                matching de posicion (sin dependencias de Android, 100%
                testeable con JUnit puro)
  sensors/    - puente entre SensorManager de Android y el motor
  audio/      - AudioEngine (SoundPool) para reproducir los sonidos
  ui/         - 3 pantallas Compose (Setup, Calibration, Play) + ViewModel
app/src/main/res/raw/
  - 8 archivos .wav sinteticos de percusion (ver "Reemplazar sonidos")
app/src/test/java/com/xdariel/airpulse/engine/
  - tests unitarios del motor de deteccion
tools/generate_synthetic_drums.py
  - script standalone (fuera del proyecto Android) que genera los .wav
    sinteticos de res/raw/
.github/workflows/build.yml
  - build de CI: JDK 17 + Android SDK, corre tests, compila el APK debug,
    lo sube como artifact de GitHub Actions
```

## Abrir en Android Studio

1. Abrir la carpeta `AirPulse/` (la raiz de este proyecto, la que contiene
   `settings.gradle`) con Android Studio ("Open" -> seleccionar esta
   carpeta).
2. Dejar que Android Studio sincronice Gradle (descargara el SDK/Build
   Tools que falten si no estan ya instalados).
3. Compilar y correr sobre un dispositivo real (recomendado: un emulador
   no tiene acelerometro/rotation vector reales, asi que la deteccion de
   golpes no va a funcionar de forma util ahi).

Requiere minSdk 26 (Android 8.0+), elegido porque es donde el sensor
Rotation Vector es confiable y estable en la mayoria de los fabricantes.

## Correr los tests

Desde la raiz del proyecto (requiere JDK 17 y, para compilar, el Android
SDK instalado):

```
./gradlew test
```

Los tests (`app/src/test/java/com/xdariel/airpulse/engine/`) validan,
entre otras cosas:

- que el detector de picos tolera una meseta de saturacion del sensor
  (varias muestras identicas en el pico) y confirma solo 1 golpe, no uno
  por muestra;
- que el filtro de pendiente de ataque NO descarta un golpe real lejano
  en el tiempo (gap grande respecto al hit anterior) aunque tenga una
  pendiente relativamente baja;
- que SI descarta un rebote cercano en el tiempo con pendiente baja;
- el comportamiento del `AzimuthStreamTracker` (congela la busqueda de
  meseta durante la fase de ataque, reporta el punto mas plano
  acumulado);
- el promedio circular de azimuth usado en calibracion (`PositionMatcher`).

Estos tests no pudieron correrse end-to-end contra los CSV reales de
sesiones de validacion (no estan disponibles en este entorno de
desarrollo), asi que validan comportamiento especifico y documentado del
algoritmo con secuencias sinteticas minimas construidas a mano,
verificadas linea por linea contra la logica real antes de incluirlas.

## CI / GitHub Actions

Cada push (cualquier rama) y cada pull request corre
`.github/workflows/build.yml`:

1. Configura JDK 17 y el Android SDK (`android-actions/setup-android`).
2. Corre `./gradlew test` -- si los tests del motor fallan, el build
   falla.
3. Corre `./gradlew assembleDebug`.
4. Sube el APK debug resultante como artifact de GitHub Actions
   (`airpulse-debug-apk`), junto con los reportes de tests.

## Reemplazar sonidos sinteticos por samples reales

Los 8 sonidos en `app/src/main/res/raw/` (`drum_snare.wav`,
`drum_hihat_closed.wav`, etc.) son sinteticos: tonos/ruido filtrado con un
envelope percusivo simple, generados por `tools/generate_synthetic_drums.py`
(un script Python standalone, FUERA del proyecto Android, que no requiere
dependencias mas alla de la libreria estandar). Sirven para poder probar
la app y diferenciar las 8 posiciones al oido antes de tener samples
reales decididos.

Para reemplazarlos por samples reales:

1. Conseguir/grabar los 8 samples (WAV, MP3 u OGG -- cualquier formato que
   soporte `SoundPool`).
2. Copiarlos a `app/src/main/res/raw/` con el MISMO nombre de archivo que
   los sinteticos (`drum_snare.wav`, etc.) para no tener que tocar codigo
   Kotlin. Si se usan nombres distintos, actualizar `soundResName` en
   `DrumPiece.kt` y el mapeo `resIdFor()` en `AudioEngine.kt`.
3. Borrar (opcional) `tools/generate_synthetic_drums.py` si ya no se va a
   volver a regenerar el set sintetico, o dejarlo como referencia.

## Pendiente de diseño (decisiones tomadas por el autor del port, a revisar)

Esta es una v1 minima. Varias decisiones de diseño quedaron abiertas en la
sesion original y se resolvieron aqui con un criterio razonable pero
explicitamente marcado como pendiente de validacion:

- **Metrica de distancia en PlayScreen** (`PositionMatcher.distance`):
  se implemento una distancia euclidea entre roll y la distancia angular
  circular de azimuth, SIN ningun factor de escala relativo entre ambos
  ejes. Es un primer intento razonable (ver el comentario `TODO` en
  `PositionMatcher.kt`), pendiente de validar con datos reales de
  calibracion -- podria necesitar pesos distintos por eje.
- **TH_ROLL fijo (-35.0)**: portado tal cual del script Python validado
  (ver `HitClassifier.kt` y `StreamingAirdrumEngine.kt`). Pendiente:
  hacerlo configurable por calibracion de usuario/posicion en vez de ser
  una constante global, especialmente porque el esquema de posiciones
  libres de 8 piezas hace que la clasificacion binaria L/H original
  (pensada para 2 zonas) quede subsumida por el matching de posicion mas
  general de `PositionMatcher`.
- **Persistencia de calibracion**: la calibracion vive solo en memoria de
  la sesion del ViewModel (`AppViewModel`). Se pierde al cerrar la app.
  Pendiente: persistirla a disco (DataStore o un archivo JSON simple) para
  no tener que recalibrar cada vez.
- **Filtrado de outliers en calibracion**: el centro de cada posicion se
  calcula como el promedio simple (lineal para roll, circular para
  azimuth) de los 3 golpes recibidos, sin ningun filtrado de golpes
  atipicos (p.ej. un golpe donde el usuario claramente erro la posicion).
  Pendiente: considerar descartar outliers antes de promediar, o pedir
  mas de 3 golpes y promediar solo los mas consistentes.
- **`AzimuthStreamTracker`**: a diferencia del motor de deteccion de
  golpes (validado exhaustivamente contra 6 sesiones de CSV reales), este
  tracker se implemento a partir de una descripcion textual de un metodo
  validado manualmente en notebooks ad-hoc (2 sesiones reales, 5/5 y 4/5
  tramos correctos). No hay script Python de referencia final para
  este componente, asi que el port aqui es una implementacion nueva
  basada en esa descripcion, no un port linea-a-linea de codigo existente.
