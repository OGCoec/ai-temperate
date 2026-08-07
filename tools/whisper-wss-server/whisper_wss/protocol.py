"""Validation for the small JSON-and-binary WebSocket protocol."""

from dataclasses import dataclass
import json
import re


_LANGUAGE_PATTERN = re.compile(r"^(?:auto|[a-z]{2,3}(?:-[a-z]{2})?)$", re.IGNORECASE)


class ProtocolError(ValueError):
    """A client message violated the voice transcription protocol."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class SessionConfig:
    """Validated audio settings for one WebSocket session."""

    language: str | None
    sample_rate: int
    channels: int
    audio_format: str


def parse_control_message(message: str) -> SessionConfig:
    """Parse the mandatory session.start message."""
    try:
        payload = json.loads(message)
    except (TypeError, json.JSONDecodeError) as exception:
        raise ProtocolError("VOICE_PROTOCOL_INVALID", "Control message must be valid JSON.") from exception

    if not isinstance(payload, dict) or payload.get("type") != "session.start":
        raise ProtocolError("VOICE_PROTOCOL_INVALID", "First control message must be session.start.")
    if payload.get("format") != "pcm_s16le":
        raise ProtocolError("VOICE_AUDIO_FORMAT_INVALID", "Audio format must be pcm_s16le.")
    if payload.get("sampleRate") != 16000 or payload.get("channels") != 1:
        raise ProtocolError(
            "VOICE_AUDIO_FORMAT_INVALID",
            "Only 16 kHz mono PCM audio is supported.",
        )

    language = payload.get("language", "auto")
    if not isinstance(language, str) or not _LANGUAGE_PATTERN.fullmatch(language):
        raise ProtocolError("VOICE_PROTOCOL_INVALID", "Language must be auto or a short language code.")

    return SessionConfig(
        language=None if language.lower() == "auto" else language.lower(),
        sample_rate=16000,
        channels=1,
        audio_format="pcm_s16le",
    )


def validate_audio_chunk(chunk: bytes, max_frame_bytes: int) -> None:
    """Reject malformed or unbounded PCM frames before buffering them."""
    if not chunk:
        raise ProtocolError("VOICE_AUDIO_FORMAT_INVALID", "Audio frame must not be empty.")
    if len(chunk) > max_frame_bytes:
        raise ProtocolError("VOICE_FRAME_TOO_LARGE", "Audio frame is too large.")
    if len(chunk) % 2 != 0:
        raise ProtocolError("VOICE_AUDIO_FORMAT_INVALID", "16-bit PCM frames must contain complete samples.")
