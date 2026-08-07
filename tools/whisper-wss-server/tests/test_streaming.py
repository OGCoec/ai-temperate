import pathlib
import sys
import unittest


PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

from whisper_wss.streaming import PartialTranscriptAccumulator  # noqa: E402
from whisper_wss.transcriber import (  # noqa: E402
    TranscriptionResult,
    TranscriptionSegment,
)


class PartialTranscriptAccumulatorTest(unittest.TestCase):

    def test_commits_stable_segments_once_and_replaces_only_the_tail(self):
        accumulator = PartialTranscriptAccumulator(stability_delay_ms=2000)

        first = accumulator.merge(
            TranscriptionResult(
                text="hello world",
                segments=(
                    TranscriptionSegment(0, 2000, "hello"),
                    TranscriptionSegment(2000, 4500, "world"),
                ),
            ),
            window_start_ms=0,
            audio_end_ms=5000,
        )
        second = accumulator.merge(
            TranscriptionResult(
                text="hello world again",
                segments=(
                    TranscriptionSegment(0, 1000, "hello"),
                    TranscriptionSegment(1000, 3000, "world"),
                    TranscriptionSegment(3000, 5200, "again"),
                ),
            ),
            window_start_ms=1000,
            audio_end_ms=6500,
        )

        self.assertEqual("hello world", first)
        self.assertEqual("hello world again", second)
        self.assertEqual(4000, accumulator.stable_until_ms)


if __name__ == "__main__":
    unittest.main()
