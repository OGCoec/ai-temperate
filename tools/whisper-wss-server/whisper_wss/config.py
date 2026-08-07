"""Environment-backed configuration with loopback-only safety defaults."""

from dataclasses import dataclass
import ipaddress
from pathlib import Path
from typing import Mapping


@dataclass(frozen=True)
class ServerSettings:
    """Validated process configuration for the local WSS server."""

    host: str
    port: int
    path: str
    model_path: Path
    pkcs12_path: Path
    pkcs12_password: str
    allowed_origins: tuple[str, ...]
    partial_interval_ms: int
    max_duration_ms: int
    inference_concurrency: int
    waiting_queue_capacity: int
    queue_wait_timeout_ms: int
    partial_window_ms: int
    stability_delay_ms: int

    @classmethod
    def from_environment(cls, environment: Mapping[str, str]):
        host = environment.get("WHISPER_WSS_HOST", "127.0.0.1").strip()
        try:
            if not ipaddress.ip_address(host).is_loopback:
                raise ValueError("WHISPER_WSS_HOST must be a loopback address.")
        except ValueError as exception:
            if "loopback" in str(exception):
                raise
            raise ValueError("WHISPER_WSS_HOST must be a loopback IP address.") from exception

        port = int(environment.get("WHISPER_WSS_PORT", "7896"))
        if port < 1024 or port > 65535:
            raise ValueError("WHISPER_WSS_PORT must be between 1024 and 65535.")

        path = environment.get("WHISPER_WSS_PATH", "/ws/transcribe").strip()
        if not path.startswith("/") or "?" in path or "#" in path:
            raise ValueError("WHISPER_WSS_PATH must be an absolute path without query or fragment.")

        model_path = Path(_required(environment, "WHISPER_WSS_MODEL_PATH")).resolve()
        if not model_path.is_dir() or not (model_path / "model.bin").is_file():
            raise ValueError("WHISPER_WSS_MODEL_PATH must contain model.bin.")

        pkcs12_path = Path(_required(environment, "WHISPER_WSS_PKCS12_PATH")).resolve()
        if not pkcs12_path.is_file():
            raise ValueError("WHISPER_WSS_PKCS12_PATH must point to a readable file.")

        partial_interval_ms = int(environment.get("WHISPER_WSS_PARTIAL_INTERVAL_MS", "1500"))
        if partial_interval_ms < 500 or partial_interval_ms > 5000:
            raise ValueError("WHISPER_WSS_PARTIAL_INTERVAL_MS must be between 500 and 5000.")

        max_duration_ms = int(environment.get("WHISPER_WSS_MAX_DURATION_MS", "300000"))
        if max_duration_ms < 1000 or max_duration_ms > 300000:
            raise ValueError("WHISPER_WSS_MAX_DURATION_MS must be between 1000 and 300000.")

        inference_concurrency = int(environment.get("WHISPER_WSS_INFERENCE_CONCURRENCY", "3"))
        if inference_concurrency < 1 or inference_concurrency > 4:
            raise ValueError("WHISPER_WSS_INFERENCE_CONCURRENCY must be between 1 and 4.")

        waiting_queue_capacity = int(environment.get("WHISPER_WSS_WAITING_QUEUE_CAPACITY", "5"))
        if waiting_queue_capacity < 0 or waiting_queue_capacity > 32:
            raise ValueError("WHISPER_WSS_WAITING_QUEUE_CAPACITY must be between 0 and 32.")

        queue_wait_timeout_ms = int(environment.get("WHISPER_WSS_QUEUE_WAIT_TIMEOUT_MS", "90000"))
        if queue_wait_timeout_ms < 1000 or queue_wait_timeout_ms > 300000:
            raise ValueError("WHISPER_WSS_QUEUE_WAIT_TIMEOUT_MS must be between 1000 and 300000.")

        partial_window_ms = int(environment.get("WHISPER_WSS_PARTIAL_WINDOW_MS", "20000"))
        if partial_window_ms < 5000 or partial_window_ms > 30000:
            raise ValueError("WHISPER_WSS_PARTIAL_WINDOW_MS must be between 5000 and 30000.")

        stability_delay_ms = int(environment.get("WHISPER_WSS_STABILITY_DELAY_MS", "2000"))
        if stability_delay_ms < 500 or stability_delay_ms > 5000:
            raise ValueError("WHISPER_WSS_STABILITY_DELAY_MS must be between 500 and 5000.")

        origins = tuple(
            value.strip()
            for value in environment.get("WHISPER_WSS_ALLOWED_ORIGINS", "").split(",")
            if value.strip()
        )
        return cls(
            host=host,
            port=port,
            path=path,
            model_path=model_path,
            pkcs12_path=pkcs12_path,
            pkcs12_password=_required(environment, "WHISPER_WSS_PKCS12_PASSWORD"),
            allowed_origins=origins,
            partial_interval_ms=partial_interval_ms,
            max_duration_ms=max_duration_ms,
            inference_concurrency=inference_concurrency,
            waiting_queue_capacity=waiting_queue_capacity,
            queue_wait_timeout_ms=queue_wait_timeout_ms,
            partial_window_ms=partial_window_ms,
            stability_delay_ms=stability_delay_ms,
        )


def _required(environment: Mapping[str, str], name: str) -> str:
    value = environment.get(name, "").strip()
    if not value:
        raise ValueError(f"{name} is required.")
    return value
