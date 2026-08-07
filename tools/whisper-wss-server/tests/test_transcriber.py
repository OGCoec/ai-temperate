import pathlib
import sys
import unittest
from unittest.mock import patch

import numpy as np


PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

from whisper_wss.transcriber import FasterWhisperTranscriber  # noqa: E402


class Segment:

    def __init__(self, start, end, text):
        self.start = start
        self.end = end
        self.text = text


class FakeModel:

    def __init__(self):
        self.audio = None
        self.options = None

    def transcribe(self, audio, **options):
        self.audio = audio
        self.options = options
        return iter([
            Segment(0.0, 0.4, " Hello "),
            Segment(0.4, 0.8, "世界"),
        ]), object()


class FasterWhisperTranscriberTest(unittest.TestCase):

    @patch("whisper_wss.transcriber.WhisperModel")
    def test_loads_one_shared_cuda_model_with_three_workers(self, model_factory):
        model_factory.return_value = FakeModel()

        transcriber = FasterWhisperTranscriber.load(pathlib.Path("model"), num_workers=3)

        self.assertIsInstance(transcriber, FasterWhisperTranscriber)
        model_factory.assert_called_once_with(
            "model",
            device="cuda",
            compute_type="int8_float16",
            num_workers=3,
        )

    def test_converts_pcm_and_uses_accurate_final_beam(self):
        model = FakeModel()
        transcriber = FasterWhisperTranscriber(model)

        result = transcriber.transcribe(
            b"\x00\x00\xff\x7f\x00\x80",
            language="zh",
            final=True,
        )

        self.assertEqual("Hello 世界", result.text)
        self.assertEqual(2, len(result.segments))
        self.assertEqual(400, result.segments[0].end_ms)
        self.assertEqual(np.float32, model.audio.dtype)
        self.assertAlmostEqual(32767 / 32768, float(model.audio[1]), places=5)
        self.assertEqual("zh", model.options["language"])
        self.assertEqual(5, model.options["beam_size"])
        self.assertTrue(model.options["vad_filter"])

    def test_uses_fast_beam_for_partial_result(self):
        model = FakeModel()
        transcriber = FasterWhisperTranscriber(model)

        transcriber.transcribe(b"\x00\x00" * 10, language=None, final=False)

        self.assertIsNone(model.options["language"])
        self.assertEqual(1, model.options["beam_size"])


if __name__ == "__main__":
    unittest.main()
