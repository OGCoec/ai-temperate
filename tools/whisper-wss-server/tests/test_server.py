import asyncio
import datetime
import json
import pathlib
import ssl
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from websockets.asyncio.client import connect
from websockets.asyncio.server import serve


PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

from whisper_wss.server import WhisperWebSocketServer  # noqa: E402
from whisper_wss.transcriber import (  # noqa: E402
    TranscriptionResult,
    TranscriptionSegment,
)


class FakeTranscriber:

    def transcribe(self, pcm_bytes, language, final):
        prefix = "final" if final else "partial"
        duration_ms = len(pcm_bytes) * 1000 // 32000
        text = f"{prefix}:{language}:{len(pcm_bytes)}"
        return TranscriptionResult(
            text=text,
            segments=(TranscriptionSegment(0, duration_ms, text),),
        )


class BlockingTranscriber(FakeTranscriber):

    def __init__(self):
        self.lock = threading.Lock()
        self.active = 0
        self.maximum_active = 0
        self.three_started = threading.Event()
        self.release = threading.Event()

    def transcribe(self, pcm_bytes, language, final):
        with self.lock:
            self.active += 1
            self.maximum_active = max(self.maximum_active, self.active)
            if self.active == 3:
                self.three_started.set()
        self.release.wait(timeout=2)
        try:
            return super().transcribe(pcm_bytes, language, final)
        finally:
            with self.lock:
                self.active -= 1


class RecordingTranscriber(FakeTranscriber):

    def __init__(self):
        self.calls = []

    def transcribe(self, pcm_bytes, language, final):
        self.calls.append((final, len(pcm_bytes)))
        return super().transcribe(pcm_bytes, language, final)


class PartialFailingTranscriber(RecordingTranscriber):

    def __init__(self):
        super().__init__()
        self.partial_started = threading.Event()

    def transcribe(self, pcm_bytes, language, final):
        self.calls.append((final, len(pcm_bytes)))
        if not final:
            self.partial_started.set()
            raise RuntimeError("partial inference failed")
        return FakeTranscriber.transcribe(self, pcm_bytes, language, final)


class CapturingWebSocket:

    def __init__(self):
        self.messages = []
        self.message_sent = asyncio.Event()
        self.closed = []

    async def send(self, payload):
        self.messages.append(json.loads(payload))
        self.message_sent.set()

    async def close(self, code, reason):
        self.closed.append((code, reason))


def tls_contexts(directory):
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
            x509.SubjectAlternativeName([
                x509.DNSName("localhost"),
                x509.IPAddress(__import__("ipaddress").ip_address("127.0.0.1")),
            ]),
            critical=False,
        )
        .sign(key, hashes.SHA256())
    )
    key_path = pathlib.Path(directory) / "key.pem"
    cert_path = pathlib.Path(directory) / "cert.pem"
    key_path.write_bytes(key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ))
    cert_path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))

    server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    server_context.load_cert_chain(cert_path, key_path)
    client_context = ssl.create_default_context(cafile=str(cert_path))
    return server_context, client_context


class WebSocketServerTest(unittest.IsolatedAsyncioTestCase):

    @staticmethod
    async def _start(websocket, language="auto"):
        await websocket.send(json.dumps({
            "type": "session.start",
            "language": language,
            "format": "pcm_s16le",
            "sampleRate": 16000,
            "channels": 1,
        }))

    async def test_admits_three_queues_five_and_rejects_ninth_session(self):
        with tempfile.TemporaryDirectory() as directory:
            server_ssl, client_ssl = tls_contexts(directory)
            application = WhisperWebSocketServer(
                FakeTranscriber(),
                inference_concurrency=3,
                waiting_queue_capacity=5,
                queue_wait_timeout_ms=90000,
            )
            async with serve(
                application.handle,
                "127.0.0.1",
                0,
                ssl=server_ssl,
                max_size=application.max_message_bytes,
            ) as running:
                port = running.sockets[0].getsockname()[1]
                connections = []
                try:
                    for index in range(8):
                        websocket = await connect(
                            f"wss://127.0.0.1:{port}/ws/transcribe",
                            ssl=client_ssl,
                        )
                        connections.append(websocket)
                        await self._start(websocket)
                        event = json.loads(await asyncio.wait_for(websocket.recv(), 2))
                        if index < 3:
                            self.assertEqual("session.ready", event["type"])
                        else:
                            self.assertEqual("session.queued", event["type"])
                            self.assertEqual(index - 2, event["position"])
                            self.assertEqual(5, event["queueCapacity"])
                            self.assertEqual(90000, event["maxWaitMs"])

                    async with connect(
                        f"wss://127.0.0.1:{port}/ws/transcribe",
                        ssl=client_ssl,
                    ) as ninth:
                        await self._start(ninth)
                        full = json.loads(await asyncio.wait_for(ninth.recv(), 2))
                        self.assertEqual("error", full["type"])
                        self.assertEqual("VOICE_QUEUE_FULL", full["code"])

                    await connections[0].close(code=1000, reason="TEST_RELEASE")
                    promoted = json.loads(await asyncio.wait_for(connections[3].recv(), 2))
                    self.assertEqual("session.ready", promoted["type"])
                finally:
                    await asyncio.gather(
                        *(websocket.close() for websocket in connections),
                        return_exceptions=True,
                    )
                    application.close()

    async def test_cancelled_waiter_is_removed_and_positions_are_updated(self):
        with tempfile.TemporaryDirectory() as directory:
            server_ssl, client_ssl = tls_contexts(directory)
            application = WhisperWebSocketServer(
                FakeTranscriber(),
                inference_concurrency=1,
                waiting_queue_capacity=2,
                queue_wait_timeout_ms=90000,
            )
            async with serve(
                application.handle,
                "127.0.0.1",
                0,
                ssl=server_ssl,
                max_size=application.max_message_bytes,
            ) as running:
                port = running.sockets[0].getsockname()[1]
                active = await connect(f"wss://127.0.0.1:{port}/ws/transcribe", ssl=client_ssl)
                first_waiter = await connect(f"wss://127.0.0.1:{port}/ws/transcribe", ssl=client_ssl)
                second_waiter = await connect(f"wss://127.0.0.1:{port}/ws/transcribe", ssl=client_ssl)
                try:
                    await self._start(active)
                    await active.recv()
                    await self._start(first_waiter)
                    self.assertEqual(1, json.loads(await first_waiter.recv())["position"])
                    await self._start(second_waiter)
                    self.assertEqual(2, json.loads(await second_waiter.recv())["position"])

                    await first_waiter.send(json.dumps({"type": "session.stop"}))
                    updated = json.loads(await asyncio.wait_for(second_waiter.recv(), 2))
                    self.assertEqual("session.queued", updated["type"])
                    self.assertEqual(1, updated["position"])

                    await active.close(code=1000, reason="TEST_RELEASE")
                    promoted = json.loads(await asyncio.wait_for(second_waiter.recv(), 2))
                    self.assertEqual("session.ready", promoted["type"])
                finally:
                    await asyncio.gather(
                        active.close(), first_waiter.close(), second_waiter.close(),
                        return_exceptions=True,
                    )
                    application.close()

    async def test_waiter_times_out_without_consuming_an_active_slot(self):
        with tempfile.TemporaryDirectory() as directory:
            server_ssl, client_ssl = tls_contexts(directory)
            application = WhisperWebSocketServer(
                FakeTranscriber(),
                inference_concurrency=1,
                waiting_queue_capacity=1,
                queue_wait_timeout_ms=50,
            )
            async with serve(
                application.handle,
                "127.0.0.1",
                0,
                ssl=server_ssl,
                max_size=application.max_message_bytes,
            ) as running:
                port = running.sockets[0].getsockname()[1]
                async with connect(
                    f"wss://127.0.0.1:{port}/ws/transcribe", ssl=client_ssl
                ) as active:
                    await self._start(active)
                    await active.recv()
                    async with connect(
                        f"wss://127.0.0.1:{port}/ws/transcribe", ssl=client_ssl
                    ) as waiter:
                        await self._start(waiter)
                        self.assertEqual("session.queued", json.loads(await waiter.recv())["type"])
                        timeout = json.loads(await asyncio.wait_for(waiter.recv(), 2))
                        self.assertEqual("VOICE_QUEUE_TIMEOUT", timeout["code"])
            application.close()

    async def test_inference_executor_runs_three_calls_and_blocks_the_fourth(self):
        transcriber = BlockingTranscriber()
        application = WhisperWebSocketServer(
            transcriber,
            inference_concurrency=3,
        )
        tasks = [
            asyncio.create_task(application._infer(b"\x00\x00", None, False, str(index)))
            for index in range(4)
        ]
        try:
            started = await asyncio.to_thread(transcriber.three_started.wait, 2)
            self.assertTrue(started)
            self.assertEqual(3, transcriber.maximum_active)
            self.assertEqual(0, sum(task.done() for task in tasks))
        finally:
            transcriber.release.set()
            await asyncio.gather(*tasks)
            application.close()

    async def test_accepts_no_origin_for_java_loopback_and_rejects_unknown_origin(self):
        with tempfile.TemporaryDirectory() as directory:
            server_ssl, client_ssl = tls_contexts(directory)
            application = WhisperWebSocketServer(FakeTranscriber())
            async with serve(
                application.handle,
                "127.0.0.1",
                0,
                ssl=server_ssl,
                origins=[None, "https://java.local"],
                max_size=application.max_message_bytes,
            ) as running:
                port = running.sockets[0].getsockname()[1]
                async with connect(
                    f"wss://127.0.0.1:{port}/ws/transcribe",
                    ssl=client_ssl,
                ) as loopback:
                    await loopback.send(json.dumps({
                        "type": "session.start",
                        "language": "auto",
                        "format": "pcm_s16le",
                        "sampleRate": 16000,
                        "channels": 1,
                    }))
                    ready = json.loads(await loopback.recv())
                    self.assertEqual("session.ready", ready["type"])

                with self.assertRaises(Exception):
                    async with connect(
                        f"wss://127.0.0.1:{port}/ws/transcribe",
                        ssl=client_ssl,
                        origin="https://untrusted.invalid",
                    ):
                        pass
            application.close()

    async def test_streams_partial_then_final_over_wss(self):
        with tempfile.TemporaryDirectory() as directory:
            server_ssl, client_ssl = tls_contexts(directory)
            application = WhisperWebSocketServer(
                FakeTranscriber(),
                partial_interval_bytes=32000,
            )
            async with serve(
                application.handle,
                "127.0.0.1",
                0,
                ssl=server_ssl,
                max_size=application.max_message_bytes,
            ) as running:
                port = running.sockets[0].getsockname()[1]
                async with connect(
                    f"wss://127.0.0.1:{port}/ws/transcribe",
                    ssl=client_ssl,
                ) as websocket:
                    await websocket.send(json.dumps({
                        "type": "session.start",
                        "language": "zh",
                        "format": "pcm_s16le",
                        "sampleRate": 16000,
                        "channels": 1,
                    }))
                    ready = json.loads(await asyncio.wait_for(websocket.recv(), 2))
                    self.assertEqual("session.ready", ready["type"])

                    await websocket.send(b"\x00\x00" * 16000)
                    partial = json.loads(await asyncio.wait_for(websocket.recv(), 2))
                    self.assertEqual("transcript.partial", partial["type"])
                    self.assertEqual("partial:zh:32000", partial["text"])

                    await websocket.send(json.dumps({"type": "input.commit"}))
                    final = json.loads(await asyncio.wait_for(websocket.recv(), 2))
                    self.assertEqual("transcript.final", final["type"])
                    self.assertEqual("final:zh:32000", final["text"])
            application.close()

    async def test_coalesces_audio_backlog_into_one_latest_partial(self):
        transcriber = RecordingTranscriber()
        application = WhisperWebSocketServer(
            transcriber,
            partial_interval_bytes=4000,
        )
        websocket = CapturingWebSocket()
        queue = asyncio.Queue()
        frame = b"\x00\x00" * 2000
        for _ in range(4):
            await queue.put(("audio", frame))

        try:
            worker = asyncio.create_task(application._transcribe(
                websocket,
                queue,
                SimpleNamespace(language="zh"),
                "coalesced-session",
            ))
            await asyncio.wait_for(websocket.message_sent.wait(), 2)
            await queue.put(("stop", None))
            await asyncio.wait_for(worker, 2)

            partials = [message for message in websocket.messages
                        if message["type"] == "transcript.partial"]
            self.assertEqual(1, len(partials))
            self.assertEqual("partial:zh:16000", partials[0]["text"])
            self.assertEqual([(False, 16000)], transcriber.calls)
        finally:
            application.close()

    async def test_commit_preempts_stale_partials_and_excludes_later_audio(self):
        transcriber = RecordingTranscriber()
        application = WhisperWebSocketServer(
            transcriber,
            partial_interval_bytes=4000,
        )
        websocket = CapturingWebSocket()
        queue = asyncio.Queue()
        frame = b"\x00\x00" * 2000
        for _ in range(3):
            await queue.put(("audio", frame))
        await queue.put(("commit", None))
        await queue.put(("audio", frame))

        try:
            await asyncio.wait_for(application._transcribe(
                websocket,
                queue,
                SimpleNamespace(language="zh"),
                "commit-session",
            ), 2)

            self.assertEqual([], [message for message in websocket.messages
                                  if message["type"] == "transcript.partial"])
            final = next(message for message in websocket.messages
                         if message["type"] == "transcript.final")
            self.assertEqual("final:zh:12000", final["text"])
            self.assertEqual([(True, 12000)], transcriber.calls)
        finally:
            application.close()

    async def test_partial_failure_disables_preview_but_preserves_final(self):
        transcriber = PartialFailingTranscriber()
        application = WhisperWebSocketServer(
            transcriber,
            partial_interval_bytes=4000,
        )
        websocket = CapturingWebSocket()
        queue = asyncio.Queue()
        await queue.put(("audio", b"\x00\x00" * 2000))

        try:
            worker = asyncio.create_task(application._transcribe(
                websocket,
                queue,
                SimpleNamespace(language="zh"),
                "partial-failure-session",
            ))
            started = await asyncio.to_thread(
                transcriber.partial_started.wait, 2)
            self.assertTrue(started)
            await queue.put(("commit", None))
            await asyncio.wait_for(worker, 2)

            self.assertEqual([], [message for message in websocket.messages
                                  if message["type"] == "transcript.partial"])
            final = next(message for message in websocket.messages
                         if message["type"] == "transcript.final")
            self.assertEqual("final:zh:4000", final["text"])
            self.assertEqual([(False, 4000), (True, 4000)], transcriber.calls)
        finally:
            application.close()

    async def test_five_minute_limit_auto_commits_without_buffering_extra_audio(self):
        with tempfile.TemporaryDirectory() as directory:
            server_ssl, client_ssl = tls_contexts(directory)
            application = WhisperWebSocketServer(
                FakeTranscriber(),
                partial_interval_bytes=64,
                max_turn_bytes=8,
            )
            async with serve(
                application.handle,
                "127.0.0.1",
                0,
                ssl=server_ssl,
                max_size=application.max_message_bytes,
            ) as running:
                port = running.sockets[0].getsockname()[1]
                async with connect(
                    f"wss://127.0.0.1:{port}/ws/transcribe",
                    ssl=client_ssl,
                ) as websocket:
                    await websocket.send(json.dumps({
                        "type": "session.start",
                        "language": "auto",
                        "format": "pcm_s16le",
                        "sampleRate": 16000,
                        "channels": 1,
                    }))
                    await websocket.recv()
                    await websocket.send(b"\x00\x00" * 6)

                    limited = json.loads(await asyncio.wait_for(websocket.recv(), 2))
                    final = json.loads(await asyncio.wait_for(websocket.recv(), 2))

                    self.assertEqual("input.limit_reached", limited["type"])
                    self.assertEqual("transcript.final", final["type"])
                    self.assertEqual("final:None:8", final["text"])
            application.close()

    async def test_rejects_audio_while_connection_is_waiting(self):
        with tempfile.TemporaryDirectory() as directory:
            server_ssl, client_ssl = tls_contexts(directory)
            application = WhisperWebSocketServer(
                FakeTranscriber(),
                inference_concurrency=1,
                waiting_queue_capacity=1,
            )
            async with serve(
                application.handle,
                "127.0.0.1",
                0,
                ssl=server_ssl,
                max_size=application.max_message_bytes,
            ) as running:
                port = running.sockets[0].getsockname()[1]
                async with connect(
                    f"wss://127.0.0.1:{port}/ws/transcribe", ssl=client_ssl
                ) as active:
                    await self._start(active)
                    await active.recv()
                    async with connect(
                        f"wss://127.0.0.1:{port}/ws/transcribe", ssl=client_ssl
                    ) as waiter:
                        await self._start(waiter)
                        await waiter.recv()
                        await waiter.send(b"\x00\x00")
                        invalid = json.loads(await asyncio.wait_for(waiter.recv(), 2))
                        self.assertEqual("VOICE_PROTOCOL_INVALID", invalid["code"])
            application.close()


if __name__ == "__main__":
    unittest.main()
