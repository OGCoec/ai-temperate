package com.example.temperate.service.user.aiconversation.diagnostic.impl;

import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassification;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassifier;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.exception.AiConversationStreamFailureReason;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;

/**
 * 通过有界异常链、HTTP 状态和异常类型完成流失败分类，并生成不包含异常消息的安全诊断指纹。
 */
@Service
public final class AiConversationStreamFailureClassifierImpl
        implements AiConversationStreamFailureClassifier {

    private static final int MAX_CAUSE_DEPTH = 16;

    @Override
    public AiConversationStreamFailureClassification classify(
            Throwable failure) {
        Objects.requireNonNull(failure);
        List<Throwable> chain = causeChain(failure);
        int status = statusCode(chain);
        AiConversationStreamFailureReason reason = explicitReason(chain);
        if (reason == null) {
            reason = classifyReason(chain, status);
        }
        Throwable root = chain.get(chain.size() - 1);
        return new AiConversationStreamFailureClassification(
                reason,
                status,
                failure.getClass().getName(),
                root.getClass().getName(),
                topApplicationFrame(chain),
                stackFingerprint(chain, status));
    }

    private static AiConversationStreamFailureReason explicitReason(
            List<Throwable> chain) {
        for (Throwable current : chain) {
            if (current instanceof AiConversationException controlled
                    && controlled.reason() != null
                    && controlled.reason()
                            != AiConversationStreamFailureReason.UNKNOWN_STREAM_FAILURE) {
                return controlled.reason();
            }
        }
        return null;
    }

    private static AiConversationStreamFailureReason classifyReason(
            List<Throwable> chain, int status) {
        if (hasBackpressureOverflow(chain)) {
            return AiConversationStreamFailureReason.STREAM_BACKPRESSURE_OVERFLOW;
        }
        if (hasTimeout(chain)) {
            return AiConversationStreamFailureReason.UPSTREAM_TOTAL_TIMEOUT;
        }
        if (status == 401 || status == 403) {
            return AiConversationStreamFailureReason.UPSTREAM_AUTH_UNAVAILABLE;
        }
        if (status == 408 || status == 504) {
            return AiConversationStreamFailureReason.UPSTREAM_TOTAL_TIMEOUT;
        }
        if (status == 429) {
            return AiConversationStreamFailureReason.UPSTREAM_RATE_LIMITED;
        }
        if (hasConnectionClosed(chain)) {
            return AiConversationStreamFailureReason.UPSTREAM_CONNECTION_CLOSED;
        }
        if (hasProtocolFailure(chain)) {
            return AiConversationStreamFailureReason.UPSTREAM_PROTOCOL_ERROR;
        }
        if (hasIoFailure(chain)) {
            return AiConversationStreamFailureReason.UPSTREAM_NETWORK_ERROR;
        }
        if (status >= 500 && status <= 599) {
            return AiConversationStreamFailureReason.UPSTREAM_SERVER_ERROR;
        }
        for (Throwable current : chain) {
            if (current instanceof AiConversationException controlled) {
                if (controlled.code() == AiConversationErrorCode.AI_USAGE_UNAVAILABLE) {
                    return AiConversationStreamFailureReason.USAGE_DATA_UNAVAILABLE;
                }
                if (controlled.code() == AiConversationErrorCode.AI_UPSTREAM_TIMEOUT) {
                    return AiConversationStreamFailureReason.UPSTREAM_TOTAL_TIMEOUT;
                }
                if (controlled.code() == AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE) {
                    return AiConversationStreamFailureReason.UPSTREAM_SERVER_ERROR;
                }
            }
        }
        return AiConversationStreamFailureReason.UNKNOWN_STREAM_FAILURE;
    }

    private static boolean hasBackpressureOverflow(List<Throwable> chain) {
        // Reactor 的类型判定不依赖异常英文文案，可稳定区分本地需求溢出与真实上游失败。
        return chain.stream().anyMatch(Exceptions::isOverflow);
    }

    private static boolean hasTimeout(List<Throwable> chain) {
        return chain.stream().anyMatch(current ->
                current instanceof TimeoutException
                        || current.getClass().getSimpleName()
                                .endsWith("TimeoutException"));
    }

    private static boolean hasConnectionClosed(List<Throwable> chain) {
        return chain.stream().anyMatch(current -> {
            String simpleName = current.getClass().getSimpleName();
            return current instanceof ClosedChannelException
                    || current instanceof EOFException
                    || simpleName.contains("PrematureClose")
                    || simpleName.contains("ConnectionReset")
                    || simpleName.contains("ConnectionClosed")
                    || simpleName.equals("AbortedException");
        });
    }

    private static boolean hasProtocolFailure(List<Throwable> chain) {
        return chain.stream().anyMatch(current -> {
            String name = current.getClass().getName();
            String simpleName = current.getClass().getSimpleName();
            return name.startsWith("com.fasterxml.jackson.core.")
                    || name.startsWith("com.fasterxml.jackson.databind.")
                    || simpleName.contains("DecodingException")
                    || simpleName.contains("CodecException")
                    || simpleName.contains("JsonParseException")
                    || simpleName.contains("JsonMappingException");
        });
    }

    private static boolean hasIoFailure(List<Throwable> chain) {
        return chain.stream().anyMatch(IOException.class::isInstance);
    }

    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(
                new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null
                && chain.size() < MAX_CAUSE_DEPTH
                && visited.add(current)) {
            chain.add(current);
            current = current.getCause();
        }
        return List.copyOf(chain);
    }

    private static int statusCode(List<Throwable> chain) {
        for (Throwable current : chain) {
            Object status = invoke(current, "getStatusCode");
            long value = number(invoke(status, "value"));
            if (value == 0L) {
                value = number(invoke(current, "getRawStatusCode"));
            }
            if (value >= 100L && value <= 599L) {
                return (int) value;
            }
        }
        return 0;
    }

    private static Object invoke(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String topApplicationFrame(List<Throwable> chain) {
        StackTraceElement fallback = null;
        for (Throwable current : chain) {
            for (StackTraceElement frame : current.getStackTrace()) {
                if (fallback == null) {
                    fallback = frame;
                }
                if (frame.getClassName().startsWith("com.example.temperate.")) {
                    return safeFrame(frame);
                }
            }
        }
        return fallback == null ? "unavailable" : safeFrame(fallback);
    }

    private static String safeFrame(StackTraceElement frame) {
        return frame.getClassName()
                + "#" + frame.getMethodName()
                + ":" + Math.max(frame.getLineNumber(), 0);
    }

    private static String stackFingerprint(
            List<Throwable> chain, int status) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Integer.toString(status)
                    .getBytes(StandardCharsets.UTF_8));
            for (Throwable current : chain) {
                digest.update((byte) 0);
                digest.update(current.getClass().getName()
                        .getBytes(StandardCharsets.UTF_8));
                StackTraceElement[] frames = current.getStackTrace();
                for (int index = 0; index < Math.min(frames.length, 12); index++) {
                    digest.update((byte) 0);
                    digest.update(safeFrame(frames[index])
                            .getBytes(StandardCharsets.UTF_8));
                }
            }
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
