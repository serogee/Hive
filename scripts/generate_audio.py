"""
Reproducible audio synthesizer for Hive's bundled music loops and sound effects.

Generates OGG Vorbis assets directly into client/src/main/res/raw/ using NumPy.
Requires `soundfile` (recommended, bundles libsndfile OGG Vorbis support) or
`ffmpeg` / `oggenc` on PATH to encode the generated PCM buffers to OGG Vorbis.

Usage:
    python scripts/generate_audio.py
"""

from __future__ import annotations

import math
import os
import shutil
import subprocess
import tempfile
import wave
from pathlib import Path

import numpy as np

SR = 44100
REPO_ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = REPO_ROOT / "client" / "src" / "main" / "res" / "raw"


# ---------------------------------------------------------------------------
# DSP & Encoding Helpers
# ---------------------------------------------------------------------------

def midi_to_hz(midi: float) -> float:
    return 440.0 * (2.0 ** ((midi - 69.0) / 12.0))


def pan_stereo(mono: np.ndarray, pan: float = 0.0) -> np.ndarray:
    """Constant-power stereo panning with pan in [-1.0, 1.0]."""
    angle = (pan + 1.0) * (math.pi / 4.0)
    return np.stack([mono * math.cos(angle), mono * math.sin(angle)], axis=1)


def mix_wrap(buf: np.ndarray, sound: np.ndarray, start_sample: int, gain: float = 1.0) -> None:
    """Mixes stereo `sound` into looping stereo `buf` with sample-exact wrap-around."""
    n = len(buf)
    m = len(sound)
    if m == 0:
        return
    if m <= n:
        s0 = int(start_sample) % n
        first = min(m, n - s0)
        buf[s0:s0 + first] += sound[:first] * gain
        rem = m - first
        if rem > 0:
            buf[:rem] += sound[first:] * gain
    else:
        idx = (np.arange(m) + int(start_sample)) % n
        np.add.at(buf, idx, sound * gain)


def mix_linear(buf: np.ndarray, sound: np.ndarray, start_sample: int, gain: float = 1.0) -> None:
    """Mixes stereo `sound` into non-looping stereo `buf`."""
    s0 = int(start_sample)
    if s0 >= len(buf) or s0 + len(sound) <= 0:
        return
    src_start = max(0, -s0)
    dst_start = max(0, s0)
    length = min(len(sound) - src_start, len(buf) - dst_start)
    if length > 0:
        buf[dst_start:dst_start + length] += sound[src_start:src_start + length] * gain


def fft_lowpass(x: np.ndarray, cutoff_hz: float, sr: int = SR, order: float = 2.0) -> np.ndarray:
    n = len(x)
    freqs = np.fft.rfftfreq(n, 1.0 / sr)
    mag = 1.0 / np.sqrt(1.0 + (freqs / max(cutoff_hz, 20.0)) ** (2.0 * order))
    if x.ndim == 2:
        mag = mag[:, None]
    return np.fft.irfft(np.fft.rfft(x, axis=0) * mag, n=n, axis=0)


def fft_highpass(x: np.ndarray, cutoff_hz: float, sr: int = SR, order: float = 2.0) -> np.ndarray:
    n = len(x)
    freqs = np.fft.rfftfreq(n, 1.0 / sr)
    with np.errstate(divide="ignore"):
        ratio = np.where(freqs > 0, cutoff_hz / freqs, 1e9)
    mag = 1.0 / np.sqrt(1.0 + ratio ** (2.0 * order))
    mag[0] = 0.0
    if x.ndim == 2:
        mag = mag[:, None]
    return np.fft.irfft(np.fft.rfft(x, axis=0) * mag, n=n, axis=0)


def fft_bandpass(x: np.ndarray, low_hz: float, high_hz: float, sr: int = SR) -> np.ndarray:
    return fft_lowpass(fft_highpass(x, low_hz, sr, order=2.0), high_hz, sr, order=2.0)


def apply_circular_reverb(
    stereo: np.ndarray,
    rt60: float = 1.1,
    wet: float = 0.22,
    damp_hz: float = 3800.0,
    predelay_ms: float = 14.0,
    seed: int = 42,
) -> np.ndarray:
    """
    Circular FFT stereo reverb so looping tracks wrap their reverb tails
    seamlessly back to sample 0 with zero click or cutoff.
    """
    rng = np.random.default_rng(seed)
    n = len(stereo)
    ir_len = min(n, int(SR * min(rt60 * 1.4, 2.5)))
    t = np.arange(ir_len) / SR
    decay = np.exp(-6.907755 * t / max(rt60, 0.05))

    ir = np.zeros((n, 2), dtype=np.float64)
    pre = int(SR * predelay_ms / 1000.0)
    for ch in range(2):
        noise = rng.standard_normal(ir_len) * decay
        noise = fft_lowpass(noise, damp_hz)
        noise = fft_highpass(noise, 140.0)
        rms = np.sqrt(np.mean(noise ** 2)) + 1e-12
        noise = noise / (rms * math.sqrt(ir_len) * 0.55)
        end = min(n, pre + ir_len)
        ir[pre:end, ch] = noise[:end - pre]

    S = np.fft.rfft(stereo, axis=0)
    H = np.fft.rfft(ir, axis=0)
    wet_sig = np.fft.irfft(S * H, n=n, axis=0)
    return stereo * (1.0 - wet * 0.35) + wet_sig * wet


def apply_linear_reverb(
    stereo: np.ndarray,
    rt60: float = 0.60,
    wet: float = 0.20,
    damp_hz: float = 5000.0,
    seed: int = 99,
) -> np.ndarray:
    """Linear FFT stereo reverb for one-shot sound effects."""
    rng = np.random.default_rng(seed)
    ir_len = int(SR * min(rt60 * 1.3, 1.2))
    t = np.arange(ir_len) / SR
    decay = np.exp(-6.907755 * t / max(rt60, 0.05))
    pre = int(SR * 0.008)
    total = len(stereo) + ir_len + pre
    nfft = 1 << (total - 1).bit_length()

    out = np.zeros((total, 2), dtype=np.float64)
    out[:len(stereo)] = stereo * (1.0 - wet * 0.25)

    for ch in range(2):
        noise = rng.standard_normal(ir_len) * decay
        noise = fft_lowpass(noise, damp_hz)
        noise = fft_highpass(noise, 180.0)
        rms = np.sqrt(np.mean(noise ** 2)) + 1e-12
        noise = noise / (rms * math.sqrt(ir_len) * 0.6)
        ir_pad = np.zeros(nfft)
        ir_pad[pre:pre + ir_len] = noise
        x_pad = np.zeros(nfft)
        x_pad[:len(stereo)] = stereo[:, ch]
        conv = np.fft.irfft(np.fft.rfft(x_pad) * np.fft.rfft(ir_pad), n=nfft)[:total]
        out[:, ch] += conv * wet

    env = np.max(np.abs(out), axis=1)
    nonzero = np.where(env > 1e-4)[0]
    if len(nonzero) > 0:
        end = min(len(out), nonzero[-1] + int(SR * 0.025))
        out = out[:end]
        fade = min(int(SR * 0.015), len(out))
        out[-fade:] *= np.linspace(1.0, 0.0, fade)[:, None]
    return out


def write_ogg_vorbis(sig: np.ndarray, out_path: Path, quality: float = 0.5) -> None:
    """Encodes float64 stereo audio in [-1, 1] to OGG Vorbis."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        try:
            import imageio_ffmpeg  # type: ignore

            ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
        except ImportError:
            ffmpeg = None
    oggenc = shutil.which("oggenc")

    if ffmpeg or oggenc:
        pcm = np.clip(sig * 32767.0, -32768, 32767).astype(np.int16)
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            tmp_path = Path(tmp.name)
        try:
            with wave.open(str(tmp_path), "wb") as wf:
                wf.setnchannels(2)
                wf.setsampwidth(2)
                wf.setframerate(SR)
                wf.writeframes(pcm.tobytes())

            q_val = f"{quality * 10.0:.1f}"
            if ffmpeg:
                subprocess.run(
                    [ffmpeg, "-y", "-i", str(tmp_path), "-c:a", "libvorbis", "-q:a", q_val, str(out_path)],
                    check=True,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
            else:
                subprocess.run(
                    [oggenc, "-Q", "-q", q_val, "-o", str(out_path), str(tmp_path)],
                    check=True,
                )
            return
        finally:
            tmp_path.unlink(missing_ok=True)

    try:
        import soundfile as sf  # type: ignore

        clipped = np.clip(sig, -1.0, 1.0).astype(np.float32)
        with sf.SoundFile(str(out_path), mode="w", samplerate=SR, channels=2, format="OGG", subtype="VORBIS") as f:
            chunk = 4096
            for i in range(0, len(clipped), chunk):
                f.write(clipped[i:i + chunk])
        return
    except ImportError as exc:
        raise RuntimeError(
            "OGG Vorbis encoding requires `ffmpeg`, `oggenc`, `imageio-ffmpeg`, or `soundfile`."
        ) from exc


def master_and_save(
    stereo: np.ndarray,
    filename: str,
    target_peak: float = 0.88,
    drive: float = 1.12,
    is_loop: bool = False,
) -> Path:
    sig = stereo.copy()
    sig -= np.mean(sig, axis=0, keepdims=True)
    peak = np.max(np.abs(sig)) + 1e-12
    sig = np.tanh((sig / peak) * drive) / math.tanh(drive)
    sig *= target_peak

    out_path = RAW_DIR / filename
    write_ogg_vorbis(sig, out_path)

    dur = len(sig) / SR
    rms_db = 20.0 * math.log10(float(np.sqrt(np.mean(sig ** 2))) + 1e-12)
    size_kb = out_path.stat().st_size / 1024.0
    seam_note = ""
    if is_loop:
        seam_diff = float(np.max(np.abs(sig[0] - sig[-1])))
        seam_note = f" | seam {seam_diff:.4f}"
    print(f"Wrote {filename:24s} | {dur:5.2f}s | {size_kb:6.1f} KB | RMS {rms_db:5.1f} dBFS{seam_note}")
    return out_path


# ---------------------------------------------------------------------------
# Instrument Synthesizers
# ---------------------------------------------------------------------------

def synth_rhodes(midi: float, dur: float, vel: float = 0.8, pan: float = 0.0, bright: float = 1.0) -> np.ndarray:
    f = midi_to_hz(midi)
    n = int(SR * (dur + 0.35))
    t = np.arange(n) / SR

    att = np.minimum(1.0, t / 0.008)
    dec = np.exp(-t / (1.4 + 0.5 * (60.0 / max(midi, 36.0))))
    rel = np.where(t > dur, np.exp(-(t - dur) / 0.09), 1.0)
    env = att * dec * rel

    tine_env = np.exp(-t / 0.14) * vel * bright
    mod = np.sin(2.0 * math.pi * (f * 7.0) * t) * (0.42 * tine_env)
    mod2 = np.sin(2.0 * math.pi * (f * 2.0) * t) * (0.35 * np.exp(-t / 0.55))

    body = np.sin(2.0 * math.pi * f * t + mod + mod2)
    warmth = 0.24 * np.sin(2.0 * math.pi * (2.0 * f) * t) * np.exp(-t / 0.6)
    warmth += 0.08 * np.sin(2.0 * math.pi * (3.0 * f) * t) * np.exp(-t / 0.3)

    lfo_l = 1.0 + 0.06 * np.sin(2.0 * math.pi * 3.2 * t)
    lfo_r = 1.0 + 0.06 * np.sin(2.0 * math.pi * 3.2 * t + math.pi * 0.7)

    mono = (body + warmth) * env * vel * 0.55
    st = pan_stereo(mono, pan)
    st[:, 0] *= lfo_l
    st[:, 1] *= lfo_r
    return st


def synth_marimba(midi: float, dur: float, vel: float = 0.85, pan: float = 0.0, woodiness: float = 1.0) -> np.ndarray:
    f = midi_to_hz(midi)
    n = int(SR * max(dur, 0.40))
    t = np.arange(n) / SR
    rng = np.random.default_rng(int(midi * 100) + int(vel * 1000))

    att = np.minimum(1.0, t / 0.0025)
    tau = max(0.11, 0.34 - (midi - 60) * 0.006)
    env1 = att * np.exp(-t / tau)
    env4 = att * np.exp(-t / (tau * 0.22))
    env10 = att * np.exp(-t / 0.018)

    sig = (
        1.00 * np.sin(2.0 * math.pi * f * t) * env1
        + 0.26 * woodiness * np.sin(2.0 * math.pi * (3.98 * f) * t) * env4
        + 0.08 * woodiness * np.sin(2.0 * math.pi * (9.2 * f) * t) * env10
    )

    mallet_n = min(n, int(SR * 0.015))
    mallet = rng.standard_normal(mallet_n) * np.exp(-np.arange(mallet_n) / (SR * 0.0025))
    mallet = fft_bandpass(mallet, 600.0, 2600.0)
    sig[:mallet_n] += mallet * 0.18 * woodiness

    return pan_stereo(sig * vel * 0.65, pan)


def synth_pizz(midi: float, dur: float = 0.26, vel: float = 0.75, pan: float = 0.0) -> np.ndarray:
    """Clean staccato pizzicato/marimba pluck used in Focus Pulse."""
    f = midi_to_hz(midi)
    n = int(SR * max(dur + 0.06, 0.22))
    t = np.arange(n) / SR
    att = np.minimum(1.0, t / 0.003)
    env = att * np.exp(-t / max(dur * 0.55, 0.08))
    sig = (
        1.00 * np.sin(2.0 * math.pi * f * t)
        + 0.30 * np.sin(2.0 * math.pi * (2.0 * f) * t) * np.exp(-t / 0.07)
        + 0.20 * np.sin(2.0 * math.pi * (3.0 * f) * t) * np.exp(-t / 0.045)
        + 0.08 * np.sin(2.0 * math.pi * (4.0 * f) * t) * np.exp(-t / 0.025)
    ) * env * vel * 0.58
    return pan_stereo(sig, pan)


def synth_pluck(midi: float, dur: float, vel: float = 0.85, pan: float = 0.0) -> np.ndarray:
    f = midi_to_hz(midi)
    n = int(SR * (dur + 0.18))
    t = np.arange(n) / SR

    att = np.minimum(1.0, t / 0.003)
    env = att * np.exp(-t / max(dur * 0.85, 0.14))
    rel = np.where(t > dur, np.exp(-(t - dur) / 0.045), 1.0)
    env *= rel

    sig = np.zeros(n)
    for k in range(1, 9):
        if k * f >= SR * 0.45:
            break
        h_env = np.exp(-t / (0.28 / (k ** 1.15)))
        detune = 1.0 + (0.0022 if k % 2 == 0 else -0.0018)
        amp = (1.0 / (k ** 1.05)) * (1.0 if k <= 4 else 0.7)
        sig += amp * np.sin(2.0 * math.pi * (f * k * detune) * t) * h_env

    mono = sig * env * vel * 0.52
    st = pan_stereo(mono, pan)
    shift = int(SR * 0.003)
    if shift < n:
        st[shift:, 1] = st[shift:, 1] * 0.7 + st[:-shift, 1] * 0.3
    return st


def synth_bass(midi: float, dur: float, vel: float = 0.9, punchy: bool = False) -> np.ndarray:
    f = midi_to_hz(midi)
    n = int(SR * (dur + 0.06))
    t = np.arange(n) / SR

    att = np.minimum(1.0, t / (0.004 if punchy else 0.012))
    rel = np.where(t > dur, np.exp(-(t - dur) / 0.025), 1.0)
    dec = np.exp(-t / (0.28 if punchy else 0.85))
    env = att * (0.35 + 0.65 * dec) * rel

    if punchy:
        phase = 2.0 * math.pi * f * t + 0.45 * (1.0 - np.exp(-t / 0.012))
    else:
        phase = 2.0 * math.pi * f * t

    h2_decay = np.exp(-t / (0.14 if punchy else 0.35))
    h3_decay = np.exp(-t / (0.08 if punchy else 0.20))

    sig = (
        1.00 * np.sin(phase)
        + (0.42 if punchy else 0.28) * np.sin(2.0 * phase) * h2_decay
        + (0.20 if punchy else 0.09) * np.sin(3.0 * phase) * h3_decay
    )
    sig = np.tanh(sig * 1.1) * env * vel * 0.62
    return pan_stereo(sig, 0.0)


def synth_pad(midis: list[float], dur: float, vel: float = 0.32) -> np.ndarray:
    n = int(SR * (dur + 0.4))
    t = np.arange(n) / SR
    att = np.minimum(1.0, t / 0.35)
    rel = np.where(t > dur, np.exp(-(t - dur) / 0.25), 1.0)
    env = att * rel

    out = np.zeros((n, 2), dtype=np.float64)
    for idx, m in enumerate(midis):
        f = midi_to_hz(m)
        pan = -0.45 + 0.9 * (idx / max(len(midis) - 1, 1))
        voice = (
            0.7 * np.sin(2.0 * math.pi * (f * 0.9985) * t)
            + 0.7 * np.sin(2.0 * math.pi * (f * 1.0015) * t)
            + 0.16 * np.sin(2.0 * math.pi * (2.0 * f) * t)
        )
        out += pan_stereo(voice * env * vel / max(len(midis), 1), pan)
    return out


def synth_bell_chime(midi: float, dur: float = 0.5, vel: float = 0.8, pan: float = 0.0) -> np.ndarray:
    f = midi_to_hz(midi)
    n = int(SR * dur)
    t = np.arange(n) / SR
    att = np.minimum(1.0, t / 0.002)
    sig = (
        1.00 * np.sin(2.0 * math.pi * f * t) * np.exp(-t / (dur * 0.45))
        + 0.45 * np.sin(2.0 * math.pi * (2.0 * f) * t) * np.exp(-t / (dur * 0.30))
        + 0.22 * np.sin(2.0 * math.pi * (3.0 * f) * t) * np.exp(-t / (dur * 0.18))
        + 0.14 * np.sin(2.0 * math.pi * (5.0 * f) * t) * np.exp(-t / (dur * 0.09))
    ) * att * vel * 0.55
    return pan_stereo(sig, pan)


# ---------------------------------------------------------------------------
# Drum & Percussion Synthesizers
# ---------------------------------------------------------------------------

def drum_kick(vel: float = 0.9, punchy: bool = False) -> np.ndarray:
    dur = 0.22 if punchy else 0.28
    n = int(SR * dur)
    t = np.arange(n) / SR
    f_start = 160.0 if punchy else 125.0
    f_end = 48.0 if punchy else 44.0
    tau_f = 0.026 if punchy else 0.036
    phase = 2.0 * math.pi * (f_end * t + (f_start - f_end) * tau_f * (1.0 - np.exp(-t / tau_f)))
    env = np.minimum(1.0, t / 0.002) * np.exp(-t / (0.095 if punchy else 0.115))
    click = np.exp(-t / 0.003) * (0.24 if punchy else 0.12) * np.sin(2.0 * math.pi * 1200.0 * t)
    sig = np.tanh((np.sin(phase) * env + click) * (1.25 if punchy else 1.05)) * vel * 0.78
    return pan_stereo(sig, 0.0)


def drum_rimshot(vel: float = 0.6, pan: float = 0.12) -> np.ndarray:
    rng = np.random.default_rng(101)
    n = int(SR * 0.09)
    t = np.arange(n) / SR
    body = np.sin(2.0 * math.pi * 420.0 * t) * np.exp(-t / 0.012)
    knock = np.sin(2.0 * math.pi * 1650.0 * t) * np.exp(-t / 0.005) * 0.45
    noise = fft_bandpass(rng.standard_normal(n), 1200.0, 5000.0) * np.exp(-t / 0.014) * 0.35
    sig = (body * 0.7 + knock + noise) * vel * 0.55
    return pan_stereo(sig, pan)


def drum_hat(vel: float = 0.45, open_hat: bool = False, pan: float = -0.18, seed: int = 303) -> np.ndarray:
    rng = np.random.default_rng(seed)
    dur = 0.16 if open_hat else 0.050
    n = int(SR * dur)
    t = np.arange(n) / SR
    hp = fft_highpass(rng.standard_normal(n), 6800.0)
    env = np.minimum(1.0, t / 0.0015) * np.exp(-t / (0.060 if open_hat else 0.013))
    return pan_stereo(hp * env * vel * 0.42, pan)


def drum_shaker(vel: float = 0.35, pan: float = 0.25, seed: int = 404) -> np.ndarray:
    rng = np.random.default_rng(seed)
    n = int(SR * 0.075)
    t = np.arange(n) / SR
    noise = fft_bandpass(rng.standard_normal(n), 4200.0, 10500.0)
    env = np.sin(math.pi * np.minimum(1.0, t / 0.022)) * np.exp(-t / 0.022)
    return pan_stereo(noise * env * vel * 0.38, pan)


def drum_clock_tick(high: bool = True, vel: float = 0.40, pan: float = 0.0) -> np.ndarray:
    """Clean clockwork tick-tock pulse for Focus Pulse."""
    n = int(SR * 0.055)
    t = np.arange(n) / SR
    f0 = 1480.0 if high else 1080.0
    f1 = 1220.0 if high else 880.0
    freq = f1 + (f0 - f1) * np.exp(-t / 0.008)
    phase = 2.0 * math.pi * np.cumsum(freq) / SR
    env = np.minimum(1.0, t / 0.001) * np.exp(-t / 0.009)
    sig = (np.sin(phase) + 0.25 * np.sin(2.4 * phase) * np.exp(-t / 0.004)) * env * vel * 0.55
    return pan_stereo(sig, pan)


def drum_woodblock(midi: float = 88.0, vel: float = 0.75, pan: float = 0.0) -> np.ndarray:
    f = midi_to_hz(midi)
    n = int(SR * 0.08)
    t = np.arange(n) / SR
    sig = (
        np.sin(2.0 * math.pi * f * t) * np.exp(-t / 0.014)
        + 0.35 * np.sin(2.0 * math.pi * (f * 2.42) * t) * np.exp(-t / 0.007)
    ) * np.minimum(1.0, t / 0.001) * vel * 0.55
    return pan_stereo(sig, pan)


def synth_whoosh(dur: float, f_start: float, f_end: float, vel: float, seed: int) -> np.ndarray:
    """Time-varying swept bandpass noise for card flip and soft swish cues."""
    rng = np.random.default_rng(seed)
    n = int(SR * dur)
    t = np.arange(n) / SR
    noise = rng.standard_normal(n)
    # Crossfade between low-band and high-band filtered noise for a smooth upward/downward sweep
    low = fft_bandpass(noise, min(f_start, f_end) * 0.75, min(f_start, f_end) * 1.45)
    mid = fft_bandpass(noise, math.sqrt(f_start * f_end) * 0.7, math.sqrt(f_start * f_end) * 1.5)
    high = fft_bandpass(noise, max(f_start, f_end) * 0.7, max(f_start, f_end) * 1.35)
    u = t / max(dur, 1e-6)
    if f_end >= f_start:
        w_low = np.clip(1.0 - 2.0 * u, 0.0, 1.0)
        w_mid = np.sin(math.pi * u)
        w_high = np.clip(2.0 * u - 1.0, 0.0, 1.0)
    else:
        w_high = np.clip(1.0 - 2.0 * u, 0.0, 1.0)
        w_mid = np.sin(math.pi * u)
        w_low = np.clip(2.0 * u - 1.0, 0.0, 1.0)
    swept = low * w_low + mid * w_mid + high * w_high
    env = np.minimum(1.0, t / (dur * 0.28)) * np.exp(-np.maximum(0.0, t - dur * 0.28) / (dur * 0.34))
    fade = min(int(SR * 0.01), n)
    env[-fade:] *= np.linspace(1.0, 0.0, fade)
    return pan_stereo(swept * env * vel, 0.0)


# ---------------------------------------------------------------------------
# MUSIC 1: LOBBY CHILL ("Honeycomb Lounge") — 96 BPM, 8 bars (exact 882000 samples)
# ---------------------------------------------------------------------------

def build_music_lobby() -> None:
    bpm = 96.0
    beat = 60.0 / bpm
    bars = 8
    total_samples = int(round(bars * 4 * beat * SR))  # Exact 882,000 samples (20.00s)
    buf = np.zeros((total_samples, 2), dtype=np.float64)

    def b2s(b: float) -> int:
        frac = b - math.floor(b)
        swing = 0.032 if (abs(frac - 0.25) < 0.02 or abs(frac - 0.75) < 0.02) else 0.0
        return int(round((b + swing) * beat * SR))

    chords = [
        (0.0, 3.8, [53, 57, 60, 64, 67]),
        (4.0, 3.8, [50, 57, 60, 64, 65]),
        (8.0, 3.8, [46, 53, 57, 62, 65]),
        (12.0, 2.0, [48, 55, 58, 62, 65]),
        (14.0, 1.8, [48, 55, 58, 62, 64]),
        (16.0, 3.8, [53, 57, 60, 64, 67]),
        (20.0, 3.8, [57, 60, 64, 67, 71]),
        (24.0, 3.8, [55, 58, 62, 65, 69]),
        (28.0, 3.8, [48, 55, 58, 62, 69]),
    ]

    for start_b, dur_b, notes in chords:
        mix_wrap(buf, synth_pad(notes[1:], dur_b * beat, vel=0.16), b2s(start_b))
        for i, m in enumerate(notes):
            roll = i * 0.022
            pan = -0.35 + 0.7 * (i / max(len(notes) - 1, 1))
            mix_wrap(buf, synth_rhodes(m, dur_b * beat * 0.92, vel=0.34, pan=pan, bright=0.75), b2s(start_b) + int(roll * SR))
        if dur_b >= 3.5:
            for i, m in enumerate(notes[1:]):
                roll = i * 0.018
                pan = -0.25 + 0.5 * (i / max(len(notes) - 2, 1))
                mix_wrap(buf, synth_rhodes(m, 0.95 * beat, vel=0.19, pan=pan, bright=0.6), b2s(start_b + 2.5) + int(roll * SR))

    bass_line = [
        (0.0, 1.4, 41), (1.5, 0.45, 41), (2.5, 1.3, 45),
        (4.0, 1.4, 38), (5.5, 0.45, 38), (6.5, 1.3, 45),
        (8.0, 1.4, 34), (9.5, 0.45, 34), (10.5, 1.3, 41),
        (12.0, 1.4, 36), (13.5, 0.45, 36), (14.5, 0.9, 43), (15.5, 0.45, 40),
        (16.0, 1.4, 41), (17.5, 0.45, 41), (18.5, 1.3, 48),
        (20.0, 1.4, 45), (21.5, 0.45, 45), (22.5, 1.3, 43),
        (24.0, 1.4, 43), (25.5, 0.45, 43), (26.5, 1.3, 38),
        (28.0, 1.4, 36), (29.5, 0.45, 36), (30.5, 0.9, 43), (31.5, 0.45, 40),
    ]
    for b, d, m in bass_line:
        mix_wrap(buf, synth_bass(m, d * beat, vel=0.72, punchy=False), b2s(b))

    melody = [
        (0.5, 0.4, 72, 0.42), (1.0, 0.4, 76, 0.46), (1.5, 0.6, 79, 0.50), (2.5, 0.5, 76, 0.40),
        (4.5, 0.4, 74, 0.42), (5.0, 0.4, 72, 0.45), (5.5, 0.7, 69, 0.48),
        (8.5, 0.4, 70, 0.42), (9.0, 0.4, 74, 0.48), (9.5, 0.6, 77, 0.50), (10.5, 0.4, 74, 0.40),
        (12.5, 0.4, 72, 0.44), (13.25, 0.4, 74, 0.42), (14.0, 0.8, 76, 0.48),
        (16.5, 0.4, 79, 0.46), (17.0, 0.4, 77, 0.42), (17.5, 0.6, 76, 0.48), (18.5, 0.5, 72, 0.42),
        (20.5, 0.4, 76, 0.44), (21.0, 0.4, 79, 0.50), (21.5, 0.8, 81, 0.52), (23.0, 0.5, 79, 0.40),
        (24.5, 0.4, 77, 0.46), (25.0, 0.4, 74, 0.44), (25.5, 0.6, 72, 0.48), (26.5, 0.5, 70, 0.42),
        (28.5, 0.4, 72, 0.46), (29.5, 0.4, 74, 0.44), (30.5, 0.7, 76, 0.48), (31.5, 0.4, 72, 0.36),
    ]
    for idx, (b, d, m, v) in enumerate(melody):
        pan = -0.32 if idx % 2 == 0 else 0.32
        mix_wrap(buf, synth_marimba(m, d * beat, vel=v, pan=pan, woodiness=0.85), b2s(b))

    for bar in range(bars):
        base = bar * 4.0
        mix_wrap(buf, drum_kick(vel=0.68, punchy=False), b2s(base + 0.0))
        mix_wrap(buf, drum_kick(vel=0.52, punchy=False), b2s(base + 2.5))
        if bar % 2 == 1:
            mix_wrap(buf, drum_kick(vel=0.40, punchy=False), b2s(base + 1.75))

        mix_wrap(buf, drum_rimshot(vel=0.58, pan=0.14), b2s(base + 1.0))
        mix_wrap(buf, drum_rimshot(vel=0.62, pan=0.14), b2s(base + 3.0))
        if bar in (3, 7):
            mix_wrap(buf, drum_rimshot(vel=0.32, pan=-0.15), b2s(base + 3.75))

        for step in range(8):
            pos = base + step * 0.5
            accent = 0.42 if step % 2 == 0 else 0.24
            mix_wrap(buf, drum_shaker(vel=accent, pan=0.22, seed=500 + bar * 16 + step), b2s(pos))
        for off in (0.5, 1.5, 2.5, 3.5):
            mix_wrap(buf, drum_hat(vel=0.18, open_hat=False, pan=-0.22, seed=700 + bar * 8 + int(off * 2)), b2s(base + off))

    buf = apply_circular_reverb(buf, rt60=1.25, wet=0.26, damp_hz=3200.0, seed=11)
    master_and_save(buf, "music_lobby.ogg", target_peak=0.84, drive=1.08, is_loop=True)


# ---------------------------------------------------------------------------
# MUSIC 2: QUIZ THINKING ("Focus Pulse") — 116 BPM, 8 bars (seamless loop)
# ---------------------------------------------------------------------------

def build_music_quiz() -> None:
    bpm = 116.0
    beat = 60.0 / bpm
    step_dur = beat / 4.0
    bars = 8
    total_steps = bars * 16  # 128 sixteenth-note steps
    # Whole number of samples per step so loop duration is exact with zero drift.
    samples_per_step = int(round(step_dur * SR))
    total_samples = total_steps * samples_per_step
    buf = np.zeros((total_samples, 2), dtype=np.float64)

    chords = [
        [64, 67, 71, 74],  # Em9
        [60, 64, 67, 71],  # Cmaj7
        [57, 60, 64, 67],  # Am7
        [59, 64, 66, 69],  # B7sus4
        [64, 67, 71, 74],  # Em9
        [62, 66, 69, 74],  # Dadd9
        [60, 64, 67, 71],  # Cmaj7
        [59, 63, 66, 69],  # B7
    ]
    roots = [40, 36, 45, 47, 40, 38, 36, 47]
    pulse_patterns = [
        [71, 76, 74, 71],
        [71, 76, 72, 71],
        [69, 76, 72, 69],
        [71, 74, 76, 71],
        [71, 76, 79, 76],
        [74, 78, 81, 78],
        [72, 76, 79, 76],
        [71, 75, 78, 75],
    ]

    for step in range(total_steps):
        bar = step // 16
        s = step % 16
        lift = bar >= 4
        s0 = step * samples_per_step

        # Clean clockwork tick-tock every 8th note
        if s % 2 == 0:
            mix_wrap(buf, drum_clock_tick(high=(s % 4 == 0), vel=0.38 if lift else 0.34, pan=-0.20 if s % 4 == 0 else 0.20), s0)

        # Soft heartbeat kick + tidy hats
        if s == 0:
            mix_wrap(buf, drum_kick(vel=0.68, punchy=False), s0)
        elif s == 8:
            mix_wrap(buf, drum_kick(vel=0.54, punchy=False), s0)
        elif lift and s == 10:
            mix_wrap(buf, drum_kick(vel=0.44, punchy=False), s0)

        if s in (4, 12):
            mix_wrap(buf, drum_hat(vel=0.30, open_hat=False, pan=0.16, seed=900 + step), s0)
        if lift and s % 4 == 2:
            mix_wrap(buf, drum_hat(vel=0.22, open_hat=False, pan=-0.20, seed=1100 + step), s0)

        # Steady pulsing sub/synth bass
        if s in (0, 6, 8, 14):
            r = roots[bar]
            note = r + 7 if s == 14 else r
            mix_wrap(buf, synth_bass(note, step_dur * 2.2, vel=0.72 if s in (0, 8) else 0.58, punchy=False), s0)

        # Clean staccato marimba thinking ostinato (8th notes)
        if s % 2 == 0:
            m = pulse_patterns[bar][(s // 2) % 4]
            accent = 0.58 if s % 4 == 0 else 0.42
            pan = -0.24 if s % 4 == 0 else 0.24
            mix_wrap(buf, synth_pizz(m, step_dur * 1.9, vel=accent, pan=pan), s0)

        # Subtle pulsing harmonic chords on beat 1 and "& of 3"
        if s in (0, 10):
            chord = chords[bar]
            dur = step_dur * (6.5 if s == 0 else 3.5)
            for idx, m in enumerate(chord):
                pan = -0.35 + 0.7 * (idx / max(len(chord) - 1, 1))
                mix_wrap(buf, synth_bell_chime(m, dur, vel=0.13 if lift else 0.11, pan=pan), s0)
                if s == 0:
                    mix_wrap(buf, synth_rhodes(m, dur * 1.1, vel=0.12, pan=pan, bright=0.65), s0)

    buf = apply_circular_reverb(buf, rt60=0.85, wet=0.20, damp_hz=4200.0, seed=24)
    master_and_save(buf, "music_quiz.ogg", target_peak=0.84, drive=1.08, is_loop=True)


# ---------------------------------------------------------------------------
# LIVELY SOUND EFFECTS (11 cues)
# ---------------------------------------------------------------------------

def build_sfx() -> None:
    # 1. Tile Tap
    n = int(SR * 0.09)
    t = np.arange(n) / SR
    f_sweep = 540.0 + 340.0 * np.exp(-t / 0.009)
    phase = 2.0 * math.pi * np.cumsum(f_sweep) / SR
    tap = (np.sin(phase) * np.exp(-t / 0.016) + 0.3 * np.sin(2.0 * phase) * np.exp(-t / 0.008)) * np.minimum(1.0, t / 0.0015)
    master_and_save(pan_stereo(tap, 0.0), "sfx_tap.ogg", target_peak=0.72, drive=1.05)

    # 2. Answer Lock-In
    buf = np.zeros((int(SR * 0.28), 2))
    mix_linear(buf, synth_marimba(72, 0.14, vel=0.75, pan=-0.1), 0)
    mix_linear(buf, synth_marimba(79, 0.18, vel=0.88, pan=0.1), int(SR * 0.055))
    buf = apply_linear_reverb(buf, rt60=0.28, wet=0.14, seed=31)
    master_and_save(buf, "sfx_lock_in.ogg", target_peak=0.82, drive=1.08)

    # 3. Correct Answer
    buf = np.zeros((int(SR * 0.85), 2))
    notes_ok = [
        (0.000, 72, 0.82, -0.20),
        (0.065, 76, 0.86, -0.05),
        (0.130, 79, 0.90, 0.10),
        (0.195, 84, 0.96, 0.20),
        (0.210, 88, 0.55, -0.15),
    ]
    for onset, m, v, pan in notes_ok:
        s0 = int(SR * onset)
        mix_linear(buf, synth_marimba(m, 0.42, vel=v * 0.72, pan=pan), s0)
        mix_linear(buf, synth_bell_chime(m, 0.52, vel=v * 0.65, pan=-pan * 0.5), s0)
    buf = apply_linear_reverb(buf, rt60=0.58, wet=0.20, damp_hz=6200.0, seed=32)
    master_and_save(buf, "sfx_correct.ogg", target_peak=0.88, drive=1.10)

    # 4. Incorrect Answer
    buf = np.zeros((int(SR * 0.55), 2))
    for onset, f0, f1, dur, gain in [
        (0.00, midi_to_hz(63), midi_to_hz(61.5), 0.14, 0.82),
        (0.13, midi_to_hz(57), midi_to_hz(54.2), 0.28, 0.92),
    ]:
        ln = int(SR * dur)
        tt = np.arange(ln) / SR
        freq = f1 + (f0 - f1) * np.exp(-tt / (dur * 0.45))
        ph = 2.0 * math.pi * np.cumsum(freq) / SR
        env = np.minimum(1.0, tt / 0.005) * np.exp(-tt / (dur * 0.55))
        tone = (
            1.00 * np.sin(ph)
            + 0.35 * np.sin(2.0 * ph) * np.exp(-tt / 0.09)
            + 0.20 * np.sin(3.0 * ph) * np.exp(-tt / 0.06)
        ) * env * gain
        tone = fft_lowpass(tone, 1450.0)
        mix_linear(buf, pan_stereo(tone, 0.0), int(SR * onset))
    buf = apply_linear_reverb(buf, rt60=0.32, wet=0.14, damp_hz=2400.0, seed=33)
    master_and_save(buf, "sfx_incorrect.ogg", target_peak=0.85, drive=1.10)

    # 5. Flashcard Flip (Flip A — crisp paper whoosh + soft settle snap)
    buf = np.zeros((int(SR * 0.22), 2))
    mix_linear(buf, synth_whoosh(0.115, 700.0, 3400.0, vel=0.72, seed=41), 0)
    mix_linear(buf, synth_whoosh(0.070, 2400.0, 1200.0, vel=0.38, seed=42), int(SR * 0.03))
    snap_n = int(SR * 0.055)
    st = np.arange(snap_n) / SR
    snap_f = 175.0 + 165.0 * np.exp(-st / 0.015)
    snap_ph = 2.0 * math.pi * np.cumsum(snap_f) / SR
    snap = (np.sin(snap_ph) + 0.25 * np.sin(2.0 * snap_ph)) * np.minimum(1.0, st / 0.004) * np.exp(-st / 0.014) * 0.48
    mix_linear(buf, pan_stereo(snap, 0.0), int(SR * 0.075))
    master_and_save(buf, "sfx_flip.ogg", target_peak=0.78, drive=1.06)

    # 6. Next Question / Card (Next B — soft breeze + crisp C6 sparkle)
    buf = np.zeros((int(SR * 0.46), 2))
    mix_linear(buf, synth_whoosh(0.10, 800.0, 3200.0, vel=0.32, seed=43), 0)
    mix_linear(buf, synth_bell_chime(84, 0.34, vel=0.78, pan=0.0), int(SR * 0.04))
    buf = apply_linear_reverb(buf, rt60=0.35, wet=0.16, damp_hz=6000.0, seed=44)
    master_and_save(buf, "sfx_next.ogg", target_peak=0.80, drive=1.06)

    # 7. Countdown Tick (final 5 seconds)
    buf = np.zeros((int(SR * 0.14), 2))
    mix_linear(buf, drum_woodblock(88, vel=0.85, pan=0.0), 0)
    mix_linear(buf, synth_bell_chime(96, 0.09, vel=0.28, pan=0.0), 0)
    master_and_save(buf, "sfx_tick.ogg", target_peak=0.80, drive=1.08)

    # 8. Time Up
    buf = np.zeros((int(SR * 0.80), 2))
    mix_linear(buf, synth_bell_chime(79, 0.35, vel=0.82, pan=-0.12), 0)
    mix_linear(buf, synth_bell_chime(72, 0.65, vel=0.90, pan=0.12), int(SR * 0.13))
    mix_linear(buf, synth_marimba(60, 0.45, vel=0.55, pan=0.0), int(SR * 0.13))
    buf = apply_linear_reverb(buf, rt60=0.55, wet=0.20, seed=34)
    master_and_save(buf, "sfx_time_up.ogg", target_peak=0.86, drive=1.10)

    # 9. Player Joined Lobby (Host phone, throttled)
    buf = np.zeros((int(SR * 0.36), 2))
    mix_linear(buf, synth_marimba(76, 0.16, vel=0.70, pan=-0.18), 0)
    mix_linear(buf, synth_bell_chime(83, 0.25, vel=0.72, pan=0.18), int(SR * 0.065))
    buf = apply_linear_reverb(buf, rt60=0.38, wet=0.18, seed=35)
    master_and_save(buf, "sfx_player_join.ogg", target_peak=0.82, drive=1.06)

    # 10. Quiz Start
    buf = np.zeros((int(SR * 0.90), 2))
    for onset, m, v in [(0.00, 67, 0.72), (0.08, 72, 0.78), (0.16, 76, 0.84), (0.24, 79, 0.88), (0.32, 84, 0.95)]:
        s0 = int(SR * onset)
        mix_linear(buf, synth_pluck(m, 0.32, vel=v * 0.75, pan=0.0), s0)
        mix_linear(buf, synth_bell_chime(m, 0.45, vel=v * 0.55, pan=0.0), s0)
    buf = apply_linear_reverb(buf, rt60=0.62, wet=0.22, seed=36)
    master_and_save(buf, "sfx_quiz_start.ogg", target_peak=0.88, drive=1.12)

    # 11. Results / Completion Fanfare
    buf = np.zeros((int(SR * 1.90), 2))
    fanfare_notes = [
        (0.00, 72, 0.22, 0.82, -0.25),
        (0.13, 76, 0.22, 0.85, -0.10),
        (0.26, 79, 0.22, 0.88, 0.10),
        (0.39, 84, 0.22, 0.90, 0.25),
        (0.52, 79, 0.18, 0.82, -0.10),
        (0.65, 84, 0.95, 0.98, 0.00),
    ]
    for onset, m, d, v, pan in fanfare_notes:
        s0 = int(SR * onset)
        mix_linear(buf, synth_pluck(m, d, vel=v * 0.72, pan=pan), s0)
        mix_linear(buf, synth_marimba(m, max(d, 0.35), vel=v * 0.65, pan=-pan), s0)
        mix_linear(buf, synth_bell_chime(m, max(d, 0.50), vel=v * 0.50, pan=pan * 0.5), s0)
    for i, cm in enumerate([60, 64, 67, 71, 74, 88]):
        mix_linear(buf, synth_rhodes(cm, 0.95, vel=0.38, pan=-0.4 + 0.8 * (i / 5.0), bright=1.2), int(SR * 0.65))
    mix_linear(buf, synth_bass(48, 0.95, vel=0.75, punchy=True), int(SR * 0.65))
    buf = apply_linear_reverb(buf, rt60=0.88, wet=0.24, damp_hz=6000.0, seed=37)
    master_and_save(buf, "sfx_results_fanfare.ogg", target_peak=0.90, drive=1.15)


if __name__ == "__main__":
    build_music_lobby()
    build_music_quiz()
    build_sfx()
