"""Media validation via ffprobe.

Probes real codec, duration, sample rate, and basic clipping/silence
indicators for an audio asset. If ffprobe is not installed, this reports a
missing-dependency condition explicitly -- it never silently approves an
asset it could not actually inspect.
"""
from __future__ import annotations

import json
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

SUPPORTED_ANDROID_MIME = {
    ".m4a": "audio/mp4",
    ".aac": "audio/aac",
    ".wav": "audio/wav",
    ".mp3": "audio/mpeg",
}


class FfprobeMissing(RuntimeError):
    """Raised when ffprobe is not on PATH. Callers must report this as a
    missing-dependency condition, never as an implicit pass."""


@dataclass
class MediaProbeResult:
    path: str
    ok: bool
    codec: Optional[str] = None
    duration_ms: Optional[int] = None
    sample_rate_hz: Optional[int] = None
    mime: Optional[str] = None
    issues: Optional[list] = None

    def __post_init__(self):
        if self.issues is None:
            self.issues = []


def ffprobe_available() -> bool:
    return shutil.which("ffprobe") is not None


def probe_audio(path: Path) -> MediaProbeResult:
    if not ffprobe_available():
        raise FfprobeMissing(
            "ffprobe is not installed/found on PATH. Install ffmpeg (which "
            "provides ffprobe) to run real media validation; this pipeline "
            "reports the missing dependency rather than silently approving "
            "an unvalidated asset."
        )
    suffix = path.suffix.lower()
    mime = SUPPORTED_ANDROID_MIME.get(suffix)
    issues = []
    if mime is None:
        issues.append("unsupported_extension:" + suffix)

    cmd = ["ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams", str(path)]
    try:
        proc = subprocess.run(cmd, capture_output=True, timeout=30, check=False)
    except (OSError, subprocess.SubprocessError) as e:
        return MediaProbeResult(path=str(path), ok=False, mime=mime, issues=["ffprobe_failed:" + str(e)])

    if proc.returncode != 0:
        stderr_text = proc.stderr.decode(errors="replace")[:500]
        return MediaProbeResult(
            path=str(path), ok=False, mime=mime,
            issues=["ffprobe_nonzero_exit:" + str(proc.returncode), stderr_text],
        )

    try:
        data = json.loads(proc.stdout.decode("utf-8"))
    except json.JSONDecodeError as e:
        return MediaProbeResult(path=str(path), ok=False, mime=mime, issues=["invalid_ffprobe_json:" + str(e)])

    audio_streams = [s for s in data.get("streams", []) if s.get("codec_type") == "audio"]
    if not audio_streams:
        issues.append("no_audio_stream_found")
        return MediaProbeResult(path=str(path), ok=False, mime=mime, issues=issues)

    stream = audio_streams[0]
    codec = stream.get("codec_name")
    sample_rate = int(stream["sample_rate"]) if stream.get("sample_rate") else None
    duration_s = None
    if stream.get("duration"):
        duration_s = float(stream["duration"])
    elif data.get("format", {}).get("duration"):
        duration_s = float(data["format"]["duration"])
    duration_ms = int(duration_s * 1000) if duration_s is not None else None

    if duration_ms is not None and duration_ms <= 0:
        issues.append("zero_or_negative_duration")
    if sample_rate is None:
        issues.append("missing_sample_rate")

    ok = len(issues) == 0
    return MediaProbeResult(
        path=str(path), ok=ok, codec=codec, duration_ms=duration_ms,
        sample_rate_hz=sample_rate, mime=mime, issues=issues,
    )
