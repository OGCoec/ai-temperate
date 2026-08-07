import datetime
import pathlib
import ssl
import sys
import tempfile
import unittest

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID


PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

from whisper_wss.tls import build_server_ssl_context  # noqa: E402


class TlsContextTest(unittest.TestCase):

    def test_loads_pkcs12_without_persisting_private_key(self):
        password = "test-only-password"
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
        now = datetime.datetime.now(datetime.timezone.utc)
        certificate = (
            x509.CertificateBuilder()
            .subject_name(name)
            .issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(minutes=1))
            .not_valid_after(now + datetime.timedelta(days=1))
            .add_extension(
                x509.SubjectAlternativeName([x509.DNSName("localhost")]),
                critical=False,
            )
            .sign(key, hashes.SHA256())
        )
        payload = pkcs12.serialize_key_and_certificates(
            b"test",
            key,
            certificate,
            None,
            serialization.BestAvailableEncryption(password.encode("utf-8")),
        )

        with tempfile.TemporaryDirectory() as directory:
            p12_path = pathlib.Path(directory) / "server.p12"
            p12_path.write_bytes(payload)

            context = build_server_ssl_context(p12_path, password)

            self.assertIsInstance(context, ssl.SSLContext)
            self.assertEqual(ssl.TLSVersion.TLSv1_2, context.minimum_version)
            self.assertEqual([p12_path.name], [path.name for path in pathlib.Path(directory).iterdir()])


if __name__ == "__main__":
    unittest.main()
