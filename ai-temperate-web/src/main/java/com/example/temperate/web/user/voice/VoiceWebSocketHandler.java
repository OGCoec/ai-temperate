package com.example.temperate.web.user.voice;

import com.example.temperate.service.user.voice.VoiceClientPlatform;
import com.example.temperate.service.user.voice.VoiceErrorCode;
import com.example.temperate.service.user.voice.VoiceException;
import com.example.temperate.service.user.voice.config.VoiceProperties;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionGateway;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionListener;
import com.example.temperate.service.user.voice.gateway.VoiceTranscriptionSession;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketService;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * 认证公开语音 WebSocket 首帧并在客户端与本机 Whisper 之间流式转发 PCM 和 JSON 事件。
 *
 * <p>每个连接的阶段、字节计数和上游会话都封装在独立 Connection 中；单例 Handler 不保存请求级可变状态。
 * Java 只执行有界转发，不缓存完整录音，也不会把最终文本自动提交给 ChatClient。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public final class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private static final String CONNECTION_ATTRIBUTE =
            VoiceWebSocketHandler.class.getName() + ".connection";
    private static final int MAX_START_MESSAGE_BYTES = 4096;
    private static final int MAX_BINARY_FRAME_BYTES = 128 * 1024;
    private static final int MAX_UPSTREAM_EVENT_CHARACTERS = 64 * 1024;
    private static final int MAX_TRANSCRIPT_CHARACTERS = 48 * 1024;
    private static final int CLIENT_SEND_TIME_LIMIT_MS = 10_000;
    private static final int CLIENT_SEND_BUFFER_LIMIT_BYTES = 1024 * 1024;
    private static final int MAX_PENDING_ADMISSION_EVENTS = 8;

    private final VoiceSessionTicketService ticketService;
    private final VoiceTranscriptionGateway gateway;
    private final VoiceProperties properties;
    private final ObjectMapper objectMapper;

    public VoiceWebSocketHandler(
            VoiceSessionTicketService ticketService,
            VoiceTranscriptionGateway gateway,
            VoiceProperties properties,
            ObjectMapper objectMapper) {
        this.ticketService = ticketService;
        this.gateway = gateway;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession serialized = new ConcurrentWebSocketSessionDecorator(
                session,
                CLIENT_SEND_TIME_LIMIT_MS,
                CLIENT_SEND_BUFFER_LIMIT_BYTES);
        Connection connection = new Connection(
                serialized,
                ticketService,
                gateway,
                properties,
                objectMapper,
                Boolean.TRUE.equals(session.getAttributes().get(
                        VoiceWebSocketOriginInterceptor.ORIGIN_PRESENT_ATTRIBUTE)));
        session.getAttributes().put(CONNECTION_ATTRIBUTE, connection);
        connection.scheduleAuthenticationTimeout();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Connection connection = connection(session);
        try {
            connection.handleText(message.getPayload());
        } catch (VoiceException exception) {
            connection.fail(
                    exception.code(),
                    exception.getMessage(),
                    exception.retryable(),
                    CloseStatus.POLICY_VIOLATION);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        connection(session).handleAudio(message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        connection(session).fail(
                VoiceErrorCode.VOICE_UPSTREAM_UNAVAILABLE,
                "语音连接已经中断。",
                true,
                CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object value = session.getAttributes().get(CONNECTION_ATTRIBUTE);
        if (value instanceof Connection connection) {
            connection.closeFromClient(status.getCode());
        }
    }

    private static Connection connection(WebSocketSession session) {
        Object value = session.getAttributes().get(CONNECTION_ATTRIBUTE);
        if (value instanceof Connection connection) {
            return connection;
        }
        throw new IllegalStateException("Voice WebSocket connection state is missing.");
    }

    private enum Stage {
        AWAITING_START,
        CONNECTING,
        QUEUED,
        READY,
        FINALIZING,
        CLOSED
    }

    /**
     * 承载一个公开连接的完整状态机；所有阶段变更在 synchronized 方法内完成，异步回调不能交叉破坏顺序。
     */
    private static final class Connection implements VoiceTranscriptionListener {

        private final WebSocketSession client;
        private final VoiceSessionTicketService ticketService;
        private final VoiceTranscriptionGateway gateway;
        private final VoiceProperties properties;
        private final ObjectMapper objectMapper;
        private final boolean originPresent;

        private Stage stage = Stage.AWAITING_START;
        private VoiceTranscriptionSession upstream;
        private final Deque<String> pendingAdmissionEvents = new ArrayDeque<>();
        private long acceptedAudioBytes;
        private int lastTranscriptSequence = -1;

        private Connection(
                WebSocketSession client,
                VoiceSessionTicketService ticketService,
                VoiceTranscriptionGateway gateway,
                VoiceProperties properties,
                ObjectMapper objectMapper,
                boolean originPresent) {
            this.client = client;
            this.ticketService = ticketService;
            this.gateway = gateway;
            this.properties = properties;
            this.objectMapper = objectMapper;
            this.originPresent = originPresent;
        }

        private void scheduleAuthenticationTimeout() {
            CompletableFuture.runAsync(
                    () -> {
                        synchronized (this) {
                            if (stage == Stage.AWAITING_START) {
                                fail(
                                        VoiceErrorCode.VOICE_TICKET_INVALID,
                                        "语音连接认证超时。",
                                        false,
                                        CloseStatus.POLICY_VIOLATION);
                            }
                        }
                    },
                    CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS));
        }

        private synchronized void handleText(String payload) {
            if (stage == Stage.CLOSED) {
                return;
            }
            if (stage == Stage.AWAITING_START) {
                start(payload);
                return;
            }
            JsonNode control = parseControl(payload);
            String type = control.path("type").asText("");
            if ("input.commit".equals(type)
                    && control.size() == 1
                    && stage == Stage.READY) {
                stage = Stage.FINALIZING;
                scheduleFinalTimeout();
                sendUpstreamText("{\"type\":\"input.commit\"}");
                return;
            }
            if ("session.stop".equals(type) && control.size() == 1) {
                stopNormally();
                return;
            }
            fail(
                    VoiceErrorCode.VOICE_PROTOCOL_INVALID,
                    "当前语音连接状态不接受该控制消息。",
                    false,
                    CloseStatus.POLICY_VIOLATION);
        }

        private void start(String payload) {
            if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > MAX_START_MESSAGE_BYTES) {
                fail(
                        VoiceErrorCode.VOICE_PROTOCOL_INVALID,
                        "语音连接首帧过大。",
                        false,
                        CloseStatus.POLICY_VIOLATION);
                return;
            }
            try {
                VoiceWebSocketStartMessage start = VoiceWebSocketStartMessage.parse(
                        objectMapper,
                        payload);
                VoiceSessionTicketSnapshot ticket = ticketService.consume(start.ticket());
                if ((originPresent && ticket.platform() != VoiceClientPlatform.H5)
                        || (!originPresent && ticket.platform() != VoiceClientPlatform.ANDROID)) {
                    throw new VoiceException(
                            VoiceErrorCode.VOICE_TICKET_INVALID,
                            "语音连接票据与客户端平台不匹配。",
                            false);
                }
                stage = Stage.CONNECTING;
                gateway.open(start.upstreamJson(objectMapper), this)
                        .whenComplete((opened, error) -> {
                            synchronized (this) {
                                if (error != null) {
                                    handleAsyncFailure(error);
                                    return;
                                }
                                if (stage == Stage.CLOSED) {
                                    opened.close(1000, "CLIENT_CLOSED");
                                    return;
                                }
                                upstream = opened;
                                // 上游可能在 connect Future 完成前回送 queued/ready；先保存会话引用，再按原顺序重放。
                                while (!pendingAdmissionEvents.isEmpty()
                                        && stage != Stage.CLOSED) {
                                    onText(pendingAdmissionEvents.removeFirst());
                                }
                            }
                        });
            } catch (VoiceException exception) {
                fail(
                        exception.code(),
                        exception.getMessage(),
                        exception.retryable(),
                        CloseStatus.POLICY_VIOLATION);
            }
        }

        private synchronized void handleAudio(ByteBuffer payload) {
            if (stage == Stage.FINALIZING) {
                // 五分钟硬边界或 commit 后丢弃在途尾帧，不能让客户端队列竞态破坏正在执行的最终识别。
                return;
            }
            if (stage != Stage.READY) {
                fail(
                        VoiceErrorCode.VOICE_PROTOCOL_INVALID,
                        "语音连接尚未准备好接收音频。",
                        false,
                        CloseStatus.POLICY_VIOLATION);
                return;
            }
            int frameBytes = payload.remaining();
            if (frameBytes > MAX_BINARY_FRAME_BYTES) {
                fail(
                        VoiceErrorCode.VOICE_FRAME_TOO_LARGE,
                        "PCM 音频帧超过允许大小。",
                        false,
                        CloseStatus.TOO_BIG_TO_PROCESS);
                return;
            }
            if (frameBytes <= 0 || frameBytes % 2 != 0) {
                fail(
                        VoiceErrorCode.VOICE_AUDIO_FORMAT_INVALID,
                        "PCM 音频帧必须包含完整的十六位采样。",
                        false,
                        CloseStatus.POLICY_VIOLATION);
                return;
            }
            long remaining = properties.maxAudioBytes() - acceptedAudioBytes;
            int accepted = Math.toIntExact(Math.min(remaining, frameBytes));
            accepted -= accepted % 2;
            if (accepted <= 0) {
                return;
            }
            ByteBuffer copy = ByteBuffer.allocate(accepted);
            ByteBuffer source = payload.asReadOnlyBuffer();
            source.limit(source.position() + accepted);
            copy.put(source).flip();
            acceptedAudioBytes += accepted;
            if (acceptedAudioBytes >= properties.maxAudioBytes()) {
                // Python 在收到精确五分钟字节数时负责发出 limit_reached 并立即执行最终推理。
                stage = Stage.FINALIZING;
                scheduleFinalTimeout();
            }
            upstream.sendAudio(copy).whenComplete((ignored, error) -> {
                if (error != null) {
                    synchronized (this) {
                        handleAsyncFailure(error);
                    }
                }
            });
        }

        @Override
        public synchronized void onText(String message) {
            if (stage == Stage.CLOSED) {
                return;
            }
            if (message == null || message.length() > MAX_UPSTREAM_EVENT_CHARACTERS) {
                fail(
                        VoiceErrorCode.VOICE_PROTOCOL_INVALID,
                        "语音识别服务返回了无效事件。",
                        false,
                        CloseStatus.SERVER_ERROR);
                return;
            }
            JsonNode event;
            try {
                event = parseControl(message);
            } catch (VoiceException exception) {
                fail(
                        exception.code(),
                        exception.getMessage(),
                        exception.retryable(),
                        CloseStatus.SERVER_ERROR);
                return;
            }
            String type = event.path("type").asText("");
            if (isAdmissionEvent(type)
                    && (stage == Stage.CONNECTING || stage == Stage.QUEUED)
                    && upstream == null) {
                bufferAdmissionEvent(message);
                return;
            }
            if ("session.queued".equals(type)
                    && (stage == Stage.CONNECTING || stage == Stage.QUEUED)
                    && acceptQueuedEvent(event)) {
                stage = Stage.QUEUED;
                sendClient(message);
                return;
            }
            if ("session.ready".equals(type)
                    && (stage == Stage.CONNECTING || stage == Stage.QUEUED)) {
                stage = Stage.READY;
                sendClient(message);
                return;
            }
            if ("transcript.partial".equals(type)
                    && (stage == Stage.READY || stage == Stage.FINALIZING)
                    && acceptTranscript(event)) {
                sendClient(message);
                return;
            }
            if ("input.limit_reached".equals(type)
                    && (stage == Stage.READY || stage == Stage.FINALIZING)) {
                stage = Stage.FINALIZING;
                sendClient(message);
                return;
            }
            if ("transcript.final".equals(type)
                    && (stage == Stage.READY || stage == Stage.FINALIZING)
                    && acceptTranscript(event)) {
                stage = Stage.CLOSED;
                sendClient(message);
                closeClient(CloseStatus.NORMAL.withReason("TRANSCRIPT_FINAL"));
                closeUpstream(1000, "TRANSCRIPT_FINAL");
                return;
            }
            if ("error".equals(type)) {
                String upstreamCode = event.path("code").asText("");
                if ("VOICE_BUSY".equals(upstreamCode)) {
                    fail(
                            VoiceErrorCode.VOICE_BUSY,
                            "本机语音识别正在处理其他会话，请稍后再试。",
                            true,
                            new CloseStatus(1013, "VOICE_BUSY"));
                } else if ("VOICE_QUEUE_FULL".equals(upstreamCode)
                        || "VOICE_QUEUE_TIMEOUT".equals(upstreamCode)) {
                    VoiceErrorCode queueCode = "VOICE_QUEUE_FULL".equals(upstreamCode)
                            ? VoiceErrorCode.VOICE_QUEUE_FULL
                            : VoiceErrorCode.VOICE_QUEUE_TIMEOUT;
                    fail(
                            queueCode,
                            queueCode == VoiceErrorCode.VOICE_QUEUE_FULL
                                    ? "本机语音识别等待队列已满，请稍后再试。"
                                    : "等待本机语音识别超时，请重新尝试。",
                            true,
                            new CloseStatus(1013, queueCode.name()));
                } else if ("VOICE_FRAME_TOO_LARGE".equals(upstreamCode)) {
                    fail(
                            VoiceErrorCode.VOICE_FRAME_TOO_LARGE,
                            "语音帧超过服务允许大小。",
                            false,
                            CloseStatus.TOO_BIG_TO_PROCESS);
                } else if ("VOICE_AUDIO_FORMAT_INVALID".equals(upstreamCode)
                        || "VOICE_PROTOCOL_INVALID".equals(upstreamCode)) {
                    fail(
                            "VOICE_AUDIO_FORMAT_INVALID".equals(upstreamCode)
                                    ? VoiceErrorCode.VOICE_AUDIO_FORMAT_INVALID
                                    : VoiceErrorCode.VOICE_PROTOCOL_INVALID,
                            "语音连接数据不符合协议。",
                            false,
                            CloseStatus.POLICY_VIOLATION);
                } else {
                    fail(
                            VoiceErrorCode.VOICE_TRANSCRIPTION_FAILED,
                            "本机语音识别失败，请重试。",
                            true,
                            CloseStatus.SERVER_ERROR);
                }
                return;
            }
            fail(
                    VoiceErrorCode.VOICE_PROTOCOL_INVALID,
                    "语音识别服务返回了不符合协议的事件。",
                    false,
                    CloseStatus.SERVER_ERROR);
        }

        @Override
        public synchronized void onClosed(int statusCode, String reason) {
            if (stage == Stage.CLOSED) {
                return;
            }
            if (statusCode == 1013) {
                VoiceErrorCode capacityCode = switch (Objects.toString(reason, "")) {
                    case "VOICE_QUEUE_FULL" -> VoiceErrorCode.VOICE_QUEUE_FULL;
                    case "VOICE_QUEUE_TIMEOUT" -> VoiceErrorCode.VOICE_QUEUE_TIMEOUT;
                    default -> VoiceErrorCode.VOICE_BUSY;
                };
                fail(
                        capacityCode,
                        externalMessage(capacityCode),
                        true,
                        new CloseStatus(1013, capacityCode.name()));
            } else {
                fail(
                        VoiceErrorCode.VOICE_UPSTREAM_UNAVAILABLE,
                        "本机语音识别连接已经中断。",
                        true,
                        CloseStatus.SERVER_ERROR);
            }
        }

        @Override
        public synchronized void onError(Throwable cause) {
            handleAsyncFailure(cause);
        }

        private void handleAsyncFailure(Throwable error) {
            Throwable cause = unwrap(error);
            if (cause instanceof VoiceException voiceException) {
                fail(
                        voiceException.code(),
                        externalMessage(voiceException.code()),
                        voiceException.retryable(),
                        voiceException.code() == VoiceErrorCode.VOICE_BACKPRESSURE
                                ? CloseStatus.SERVER_ERROR
                                : CloseStatus.POLICY_VIOLATION);
                return;
            }
            fail(
                    VoiceErrorCode.VOICE_UPSTREAM_UNAVAILABLE,
                    "暂时无法连接本机语音识别服务。",
                    true,
                    CloseStatus.SERVER_ERROR);
        }

        private void sendUpstreamText(String message) {
            if (upstream == null) {
                fail(
                        VoiceErrorCode.VOICE_UPSTREAM_UNAVAILABLE,
                        "本机语音识别连接尚未建立。",
                        true,
                        CloseStatus.SERVER_ERROR);
                return;
            }
            upstream.sendText(message).whenComplete((ignored, error) -> {
                if (error != null) {
                    synchronized (this) {
                        handleAsyncFailure(error);
                    }
                }
            });
        }

        private JsonNode parseControl(String payload) {
            try {
                JsonNode node = objectMapper.readTree(payload);
                if (node == null || !node.isObject()) {
                    throw new VoiceException(
                            VoiceErrorCode.VOICE_PROTOCOL_INVALID,
                            "语音控制消息必须是 JSON 对象。",
                            false);
                }
                return node;
            } catch (JsonProcessingException exception) {
                throw new VoiceException(
                        VoiceErrorCode.VOICE_PROTOCOL_INVALID,
                        "语音控制消息不是有效 JSON。",
                        false);
            }
        }

        private synchronized void closeFromClient(int statusCode) {
            if (stage == Stage.CLOSED) {
                return;
            }
            stage = Stage.CLOSED;
            closeUpstream(statusCode, "CLIENT_CLOSED");
        }

        private boolean acceptTranscript(JsonNode event) {
            JsonNode text = event.get("text");
            JsonNode sequence = event.get("sequence");
            boolean valid = text != null && text.isTextual()
                    && text.textValue().length() <= MAX_TRANSCRIPT_CHARACTERS
                    && sequence != null && sequence.isIntegralNumber()
                    && sequence.canConvertToInt()
                    && sequence.asInt() > lastTranscriptSequence;
            if (valid) {
                lastTranscriptSequence = sequence.asInt();
            }
            return valid;
        }

        private boolean acceptQueuedEvent(JsonNode event) {
            JsonNode positionNode = event.get("position");
            JsonNode capacityNode = event.get("queueCapacity");
            JsonNode maxWaitNode = event.get("maxWaitMs");
            String sessionId = event.path("sessionId").asText("");
            if (event.size() != 5
                    || positionNode == null || !positionNode.isIntegralNumber()
                    || capacityNode == null || !capacityNode.isIntegralNumber()
                    || maxWaitNode == null || !maxWaitNode.isIntegralNumber()) {
                return false;
            }
            int position = positionNode.asInt(-1);
            int capacity = capacityNode.asInt(-1);
            long maxWaitMs = maxWaitNode.asLong(-1);
            return sessionId.matches("[0-9a-f]{32}")
                    && capacity == properties.waitingQueueCapacity()
                    && position >= 1 && position <= capacity
                    && maxWaitMs == properties.queueWaitTimeout().toMillis();
        }

        private static boolean isAdmissionEvent(String type) {
            return "session.queued".equals(type) || "session.ready".equals(type);
        }

        private void bufferAdmissionEvent(String message) {
            if (pendingAdmissionEvents.size() >= MAX_PENDING_ADMISSION_EVENTS) {
                fail(
                        VoiceErrorCode.VOICE_PROTOCOL_INVALID,
                        "语音识别服务返回了过多待处理准入事件。",
                        false,
                        CloseStatus.SERVER_ERROR);
                return;
            }
            pendingAdmissionEvents.addLast(message);
        }

        private void stopNormally() {
            stage = Stage.CLOSED;
            VoiceTranscriptionSession current = upstream;
            upstream = null;
            if (current != null) {
                // stop 必须排在已发送音频之后，再关闭上游，确保 Python 可以丢弃会话缓冲且不会遗留活动名额。
                current.sendText("{\"type\":\"session.stop\"}")
                        .whenComplete((ignored, error) ->
                                current.close(1000, "SESSION_STOPPED"));
            }
            closeClient(CloseStatus.NORMAL.withReason("SESSION_STOPPED"));
        }

        private void scheduleFinalTimeout() {
            CompletableFuture.runAsync(
                    () -> {
                        synchronized (this) {
                            if (stage == Stage.FINALIZING) {
                                fail(
                                        VoiceErrorCode.VOICE_TRANSCRIPTION_FAILED,
                                        "语音最终识别等待超时，请重新录音。",
                                        true,
                                        CloseStatus.SERVER_ERROR);
                            }
                        }
                    },
                    CompletableFuture.delayedExecutor(
                            properties.finalTimeout().toMillis(),
                            TimeUnit.MILLISECONDS));
        }

        private void sendClient(String payload) {
            if (!client.isOpen()) {
                return;
            }
            try {
                client.sendMessage(new TextMessage(payload));
            } catch (IOException | RuntimeException exception) {
                stage = Stage.CLOSED;
                closeUpstream(1011, "CLIENT_SEND_FAILED");
            }
        }

        private synchronized void fail(
                VoiceErrorCode code,
                String message,
                boolean retryable,
                CloseStatus closeStatus) {
            if (stage == Stage.CLOSED) {
                return;
            }
            sendClient(errorJson(code, message, retryable));
            stage = Stage.CLOSED;
            closeClient(closeStatus);
            closeUpstream(closeStatus.getCode(), code.name());
        }

        private String errorJson(
                VoiceErrorCode code,
                String message,
                boolean retryable) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "error");
            event.put("code", code.name());
            event.put("message", message);
            event.put("retryable", retryable);
            try {
                return objectMapper.writeValueAsString(event);
            } catch (JsonProcessingException exception) {
                return "{\"type\":\"error\",\"code\":\"VOICE_TRANSCRIPTION_FAILED\","
                        + "\"message\":\"语音识别失败。\",\"retryable\":true}";
            }
        }

        private void closeClient(CloseStatus status) {
            if (!client.isOpen()) {
                return;
            }
            try {
                client.close(status);
            } catch (IOException ignored) {
                // 连接已经进入终态，关闭异常不得覆盖原始协议或上游失败原因。
            }
        }

        private synchronized void closeUpstream(int statusCode, String reason) {
            VoiceTranscriptionSession current = upstream;
            upstream = null;
            if (current != null) {
                current.close(statusCode, safeReason(reason));
            }
        }

        private static String safeReason(String reason) {
            String value = Objects.toString(reason, "VOICE_CLOSED");
            return value.length() <= 96 ? value : value.substring(0, 96);
        }

        private static Throwable unwrap(Throwable error) {
            Throwable current = error;
            while ((current instanceof CompletionException
                    || current instanceof java.util.concurrent.ExecutionException)
                    && current.getCause() != null) {
                current = current.getCause();
            }
            return current;
        }

        private static String externalMessage(VoiceErrorCode code) {
            return switch (code) {
                case VOICE_BACKPRESSURE -> "语音数据发送速度过快，请重新录音。";
                case VOICE_BUSY -> "本机语音识别正在处理其他会话，请稍后再试。";
                case VOICE_QUEUE_FULL -> "本机语音识别等待队列已满，请稍后再试。";
                case VOICE_QUEUE_TIMEOUT -> "等待本机语音识别超时，请重新尝试。";
                case VOICE_TICKET_INVALID -> "语音连接票据无效或已经过期。";
                default -> "语音识别服务暂时不可用。";
            };
        }
    }
}
