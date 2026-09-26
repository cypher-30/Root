"""Generate and verify Root's original interaction sounds.

Standard library only. Every cue is synthesized from the recipes below: a
softened "felted key" voice (sine fundamental with quickly decaying upper
partials) plus a very quiet, deterministic "grain" transient (seeded, low-passed
noise). The shipped WAVs are fully reproducible and contain no third-party
recordings.

    python tools/audio/generate_root_sounds.py          # write WAVs
    python tools/audio/generate_root_sounds.py --check  # verify shipped WAVs

Check mode regenerates every cue in memory and fails if a shipped file differs,
or if any measured property (format, duration, peak, endpoints, DC offset,
tail, size) is outside its limit. It reports failures; it never fixes files.
"""

from __future__ import annotations

import argparse
import io
import math
import random
import struct
import sys
import wave
from dataclasses import dataclass
from pathlib import Path

RATE = 48_000
REPO = Path(__file__).resolve().parents[2]
OUT_DIR = REPO / "app" / "src" / "main" / "res" / "raw"
MAX_TOTAL_BYTES = 1 << 20
MAX_PEAK_DBFS = -6.0
MAX_ABS_DC = 0.001
TAIL_SECONDS = 0.002
MAX_TAIL = 0.01  # -40 dBFS over the final 2 ms

# Pitches (Hz): a D-major vocabulary kept inside a phone-audible register.
A3, D4, FS4 = 220.00, 293.66, 369.99
A4, D5, FS5, A5 = 440.00, 587.33, 739.99, 880.00

# The launch animation this startup sound is scored to. These mirror
# RootMotion (launchMillis, launchHoldMillis, launchEase) and the five branches
# in RootGeometry.branches(); RootSoundCatalogueTest fails if they drift apart.
# The WAV starts when growth starts (LaunchScreen's onGrowthStart).
LAUNCH_GROWTH_S = 2.600
LAUNCH_HOLD_S = 0.350
LAUNCH_EASE = (0.30, 0.05, 0.25, 1.00)
LAUNCH_BRANCHES = 5
# Speakers start a little after play(); visual accents after the first are led by this.
OUTPUT_LEAD_S = 0.030


def _bezier(t: float, a: float, b: float) -> float:
    return 3 * a * (1 - t) ** 2 * t + 3 * b * (1 - t) * t ** 2 + t ** 3


def launch_progress(seconds: float) -> float:
    """Eased root-growth progress (0..1) at a time after growth starts."""
    x = min(max(seconds / LAUNCH_GROWTH_S, 0.0), 1.0)
    x1, y1, x2, y2 = LAUNCH_EASE
    lo, hi = 0.0, 1.0
    for _ in range(50):
        mid = (lo + hi) / 2
        if _bezier(mid, x1, x2) < x:
            lo = mid
        else:
            hi = mid
    return _bezier((lo + hi) / 2, y1, y2)


def launch_time(progress: float) -> float:
    """Seconds after growth starts at which the drawing reaches [progress]."""
    lo, hi = 0.0, LAUNCH_GROWTH_S
    for _ in range(50):
        mid = (lo + hi) / 2
        if launch_progress(mid) < progress:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def _led(seconds: float) -> float:
    return max(0.0, seconds - OUTPUT_LEAD_S)


BRANCH_STARTS = tuple(launch_time(i / LAUNCH_BRANCHES) if i else 0.0 for i in range(LAUNCH_BRANCHES))
# The growth visibly lands well before 100 %: the last few percent creep in.
LAUNCH_LANDS = launch_time(0.92)


@dataclass(frozen=True)
class Note:
    at: float        # onset, seconds
    freq: float      # fundamental, Hz
    level: float     # relative amplitude
    decay: float     # fundamental decay time constant, seconds
    attack: float = 0.008


@dataclass(frozen=True)
class Grain:
    at: float
    level: float
    length: float = 0.018
    cutoff: float = 2_400.0  # low-pass corner, Hz: soft texture, not hiss


@dataclass(frozen=True)
class Ink:
    """Pen-on-paper texture that follows the launch drawing: louder and a little
    brighter while the line draws quickly, fading as it slows to a stop."""
    level: float
    dark_hz: float = 700.0
    bright_hz: float = 2_600.0
    highpass_hz: float = 350.0


@dataclass(frozen=True)
class Cue:
    name: str
    duration: float
    peak_dbfs: float
    min_duration: float
    max_duration: float
    notes: tuple[Note, ...] = ()
    grains: tuple[Grain, ...] = ()
    seed: int = 1
    fade_out: float = 0.04
    ink: Ink | None = None


# Relative level hierarchy (tactile < outcome < motif) is set by peak_dbfs.
CUES: tuple[Cue, ...] = (
    # Startup sound, scored to the launch: ink grain follows the drawing, a soft
    # wooden tap marks each branch as it starts (stepping down into the soil),
    # and a warm open fifth settles as the wordmark resolves, ringing out
    # through the hold so it ends as the screen changes.
    Cue("root_growth", LAUNCH_GROWTH_S + LAUNCH_HOLD_S, -12.0, 2.5, 3.2, seed=91, fade_out=0.45,
        ink=Ink(0.30),
        notes=(
            Note(BRANCH_STARTS[0], A4, 0.42, 0.080, 0.006),         # trunk leaves the seed
            Note(_led(BRANCH_STARTS[1]), FS4, 0.36, 0.075, 0.006),
            Note(_led(BRANCH_STARTS[2]), D4, 0.34, 0.075, 0.006),
            Note(_led(BRANCH_STARTS[3]), A3, 0.30, 0.080, 0.006),
            Note(_led(BRANCH_STARTS[4]), D4, 0.24, 0.080, 0.006),   # last, deepest branch: softer
            Note(_led(LAUNCH_LANDS), D4, 0.46, 0.55, 0.060),        # settle: open fifth
            Note(_led(LAUNCH_LANDS), A4, 0.32, 0.50, 0.060),
            Note(_led(LAUNCH_LANDS) + 0.02, D5, 0.10, 0.40, 0.070), # presence on small speakers
        ),
        grains=tuple(Grain(_led(t) if i else t, 0.22 - 0.03 * i, 0.016, 2_000.0)
                     for i, t in enumerate(BRANCH_STARTS))),
    Cue("brand_a", 1.60, -8.0, 1.0, 2.5, seed=11, fade_out=0.25, notes=(
        Note(0.00, D5, 0.70, 0.18),            # seed
        Note(0.17, A5, 0.80, 0.22),            # branch (fifth)
        Note(0.40, FS5, 1.00, 0.45),           # settle (third)
        Note(0.40, A4, 0.35, 0.50, 0.012),     # warm support under the settle
    ), grains=(Grain(0.0, 0.30), Grain(0.17, 0.18))),
    Cue("brand_b", 1.80, -8.0, 1.0, 2.5, seed=12, fade_out=0.30, notes=(
        Note(0.00, A4, 0.75, 0.20),
        Note(0.24, D5, 0.80, 0.24),
        Note(0.50, FS5, 0.85, 0.55),
        Note(0.50, D5, 0.45, 0.55, 0.012),     # open dyad settle
    ), grains=(Grain(0.0, 0.28), Grain(0.24, 0.16), Grain(0.50, 0.12))),
    Cue("reveal", 0.13, -21.0, 0.08, 0.18, seed=21, fade_out=0.03, notes=(
        Note(0.004, FS5, 0.55, 0.030, 0.004),
    ), grains=(Grain(0.0, 0.60, 0.014, 3_000.0),)),
    Cue("recall_acknowledged", 0.40, -14.0, 0.18, 0.48, seed=31, fade_out=0.08, notes=(
        Note(0.00, D5, 0.80, 0.07),
        Note(0.09, FS5, 1.00, 0.10),           # gentle lift of a third
    ), grains=(Grain(0.0, 0.25),)),
    Cue("answer_correct", 0.48, -13.0, 0.22, 0.55, seed=41, fade_out=0.10, notes=(
        Note(0.00, D5, 0.80, 0.08),
        Note(0.10, A5, 1.00, 0.12),            # resolution to the fifth
        Note(0.10, D5, 0.30, 0.12, 0.012),
    ), grains=(Grain(0.0, 0.25),)),
    Cue("lesson_settled", 1.00, -11.0, 0.6, 1.1, seed=51, fade_out=0.20, notes=(
        Note(0.00, D5, 0.70, 0.12),
        Note(0.13, FS5, 0.80, 0.14),
        Note(0.26, A5, 0.90, 0.28),
        Note(0.26, D5, 0.40, 0.30, 0.015),
    ), grains=(Grain(0.0, 0.25), Grain(0.26, 0.12))),
    Cue("neutral_settle", 0.26, -18.0, 0.12, 0.35, seed=61, fade_out=0.06, notes=(
        Note(0.00, A4, 1.00, 0.07, 0.010),     # single, non-judging tone
    ), grains=(Grain(0.0, 0.20),)),
    Cue("saved", 0.30, -16.0, 0.15, 0.40, seed=71, fade_out=0.07, notes=(
        Note(0.00, D5, 0.80, 0.08),
        Note(0.00, A5, 0.45, 0.06),
    ), grains=(Grain(0.0, 0.35),)),
    Cue("page_touch", 0.09, -24.0, 0.06, 0.20, seed=81, fade_out=0.025, notes=(
        Note(0.003, A5, 0.30, 0.018, 0.003),
    ), grains=(Grain(0.0, 0.80, 0.012, 2_800.0),)),
)


def _attack(t: float, attack: float) -> float:
    if t >= attack:
        return 1.0
    return 0.5 - 0.5 * math.cos(math.pi * t / attack)


def render(cue: Cue) -> list[float]:
    n = int(round(cue.duration * RATE))
    out = [0.0] * n
    # Felted-key voice: soft upper partials that die away faster than the body.
    partials = ((1.0, 1.0, 1.0), (2.0, 0.30, 0.45), (3.0, 0.08, 0.30))
    for note in cue.notes:
        start = int(round(note.at * RATE))
        for i in range(start, n):
            t = (i - start) / RATE
            value = 0.0
            for ratio, amp, decay_scale in partials:
                value += amp * math.exp(-t / (note.decay * decay_scale)) * math.sin(
                    2.0 * math.pi * note.freq * ratio * t)
            out[i] += note.level * _attack(t, note.attack) * value
    rng = random.Random(cue.seed)
    for grain in cue.grains:
        start = int(round(grain.at * RATE))
        length = int(round(grain.length * RATE))
        alpha = 1.0 - math.exp(-2.0 * math.pi * grain.cutoff / RATE)
        lp = 0.0
        for k in range(length):
            i = start + k
            if i >= n:
                break
            lp += alpha * (rng.uniform(-1.0, 1.0) - lp)
            out[i] += grain.level * math.sin(math.pi * k / length) ** 2 * lp
    if cue.ink is not None:
        _add_ink(out, cue.ink, rng)
    # DC blocker, then fades so the first/last samples are exactly silent.
    y_prev = x_prev = 0.0
    for i, x in enumerate(out):
        y = x - x_prev + 0.995 * y_prev
        out[i], x_prev, y_prev = y, x, y
    fade_in = max(1, int(0.002 * RATE))
    for i in range(min(fade_in, n)):
        out[i] *= 0.5 - 0.5 * math.cos(math.pi * i / fade_in)
    fade = int(cue.fade_out * RATE)
    for k in range(fade):
        out[n - fade + k] *= 0.5 + 0.5 * math.cos(math.pi * k / (fade - 1))
    peak = max(abs(v) for v in out) or 1.0
    target = 10 ** (cue.peak_dbfs / 20.0)
    out = [v * target / peak for v in out]
    out[0] = 0.0
    out[-1] = 0.0
    return out


def _add_ink(out: list[float], ink: Ink, rng: random.Random) -> None:
    step = 0.005
    speeds = []
    t = 0.0
    while t <= LAUNCH_GROWTH_S:
        speeds.append((launch_progress(t + step) - launch_progress(t)) / step)
        t += step
    fastest = max(speeds) or 1.0
    dt = 1.0 / RATE
    hp_rc = 1.0 / (2.0 * math.pi * ink.highpass_hz)
    hp_a = hp_rc / (hp_rc + dt)
    lp = hp = x_prev = 0.0
    smooth = 0.0
    smooth_a = 1.0 - math.exp(-dt / 0.030)
    for i in range(len(out)):
        t = i / RATE
        index = int(t / step)
        speed = speeds[index] / fastest if index < len(speeds) else 0.0
        smooth += smooth_a * (speed - smooth)
        cutoff = ink.dark_hz + (ink.bright_hz - ink.dark_hz) * smooth
        alpha = 1.0 - math.exp(-2.0 * math.pi * cutoff * dt)
        lp += alpha * (rng.uniform(-1.0, 1.0) - lp)
        hp = hp_a * (hp + lp - x_prev)
        x_prev = lp
        out[i] += ink.level * smooth ** 0.8 * hp


def encode(samples: list[float]) -> bytes:
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(
            struct.pack("<h", max(-32768, min(32767, int(round(v * 32767))))) for v in samples))
    return buffer.getvalue()


def path_for(cue: Cue) -> Path:
    return OUT_DIR / f"root_sound_{cue.name}.wav"


def measure(data: bytes) -> tuple[dict, list[str]]:
    errors: list[str] = []
    with wave.open(io.BytesIO(data), "rb") as w:
        if (w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getcomptype()) != (1, 2, RATE, "NONE"):
            errors.append("format must be 48 kHz mono 16-bit PCM")
        frames = w.readframes(w.getnframes())
    samples = [s / 32768.0 for (s,) in struct.iter_unpack("<h", frames)]
    if not samples:
        return {"duration": 0.0, "peak_dbfs": -math.inf, "dc": 0.0, "tail": 0.0, "bytes": len(data)}, ["empty"]
    peak = max(abs(s) for s in samples)
    tail_n = int(TAIL_SECONDS * RATE)
    stats = {
        "duration": len(samples) / RATE,
        "peak_dbfs": 20 * math.log10(peak) if peak else -math.inf,
        "dc": sum(samples) / len(samples),
        "tail": max(abs(s) for s in samples[-tail_n:]),
        "bytes": len(data),
    }
    if samples[0] != 0.0 or samples[-1] != 0.0:
        errors.append("first and last samples must be zero")
    if any(abs(s) >= 32767 / 32768.0 for s in samples):
        errors.append("clipped samples")
    return stats, errors


def check(cue: Cue, data: bytes) -> list[str]:
    stats, errors = measure(data)
    if not cue.min_duration <= stats["duration"] <= cue.max_duration:
        errors.append(f"duration {stats['duration']:.3f}s outside {cue.min_duration}-{cue.max_duration}s")
    if stats["peak_dbfs"] > MAX_PEAK_DBFS:
        errors.append(f"peak {stats['peak_dbfs']:.1f} dBFS above {MAX_PEAK_DBFS}")
    if abs(stats["dc"]) >= MAX_ABS_DC:
        errors.append(f"DC offset {stats['dc']:.5f}")
    if stats["tail"] > MAX_TAIL:
        errors.append(f"final tail {stats['tail']:.4f} is not near-silent")
    if stats["bytes"] >= 1_000_000:
        errors.append("exceeds SoundPool's per-sample decode cap")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate or verify Root's interaction sounds.")
    parser.add_argument("--check", action="store_true", help="verify shipped WAVs without writing")
    args = parser.parse_args()
    failures: list[str] = []
    total = 0
    for cue in CUES:
        data = encode(render(cue))
        target = path_for(cue)
        if args.check:
            if not target.is_file():
                failures.append(f"{target.name}: missing")
                continue
            shipped = target.read_bytes()
            if shipped != data:
                failures.append(f"{target.name}: differs from its recipe; regenerate")
            data = shipped
        else:
            OUT_DIR.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        total += len(data)
        stats, _ = measure(data)
        failures += [f"{target.name}: {e}" for e in check(cue, data)]
        print(f"{target.name:34} {stats['duration'] * 1000:6.0f} ms  peak {stats['peak_dbfs']:6.1f} dBFS  "
              f"dc {stats['dc']:+.5f}  {stats['bytes'] / 1024:6.1f} KiB")
    if total > MAX_TOTAL_BYTES:
        failures.append(f"total {total} bytes exceeds {MAX_TOTAL_BYTES}")
    print(f"total {total / 1024:.1f} KiB")
    for failure in failures:
        print(f"FAIL {failure}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
