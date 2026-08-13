import pathlib
import sys
import tempfile
import unittest


PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

from whisper_wss.config import ServerSettings  # noqa: E402


class ServerSettingsTest(unittest.TestCase):

    def test_loads_loopback_7896_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            model = root / "model"
            model.mkdir()
            (model / "model.bin").write_bytes(b"model")
            certificate = root / "server.p12"
            certificate.write_bytes(b"p12")

            settings = ServerSettings.from_environment({
                "WHISPER_WSS_HOST": "127.0.0.1",
                "WHISPER_WSS_PORT": "7896",
                "WHISPER_WSS_MODEL_PATH": str(model),
                "WHISPER_WSS_PKCS12_PATH": str(certificate),
                "WHISPER_WSS_PKCS12_PASSWORD": "secret",
                "WHISPER_WSS_ALLOWED_ORIGINS": "https://localhost:5173",
            })

            self.assertEqual(7896, settings.port)
            self.assertEqual(("https://localhost:5173",), settings.allowed_origins)
            self.assertEqual(300000, settings.max_duration_ms)
            self.assertEqual(3, settings.inference_concurrency)
            self.assertEqual(5, settings.waiting_queue_capacity)
            self.assertEqual(90000, settings.queue_wait_timeout_ms)
            self.assertEqual(800, settings.partial_interval_ms)
            self.assertEqual(20000, settings.partial_window_ms)

    def test_rejects_invalid_concurrency_and_waiting_queue_boundaries(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            model = root / "model"
            model.mkdir()
            (model / "model.bin").write_bytes(b"model")
            certificate = root / "server.p12"
            certificate.write_bytes(b"p12")
            base = {
                "WHISPER_WSS_MODEL_PATH": str(model),
                "WHISPER_WSS_PKCS12_PATH": str(certificate),
                "WHISPER_WSS_PKCS12_PASSWORD": "secret",
            }

            with self.assertRaisesRegex(ValueError, "INFERENCE_CONCURRENCY"):
                ServerSettings.from_environment({
                    **base,
                    "WHISPER_WSS_INFERENCE_CONCURRENCY": "0",
                })
            with self.assertRaisesRegex(ValueError, "WAITING_QUEUE_CAPACITY"):
                ServerSettings.from_environment({
                    **base,
                    "WHISPER_WSS_WAITING_QUEUE_CAPACITY": "33",
                })
            with self.assertRaisesRegex(ValueError, "QUEUE_WAIT_TIMEOUT_MS"):
                ServerSettings.from_environment({
                    **base,
                    "WHISPER_WSS_QUEUE_WAIT_TIMEOUT_MS": "999",
                })

    def test_rejects_non_loopback_binding(self):
        with self.assertRaisesRegex(ValueError, "loopback"):
            ServerSettings.from_environment({
                "WHISPER_WSS_HOST": "0.0.0.0",
                "WHISPER_WSS_PORT": "7896",
                "WHISPER_WSS_MODEL_PATH": __file__,
                "WHISPER_WSS_PKCS12_PATH": __file__,
                "WHISPER_WSS_PKCS12_PASSWORD": "secret",
            })


if __name__ == "__main__":
    unittest.main()
