import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from content_pipeline.media import probe_audio, ffprobe_available, FfprobeMissing, MediaProbeResult


class FakeCompletedProcess:
    def __init__(self, returncode=0, stdout=b"", stderr=b""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class MediaProbeTests(unittest.TestCase):
    def test_raises_when_ffprobe_missing_not_silent_pass(self):
        with mock.patch("content_pipeline.media.shutil.which", return_value=None):
            with self.assertRaises(FfprobeMissing):
                probe_audio(Path("some.m4a"))

    def test_valid_audio_stream_reports_ok(self):
        ffprobe_json = {
            "streams": [{"codec_type": "audio", "codec_name": "aac", "sample_rate": "44100", "duration": "1.5"}],
            "format": {"duration": "1.5"},
        }
        with mock.patch("content_pipeline.media.shutil.which", return_value="/usr/bin/ffprobe"), \
             mock.patch("content_pipeline.media.subprocess.run", return_value=FakeCompletedProcess(
                 returncode=0, stdout=json.dumps(ffprobe_json).encode("utf-8"))):
            result = probe_audio(Path("clip.m4a"))
            self.assertTrue(result.ok)
            self.assertEqual(result.codec, "aac")
            self.assertEqual(result.sample_rate_hz, 44100)
            self.assertEqual(result.duration_ms, 1500)
            self.assertEqual(result.mime, "audio/mp4")

    def test_no_audio_stream_is_not_ok(self):
        ffprobe_json = {"streams": [{"codec_type": "video"}], "format": {}}
        with mock.patch("content_pipeline.media.shutil.which", return_value="/usr/bin/ffprobe"), \
             mock.patch("content_pipeline.media.subprocess.run", return_value=FakeCompletedProcess(
                 returncode=0, stdout=json.dumps(ffprobe_json).encode("utf-8"))):
            result = probe_audio(Path("clip.m4a"))
            self.assertFalse(result.ok)
            self.assertIn("no_audio_stream_found", result.issues)

    def test_nonzero_exit_is_not_ok(self):
        with mock.patch("content_pipeline.media.shutil.which", return_value="/usr/bin/ffprobe"), \
             mock.patch("content_pipeline.media.subprocess.run", return_value=FakeCompletedProcess(
                 returncode=1, stderr=b"invalid data")):
            result = probe_audio(Path("clip.m4a"))
            self.assertFalse(result.ok)
            self.assertTrue(any("ffprobe_nonzero_exit" in i for i in result.issues))

    def test_unsupported_extension_flagged(self):
        ffprobe_json = {
            "streams": [{"codec_type": "audio", "codec_name": "opus", "sample_rate": "48000", "duration": "2.0"}],
            "format": {"duration": "2.0"},
        }
        with mock.patch("content_pipeline.media.shutil.which", return_value="/usr/bin/ffprobe"), \
             mock.patch("content_pipeline.media.subprocess.run", return_value=FakeCompletedProcess(
                 returncode=0, stdout=json.dumps(ffprobe_json).encode("utf-8"))):
            result = probe_audio(Path("clip.ogg"))
            self.assertIn("unsupported_extension:.ogg", result.issues)
            self.assertIsNone(result.mime)

    def test_zero_duration_flagged(self):
        ffprobe_json = {
            "streams": [{"codec_type": "audio", "codec_name": "aac", "sample_rate": "44100", "duration": "0"}],
            "format": {"duration": "0"},
        }
        with mock.patch("content_pipeline.media.shutil.which", return_value="/usr/bin/ffprobe"), \
             mock.patch("content_pipeline.media.subprocess.run", return_value=FakeCompletedProcess(
                 returncode=0, stdout=json.dumps(ffprobe_json).encode("utf-8"))):
            result = probe_audio(Path("clip.m4a"))
            self.assertFalse(result.ok)
            self.assertIn("zero_or_negative_duration", result.issues)


@unittest.skipUnless(ffprobe_available() and shutil.which("ffmpeg"), "requires real ffmpeg/ffprobe on PATH")
class RealFfprobeSmokeTests(unittest.TestCase):
    """Genuine (unmocked) ffprobe smoke test: generates a real, tiny silent
    AAC/m4a clip with ffmpeg and probes it with the real ffprobe binary, so
    this pipeline is exercised against real media tooling wherever it is
    actually available -- not only through mocks."""

    def test_probe_real_generated_m4a_clip(self):
        with tempfile.TemporaryDirectory() as tmp:
            clip_path = Path(tmp) / "real_clip.m4a"
            gen = subprocess.run(
                [
                    "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "anullsrc=r=44100:cl=mono",
                    "-t", "0.5", "-c:a", "aac", str(clip_path),
                ],
                capture_output=True,
            )
            self.assertEqual(gen.returncode, 0, gen.stderr.decode(errors="replace"))
            self.assertTrue(clip_path.exists())

            result = probe_audio(clip_path)
            self.assertTrue(result.ok, result.issues)
            self.assertEqual(result.mime, "audio/mp4")
            self.assertEqual(result.sample_rate_hz, 44100)
            self.assertIsNotNone(result.duration_ms)
            self.assertGreater(result.duration_ms, 0)


if __name__ == "__main__":
    unittest.main()
