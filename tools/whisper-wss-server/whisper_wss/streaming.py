"""Stable-prefix assembly for rolling-window Whisper partial results."""


class PartialTranscriptAccumulator:
    """Commit old timestamped segments once while keeping the newest tail replaceable."""

    def __init__(self, *, stability_delay_ms=2000):
        self._stability_delay_ms = stability_delay_ms
        self._stable_parts = []
        self._stable_until_ms = 0

    @property
    def stable_until_ms(self):
        return self._stable_until_ms

    def merge(self, result, *, window_start_ms, audio_end_ms):
        stable_cutoff_ms = max(0, audio_end_ms - self._stability_delay_ms)
        provisional_parts = []

        for segment in result.segments:
            global_end_ms = window_start_ms + segment.end_ms
            text = segment.text.strip()
            if not text:
                continue
            if global_end_ms <= self._stable_until_ms:
                continue
            if global_end_ms <= stable_cutoff_ms:
                self._stable_parts.append(text)
                self._stable_until_ms = global_end_ms
            else:
                provisional_parts.append(text)

        if not result.segments and result.text.strip():
            provisional_parts.append(result.text.strip())
        return " ".join((*self._stable_parts, *provisional_parts)).strip()
