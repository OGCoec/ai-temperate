"""GPU-backed faster-whisper adapter for little-endian 16-bit PCM."""

from dataclasses import dataclass
from pathlib import Path

from faster_whisper import WhisperModel
import numpy as np


@dataclass(frozen=True)
class TranscriptionSegment:
    """One timestamped transcript segment relative to the submitted PCM snapshot."""

    start_ms: int
    end_ms: int
    text: str


@dataclass(frozen=True)
class TranscriptionResult:
    """Normalized text and segments returned by one faster-whisper inference."""

    text: str
    segments: tuple[TranscriptionSegment, ...]

    @classmethod
    def empty(cls):
        return cls(text="", segments=())


class FasterWhisperTranscriber:
    """Keep one Medium model loaded and transcribe immutable PCM snapshots."""

    def __init__(self, model):
        self._model = model

    @classmethod
    def load(cls, model_path: Path, num_workers: int = 3):
        model = WhisperModel(
            str(model_path),
            device="cuda",
            compute_type="int8_float16",
            num_workers=num_workers,
        )
        return cls(model)

    def transcribe(
        self,
        pcm_bytes: bytes,
        language: str | None,
        final: bool,
    ) -> TranscriptionResult:
        audio = np.frombuffer(pcm_bytes, dtype="<i2").astype(np.float32)
        audio /= 32768.0
        segments, _ = self._model.transcribe(
            audio,
            language=language,
            beam_size=5 if final else 1,
            vad_filter=True,
            condition_on_previous_text=False,
        )
        normalized_segments = tuple(
            TranscriptionSegment(
                start_ms=max(0, round(segment.start * 1000)),
                end_ms=max(0, round(segment.end * 1000)),
                text=segment.text.strip(),
            )
            for segment in segments
            if segment.text.strip()
        )
        return TranscriptionResult(
            text=" ".join(segment.text for segment in normalized_segments),
            segments=normalized_segments,
        )
