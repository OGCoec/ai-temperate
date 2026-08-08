"""Process entry point for the loopback-only Whisper WSS service."""

import asyncio
import logging
import os

from websockets.asyncio.server import serve

from .config import ServerSettings
from .server import WhisperWebSocketServer
from .tls import build_server_ssl_context
from .transcriber import FasterWhisperTranscriber


def configure_logging() -> None:
    """Keep operational metadata while suppressing third-party audio analysis details."""
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    logging.getLogger("faster_whisper").setLevel(logging.WARNING)
    logging.getLogger("websockets.server").setLevel(logging.WARNING)


async def run() -> None:
    configure_logging()
    settings = ServerSettings.from_environment(os.environ)
    ssl_context = build_server_ssl_context(
        settings.pkcs12_path,
        settings.pkcs12_password,
    )

    print("Loading faster-whisper Medium on CUDA...", flush=True)
    transcriber = await asyncio.to_thread(
        FasterWhisperTranscriber.load,
        settings.model_path,
        settings.inference_concurrency,
    )
    application = WhisperWebSocketServer(
        transcriber,
        path=settings.path,
        partial_interval_bytes=settings.partial_interval_ms * 32,
        max_turn_bytes=settings.max_duration_ms * 32,
        inference_concurrency=settings.inference_concurrency,
        waiting_queue_capacity=settings.waiting_queue_capacity,
        queue_wait_timeout_ms=settings.queue_wait_timeout_ms,
        partial_window_bytes=settings.partial_window_ms * 32,
        stability_delay_ms=settings.stability_delay_ms,
    )

    allowed_origins = [None, *settings.allowed_origins]
    try:
        async with serve(
            application.handle,
            settings.host,
            settings.port,
            ssl=ssl_context,
            origins=allowed_origins,
            compression=None,
            max_size=application.max_message_bytes,
            max_queue=16,
            ping_interval=20,
            ping_timeout=20,
            close_timeout=5,
        ) as running:
            print(
                f"READY wss://{settings.host}:{settings.port}{settings.path}",
                flush=True,
            )
            await running.serve_forever()
    finally:
        application.close()


def main() -> None:
    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        print("Whisper WSS service stopped.", flush=True)


if __name__ == "__main__":
    main()
