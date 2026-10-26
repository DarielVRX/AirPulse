#!/usr/bin/env python3
"""
Genera 8 archivos WAV sinteticos de percusion, uno por pieza de AirPulse,
y los coloca en app/src/main/res/raw/.

Esto es una herramienta de DESARROLLO, no parte de la app Android: se
corre una vez (o cuando se quiera regenerar el set) para producir los
.wav que luego SoundPool carga como recursos `raw`. No requiere ninguna
dependencia externa (usa solo el modulo `wave` + `math` + `struct` de la
libreria estandar de Python), para que cualquiera pueda regenerarlos sin
instalar numpy/scipy.

Cada sonido es un "click"/"beep" percusivo corto con envelope ADSR simple
(en la practica, para un sonido de percusion sintetico tan corto, el
"sustain" es minimo: es basicamente un attack rapido + decay exponencial,
que es lo que hace que un tono suene "percusivo" en vez de un beep plano
de organo). Cada pieza usa una frecuencia/timbre distinta para poder
diferenciarlas al oido mientras se prueba la app, antes de reemplazarlas
por samples reales (ver README.md, seccion "Reemplazar sonidos
sinteticos").

Uso:
    python3 tools/generate_synthetic_drums.py

Para reemplazar por samples reales: borrar estos .wav de
app/src/main/res/raw/ y copiar ahi los samples reales (cualquier nombre,
siempre WAV o MP3/OGG soportado por SoundPool), actualizando las
constantes `soundResName` en DrumPiece.kt si se cambian los nombres de
archivo.
"""
import math
import struct
import wave
import os

SAMPLE_RATE = 44100

# (nombre_archivo, frecuencia_fundamental_hz, duracion_s, tipo_ruido)
# tipo_ruido: "tone" = tono senoidal puro con armonico leve; "noise" = ruido
# filtrado (para hi-hats/crash/ride, que en la realidad son mas ruido que
# tono puro).
SOUNDS = [
    ("drum_snare", 180.0, 0.18, "tone_noise"),
    ("drum_hihat_closed", 1200.0, 0.08, "noise"),
    ("drum_hihat_open", 1000.0, 0.35, "noise"),
    ("drum_crash", 500.0, 0.9, "noise"),
    ("drum_ride", 700.0, 0.6, "noise"),
    ("drum_tom_high", 260.0, 0.3, "tone"),
    ("drum_tom_mid", 180.0, 0.35, "tone"),
    ("drum_tom_low", 110.0, 0.45, "tone"),
]

OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "res", "raw",
)


def envelope(i, n, attack_frac=0.01, decay_tau_frac=0.25):
    """ADSR simplificado para un golpe de percusion corto: attack casi
    instantaneo (rampa lineal muy corta) + decaimiento exponencial.
    Sin sustain real (el sonido ya decae desde el pico), que es lo
    esperado para un golpe percusivo corto, no para una nota sostenida."""
    attack_n = max(1, int(n * attack_frac))
    if i < attack_n:
        return i / attack_n
    decay_tau = max(1.0, n * decay_tau_frac)
    return math.exp(-(i - attack_n) / decay_tau)


def lcg_noise(seed_state):
    """Generador de ruido pseudo-aleatorio simple y determinista (sin
    dependencias externas): congruencial lineal basico."""
    seed_state[0] = (1103515245 * seed_state[0] + 12345) & 0x7FFFFFFF
    return (seed_state[0] / 0x7FFFFFFF) * 2.0 - 1.0


def generate_samples(freq, duration_s, kind):
    n = int(SAMPLE_RATE * duration_s)
    samples = []
    seed_state = [12345]
    prev_noise = 0.0
    for i in range(n):
        t = i / SAMPLE_RATE
        env = envelope(i, n)

        if kind == "tone":
            # tono fundamental + segundo armonico leve para que no sea un
            # seno totalmente puro (mas percusivo al oido)
            val = 0.8 * math.sin(2 * math.pi * freq * t) + 0.2 * math.sin(2 * math.pi * freq * 2 * t)
        elif kind == "noise":
            raw_noise = lcg_noise(seed_state)
            # filtro paso-bajo de un polo simple para no sonar a estatica pura
            filtered = 0.6 * raw_noise + 0.4 * prev_noise
            prev_noise = filtered
            val = filtered
        else:  # "tone_noise": snare = mezcla de tono + ruido
            raw_noise = lcg_noise(seed_state)
            filtered = 0.6 * raw_noise + 0.4 * prev_noise
            prev_noise = filtered
            tone = math.sin(2 * math.pi * freq * t)
            val = 0.5 * tone + 0.5 * filtered

        samples.append(val * env)
    return samples


def write_wav(path, samples):
    max_amp = 32767 * 0.9
    with wave.open(path, "w") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        frames = bytearray()
        for s in samples:
            v = int(max(-1.0, min(1.0, s)) * max_amp)
            frames += struct.pack("<h", v)
        wf.writeframes(bytes(frames))


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, freq, duration, kind in SOUNDS:
        samples = generate_samples(freq, duration, kind)
        out_path = os.path.join(OUT_DIR, f"{name}.wav")
        write_wav(out_path, samples)
        print(f"wrote {out_path} ({len(samples)} samples, {duration}s)")


if __name__ == "__main__":
    main()
