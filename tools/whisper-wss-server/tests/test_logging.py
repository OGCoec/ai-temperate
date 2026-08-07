import logging
import pathlib
import sys
import unittest
from unittest.mock import patch


PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

from whisper_wss.__main__ import configure_logging  # noqa: E402


class LoggingConfigurationTest(unittest.TestCase):

    def test_suppresses_third_party_audio_analysis_metadata(self):
        names = ("faster_whisper", "websockets.server")
        previous = {name: logging.getLogger(name).level for name in names}
        try:
            with patch("whisper_wss.__main__.logging.basicConfig"):
                configure_logging()

            for name in names:
                self.assertEqual(logging.WARNING, logging.getLogger(name).level)
        finally:
            for name, level in previous.items():
                logging.getLogger(name).setLevel(level)


if __name__ == "__main__":
    unittest.main()
