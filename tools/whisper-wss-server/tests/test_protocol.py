import json
import pathlib
import sys
import unittest


PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

from whisper_wss.protocol import (  # noqa: E402
    ProtocolError,
    parse_control_message,
    validate_audio_chunk,
)


class ProtocolTest(unittest.TestCase):

    def test_accepts_supported_session_start(self):
        config = parse_control_message(json.dumps({
            "type": "session.start",
            "language": "zh",
            "format": "pcm_s16le",
            "sampleRate": 16000,
            "channels": 1,
        }))

        self.assertEqual("zh", config.language)
        self.assertEqual(16000, config.sample_rate)
        self.assertEqual(1, config.channels)

    def test_rejects_unsupported_audio_configuration(self):
        with self.assertRaisesRegex(ProtocolError, "16 kHz"):
            parse_control_message(json.dumps({
                "type": "session.start",
                "language": "zh",
                "format": "pcm_s16le",
                "sampleRate": 48000,
                "channels": 2,
            }))

    def test_rejects_odd_pcm_byte_count_and_oversized_frame(self):
        with self.assertRaisesRegex(ProtocolError, "16-bit"):
            validate_audio_chunk(b"\x00", max_frame_bytes=1024)

        with self.assertRaisesRegex(ProtocolError, "too large"):
            validate_audio_chunk(b"\x00\x00" * 513, max_frame_bytes=1024)


if __name__ == "__main__":
    unittest.main()
