"""Async WebSocket session handling for local Whisper transcription."""

import asyncio
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import json
import logging
import time
import uuid

from websockets.exceptions import ConnectionClosed

from .protocol import ProtocolError, parse_control_message, validate_audio_chunk
from .streaming import PartialTranscriptAccumulator
from .transcriber import TranscriptionResult


LOGGER = logging.getLogger(__name__)


@dataclass(eq=False)
class _WaitingSession:
    """One authenticated upstream connection waiting for a GPU session slot."""

    session_id: str
    websocket: object
    admitted: asyncio.Future
    enqueued_at: float
    slot_transferred: bool = False


class WhisperWebSocketServer:
    """Bridge binary PCM frames to bounded concurrent GPU transcription workers."""

    def __init__(
        self,
        transcriber,
        *,
        path="/ws/transcribe",
        partial_interval_bytes=25600,
        max_frame_bytes=131072,
        max_turn_bytes=9600000,
        queue_capacity=64,
        inference_concurrency=3,
        waiting_queue_capacity=5,
        queue_wait_timeout_ms=90000,
        partial_window_bytes=640000,
        partial_overlap_bytes=32000,
        stability_delay_ms=2000,
    ):
        if inference_concurrency < 1:
            raise ValueError("inference_concurrency must be positive.")
        if waiting_queue_capacity < 0:
            raise ValueError("waiting_queue_capacity must not be negative.")
        if queue_wait_timeout_ms < 1:
            raise ValueError("queue_wait_timeout_ms must be positive.")
        self._transcriber = transcriber
        self._path = path
        self._partial_interval_bytes = partial_interval_bytes
        self._max_frame_bytes = max_frame_bytes
        self._max_turn_bytes = max_turn_bytes
        self._audio_queue_capacity = queue_capacity
        self._inference_concurrency = inference_concurrency
        self._waiting_queue_capacity = waiting_queue_capacity
        self._queue_wait_timeout_ms = queue_wait_timeout_ms
        self._partial_window_bytes = partial_window_bytes
        self._partial_overlap_bytes = partial_overlap_bytes
        self._stability_delay_ms = stability_delay_ms
        self._inference_gate = asyncio.Semaphore(inference_concurrency)
        self._inference_executor = ThreadPoolExecutor(
            max_workers=inference_concurrency,
            thread_name_prefix="whisper-inference",
        )
        self._active_sessions = 0
        self._waiting_sessions = deque()
        self._admission_lock = asyncio.Lock()
        self.max_message_bytes = max_frame_bytes

    async def handle(self, websocket):
        """Validate one connection and run receiver and transcription workers."""
        if websocket.request.path != self._path:
            await websocket.close(code=1008, reason="Unsupported WebSocket path.")
            return

        admitted = False
        session_id = None
        try:
            try:
                first_message = await asyncio.wait_for(websocket.recv(), timeout=10)
                if not isinstance(first_message, str):
                    raise ProtocolError(
                        "VOICE_PROTOCOL_INVALID",
                        "First message must be a JSON session.start control frame.",
                    )
                config = parse_control_message(first_message)
            except asyncio.TimeoutError:
                await self._send_error(
                    websocket,
                    "VOICE_PROTOCOL_INVALID",
                    "session.start timed out.",
                )
                await websocket.close(code=1008, reason="session.start timed out.")
                return
            except ProtocolError as exception:
                await self._send_error(websocket, exception.code, str(exception))
                await websocket.close(code=1008, reason=exception.code)
                return

            session_id = uuid.uuid4().hex
            waiter = await self._admit_or_queue(websocket, session_id)
            if waiter is False:
                LOGGER.warning(
                    "voice_queue_full session_id=%s active_sessions=%s waiting_sessions=%s",
                    session_id,
                    self._active_sessions,
                    len(self._waiting_sessions),
                )
                await self._send_error(
                    websocket,
                    "VOICE_QUEUE_FULL",
                    "The local transcription waiting queue is full.",
                    retryable=True,
                )
                await websocket.close(code=1013, reason="VOICE_QUEUE_FULL")
                return
            if waiter is not None:
                if not await self._wait_for_admission(websocket, waiter):
                    return
            admitted = True

            LOGGER.info("voice_session_started session_id=%s", session_id)
            await self._send(websocket, {
                "type": "session.ready",
                "sessionId": session_id,
                "format": "pcm_s16le",
                "sampleRate": 16000,
                "channels": 1,
                "maxDurationMs": self._duration_ms(self._max_turn_bytes),
                "partialIntervalMs": self._duration_ms(self._partial_interval_bytes),
            })

            queue = asyncio.Queue(maxsize=self._audio_queue_capacity)
            receiver = asyncio.create_task(self._receive(websocket, queue))
            worker = asyncio.create_task(self._transcribe(
                websocket,
                queue,
                config,
                session_id,
            ))
            done, pending = await asyncio.wait(
                {receiver, worker},
                return_when=asyncio.FIRST_COMPLETED,
            )
            for task in pending:
                task.cancel()
            await asyncio.gather(*pending, return_exceptions=True)

            for task in done:
                exception = task.exception()
                if exception is not None and not isinstance(exception, ConnectionClosed):
                    LOGGER.error(
                        "voice_session_failed session_id=%s error_code=VOICE_TRANSCRIPTION_FAILED exception_type=%s",
                        session_id,
                        type(exception).__name__,
                    )
                    await self._send_error(
                        websocket,
                        "VOICE_TRANSCRIPTION_FAILED",
                        "Local transcription failed.",
                        retryable=True,
                    )
                    await websocket.close(code=1011, reason="VOICE_TRANSCRIPTION_FAILED")
        finally:
            if admitted:
                await self._release_session()
            if session_id is not None:
                LOGGER.info("voice_session_closed session_id=%s", session_id)

    async def _admit_or_queue(self, websocket, session_id):
        """Acquire an active slot or append one connection to the bounded FIFO queue."""
        async with self._admission_lock:
            if self._active_sessions < self._inference_concurrency:
                self._active_sessions += 1
                return None
            if len(self._waiting_sessions) >= self._waiting_queue_capacity:
                return False

            waiter = _WaitingSession(
                session_id=session_id,
                websocket=websocket,
                admitted=asyncio.get_running_loop().create_future(),
                enqueued_at=time.monotonic(),
            )
            self._waiting_sessions.append(waiter)
            position = len(self._waiting_sessions)
            # 初始排队事件必须先于任何晋升事件发出，因此在准入锁内完成这一次有界本机写出。
            try:
                await self._send(websocket, self._queued_event(waiter, position))
            except ConnectionClosed:
                self._waiting_sessions.remove(waiter)
                raise
            LOGGER.info(
                "voice_session_queued session_id=%s position=%s active_sessions=%s waiting_sessions=%s",
                session_id,
                position,
                self._active_sessions,
                len(self._waiting_sessions),
            )
            return waiter

    async def _wait_for_admission(self, websocket, waiter):
        """Wait for FIFO promotion while still accepting cancellation or disconnect."""
        receive_task = asyncio.create_task(websocket.recv())
        done, _ = await asyncio.wait(
            {waiter.admitted, receive_task},
            timeout=self._queue_wait_timeout_ms / 1000,
            return_when=asyncio.FIRST_COMPLETED,
        )

        # 取消与晋升同时发生时优先尊重用户取消，并把刚转交的名额继续传给下一位。
        if receive_task in done:
            try:
                message = receive_task.result()
            except ConnectionClosed:
                await self._withdraw_waiter(waiter)
                return False
            try:
                self._validate_queued_control(message)
            except ProtocolError as exception:
                await self._withdraw_waiter(waiter)
                await self._send_error(websocket, exception.code, str(exception))
                await websocket.close(code=1008, reason=exception.code)
                return False
            await self._withdraw_waiter(waiter)
            await websocket.close(code=1000, reason="SESSION_STOPPED")
            return False

        if waiter.admitted in done:
            receive_task.cancel()
            await asyncio.gather(receive_task, return_exceptions=True)
            LOGGER.info(
                "voice_session_promoted session_id=%s wait_ms=%s active_sessions=%s waiting_sessions=%s",
                waiter.session_id,
                round((time.monotonic() - waiter.enqueued_at) * 1000),
                self._active_sessions,
                len(self._waiting_sessions),
            )
            return True

        receive_task.cancel()
        await asyncio.gather(receive_task, return_exceptions=True)
        await self._withdraw_waiter(waiter)
        LOGGER.info(
            "voice_queue_timeout session_id=%s wait_ms=%s",
            waiter.session_id,
            round((time.monotonic() - waiter.enqueued_at) * 1000),
        )
        await self._send_error(
            websocket,
            "VOICE_QUEUE_TIMEOUT",
            "The local transcription queue wait timed out.",
            retryable=True,
        )
        await websocket.close(code=1013, reason="VOICE_QUEUE_TIMEOUT")
        return False

    async def _withdraw_waiter(self, waiter):
        async with self._admission_lock:
            if waiter in self._waiting_sessions:
                self._waiting_sessions.remove(waiter)
            elif waiter.slot_transferred:
                waiter.slot_transferred = False
                self._promote_or_release_locked()
            await self._notify_waiting_positions_locked()

    async def _release_session(self):
        async with self._admission_lock:
            self._promote_or_release_locked()
            await self._notify_waiting_positions_locked()

    def _promote_or_release_locked(self):
        while self._waiting_sessions:
            waiter = self._waiting_sessions.popleft()
            if waiter.admitted.done():
                continue
            waiter.slot_transferred = True
            waiter.admitted.set_result(None)
            return
        self._active_sessions = max(0, self._active_sessions - 1)

    async def _notify_waiting_positions_locked(self):
        if not self._waiting_sessions:
            return
        # 位置快照和写出共用准入锁，避免已经晋升的连接随后收到过期 queued 事件。
        await asyncio.gather(*(
            self._send(waiter.websocket, self._queued_event(waiter, position))
            for position, waiter in enumerate(self._waiting_sessions, start=1)
        ), return_exceptions=True)

    def _queued_event(self, waiter, position):
        return {
            "type": "session.queued",
            "sessionId": waiter.session_id,
            "position": position,
            "queueCapacity": self._waiting_queue_capacity,
            "maxWaitMs": self._queue_wait_timeout_ms,
        }

    @staticmethod
    def _validate_queued_control(message):
        if not isinstance(message, str):
            raise ProtocolError(
                "VOICE_PROTOCOL_INVALID",
                "Audio is not accepted while the session is queued.",
            )
        try:
            payload = json.loads(message)
        except json.JSONDecodeError as exception:
            raise ProtocolError(
                "VOICE_PROTOCOL_INVALID",
                "Queued control message must be valid JSON.",
            ) from exception
        if not isinstance(payload, dict) or payload != {"type": "session.stop"}:
            raise ProtocolError(
                "VOICE_PROTOCOL_INVALID",
                "Only session.stop is accepted while queued.",
            )

    async def _receive(self, websocket, queue):
        try:
            async for message in websocket:
                if isinstance(message, bytes):
                    validate_audio_chunk(message, self._max_frame_bytes)
                    await queue.put(("audio", message))
                    continue

                try:
                    payload = json.loads(message)
                except json.JSONDecodeError as exception:
                    raise ProtocolError(
                        "VOICE_PROTOCOL_INVALID",
                        "Control message must be valid JSON.",
                    ) from exception
                message_type = payload.get("type") if isinstance(payload, dict) else None
                if message_type == "input.commit":
                    await queue.put(("commit", None))
                elif message_type == "session.stop":
                    await queue.put(("stop", None))
                    return
                else:
                    raise ProtocolError(
                        "VOICE_PROTOCOL_INVALID",
                        "Control message type is not supported.",
                    )
        except ProtocolError as exception:
            await self._send_error(websocket, exception.code, str(exception))
            await websocket.close(code=1008, reason=exception.code)
        finally:
            await queue.put(("closed", None))

    async def _transcribe(self, websocket, queue, config, session_id="unknown"):
        pcm_buffer = bytearray()
        last_partial_size = 0
        last_partial_text = ""
        partial_disabled = False
        sequence = 0
        accumulator = PartialTranscriptAccumulator(
            stability_delay_ms=self._stability_delay_ms,
        )

        def append_audio(payload):
            remaining_bytes = self._max_turn_bytes - len(pcm_buffer)
            accepted_bytes = min(len(payload), remaining_bytes)
            accepted_bytes -= accepted_bytes % 2
            if accepted_bytes:
                pcm_buffer.extend(payload[:accepted_bytes])

        while True:
            message_type, payload = await queue.get()
            if message_type == "closed" or message_type == "stop":
                return
            if message_type == "audio":
                append_audio(payload)
                pending_control = None
                while True:
                    try:
                        queued_type, queued_payload = queue.get_nowait()
                    except asyncio.QueueEmpty:
                        break
                    if queued_type == "audio":
                        append_audio(queued_payload)
                        continue
                    pending_control = queued_type
                    break

                if pending_control == "closed" or pending_control == "stop":
                    return

                if len(pcm_buffer) >= self._max_turn_bytes:
                    await self._send(websocket, {
                        "type": "input.limit_reached",
                        "maxDurationMs": self._duration_ms(self._max_turn_bytes),
                    })
                    await self._send_final(
                        websocket,
                        pcm_buffer,
                        config,
                        sequence + 1,
                        session_id,
                    )
                    await websocket.close(code=1000, reason="TRANSCRIPT_FINAL")
                    return

                if pending_control == "commit":
                    await self._send_final(
                        websocket,
                        pcm_buffer,
                        config,
                        sequence + 1,
                        session_id,
                    )
                    await websocket.close(code=1000, reason="TRANSCRIPT_FINAL")
                    return

                if partial_disabled:
                    continue
                if len(pcm_buffer) - last_partial_size < self._partial_interval_bytes:
                    continue

                window_start_bytes = max(
                    0,
                    len(pcm_buffer) - self._partial_window_bytes,
                    accumulator.stable_until_ms * 32 - self._partial_overlap_bytes,
                )
                window_start_bytes -= window_start_bytes % 2
                try:
                    result = await self._infer(
                        bytes(pcm_buffer[window_start_bytes:]),
                        config.language,
                        final=False,
                        session_id=session_id,
                    )
                except Exception as exception:
                    partial_disabled = True
                    last_partial_size = len(pcm_buffer)
                    LOGGER.warning(
                        "voice_partial_inference_disabled session_id=%s exception_type=%s",
                        session_id,
                        type(exception).__name__,
                    )
                    continue
                text = accumulator.merge(
                    result,
                    window_start_ms=self._duration_ms(window_start_bytes),
                    audio_end_ms=self._duration_ms(len(pcm_buffer)),
                )
                last_partial_size = len(pcm_buffer)
                if text and text != last_partial_text:
                    sequence += 1
                    last_partial_text = text
                    await self._send(websocket, self._transcript_event(
                        "transcript.partial", sequence, text, len(pcm_buffer)
                    ))
                continue

            if message_type == "commit":
                await self._send_final(
                    websocket,
                    pcm_buffer,
                    config,
                    sequence + 1,
                    session_id,
                )
                await websocket.close(code=1000, reason="TRANSCRIPT_FINAL")
                return

    async def _infer(self, pcm_bytes, language, final, session_id="unknown"):
        started = time.perf_counter()
        async with self._inference_gate:
            result = await asyncio.get_running_loop().run_in_executor(
                self._inference_executor,
                self._transcriber.transcribe,
                pcm_bytes,
                language,
                final,
            )
        LOGGER.info(
            "voice_inference_completed session_id=%s final=%s audio_duration_ms=%s inference_ms=%s",
            session_id,
            final,
            self._duration_ms(len(pcm_bytes)),
            round((time.perf_counter() - started) * 1000),
        )
        return result

    async def _send_final(
        self,
        websocket,
        pcm_buffer,
        config,
        sequence,
        session_id="unknown",
    ):
        result = TranscriptionResult.empty()
        if pcm_buffer:
            result = await self._infer(
                bytes(pcm_buffer),
                config.language,
                final=True,
                session_id=session_id,
            )
        await self._send(websocket, self._transcript_event(
            "transcript.final",
            sequence,
            result.text,
            len(pcm_buffer),
        ))

    def close(self):
        """Stop accepting queued inference work without waiting on process shutdown."""
        self._inference_executor.shutdown(wait=False, cancel_futures=True)

    @staticmethod
    def _duration_ms(byte_count):
        return byte_count * 1000 // 32000

    @staticmethod
    def _transcript_event(event_type, sequence, text, byte_count):
        return {
            "type": event_type,
            "sequence": sequence,
            "text": text,
            "startMs": 0,
            "endMs": byte_count * 1000 // 32000,
        }

    @staticmethod
    async def _send(websocket, payload):
        await websocket.send(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))

    @classmethod
    async def _send_error(cls, websocket, code, message, retryable=False):
        if websocket.state.name == "OPEN":
            await cls._send(websocket, {
                "type": "error",
                "code": code,
                "message": message,
                "retryable": retryable,
            })
