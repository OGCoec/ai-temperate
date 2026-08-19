package com.example.temperate.web.aiinference;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * 该分类器是来依据异常类型、响应提交状态和响应媒体类型识别模型流的客户端断开，供公开 API 与会话 SSE 共用且不读取本地化异常消息。
 */
public final class ApiInferenceClientDisconnectClassifier {

    private static final int MAX_CAUSE_DEPTH = 16;
    private static final String DIAGNOSTIC_ATTRIBUTE =
            ApiInferenceClientDisconnectClassifier.class.getName() + ".diagnosticLogged";

    private ApiInferenceClientDisconnectClassifier() {
    }

    /**
     * 分类写入失败；只有已提交的 SSE 才能被视为正常客户端断开，未提交响应仍须生成受控 JSON。
     */
    public static Result classify(
            Throwable failure,
            HttpServletResponse response) {
        if (!containsIoFailure(failure)) {
            return Result.NOT_IO_FAILURE;
        }
        if (response == null || !response.isCommitted()) {
            return Result.UNCOMMITTED_IO_FAILURE;
        }
        return isEventStream(response)
                ? Result.COMMITTED_SSE_CLIENT_DISCONNECT
                : Result.COMMITTED_RESPONSE_IO_FAILURE;
    }

    /**
     * 在同一个 Servlet 请求的过滤器、异步监听器和异常处理器之间执行一次性日志领取，避免同一断开重复制造诊断噪声。
     */
    public static boolean claimDiagnostic(HttpServletRequest request) {
        if (request == null) {
            return true;
        }
        AtomicBoolean claimed;
        // AsyncListener 与错误分派可能并发首次访问属性；锁住请求对象可避免各自创建 CAS 后都成功记录。
        synchronized (request) {
            Object existing = request.getAttribute(DIAGNOSTIC_ATTRIBUTE);
            if (existing instanceof AtomicBoolean atomicBoolean) {
                claimed = atomicBoolean;
            } else {
                claimed = new AtomicBoolean();
                request.setAttribute(DIAGNOSTIC_ATTRIBUTE, claimed);
            }
        }
        return claimed.compareAndSet(false, true);
    }

    private static boolean containsIoFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof IOException
                    || current instanceof AsyncRequestNotUsableException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isEventStream(HttpServletResponse response) {
        String contentType = response.getContentType();
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        return MediaType.TEXT_EVENT_STREAM_VALUE.equals(
                contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 该枚举是来稳定表达写失败是否允许结束而不再改写响应，调用方不得把普通业务异常归入客户端断开。
     */
    public enum Result {
        COMMITTED_SSE_CLIENT_DISCONNECT,
        COMMITTED_RESPONSE_IO_FAILURE,
        UNCOMMITTED_IO_FAILURE,
        NOT_IO_FAILURE
    }
}
