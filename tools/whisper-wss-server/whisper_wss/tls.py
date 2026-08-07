"""TLS context construction from the project's password-protected PKCS#12 file."""

from pathlib import Path
import ssl
import tempfile

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.serialization import pkcs12


def build_server_ssl_context(pkcs12_path: Path, password: str) -> ssl.SSLContext:
    """Load a PKCS#12 identity while leaving no persistent PEM private key."""
    private_key, certificate, additional_certificates = (
        pkcs12.load_key_and_certificates(
            Path(pkcs12_path).read_bytes(),
            password.encode("utf-8"),
        )
    )
    if private_key is None or certificate is None:
        raise ValueError("PKCS#12 file does not contain a private key and certificate.")

    private_key_pem = private_key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    certificate_chain_pem = certificate.public_bytes(serialization.Encoding.PEM)
    for additional in additional_certificates or ():
        certificate_chain_pem += additional.public_bytes(serialization.Encoding.PEM)

    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.maximum_version = ssl.TLSVersion.TLSv1_3
    context.options |= ssl.OP_NO_COMPRESSION

    with tempfile.TemporaryDirectory(prefix="whisper-wss-tls-") as directory:
        key_path = Path(directory) / "private-key.pem"
        certificate_path = Path(directory) / "certificate-chain.pem"
        key_path.write_bytes(private_key_pem)
        certificate_path.write_bytes(certificate_chain_pem)
        context.load_cert_chain(certificate_path, key_path)

    return context
