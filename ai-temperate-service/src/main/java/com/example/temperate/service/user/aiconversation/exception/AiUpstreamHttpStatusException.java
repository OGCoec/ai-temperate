package com.example.temperate.service.user.aiconversation.exception;

import com.example.temperate.service.user.aiconversation.diagnostic.AiUpstreamErrorDiagnostic;
import java.util.Objects;
import org.springframework.http.HttpStatusCode;

/**
 * 表示上游以非成功 HTTP 状态拒绝 AI 请求，并仅携带已经完成脱敏的服务端诊断元数据。
 *
 * <p>固定异常消息防止通用异常日志意外展开供应商正文；原始状态码继续供现有失败分类器判断。</p>
 */
public final class AiUpstreamHttpStatusException extends RuntimeException {

    private final HttpStatusCode statusCode;
    private final AiUpstreamErrorDiagnostic diagnostic;

    public AiUpstreamHttpStatusException(
            HttpStatusCode statusCode,
            AiUpstreamErrorDiagnostic diagnostic) {
        super("AI upstream rejected the request");
        this.statusCode = Objects.requireNonNull(statusCode);
        this.diagnostic = Objects.requireNonNull(diagnostic);
    }

    public HttpStatusCode getStatusCode() {
        return statusCode;
    }

    public AiUpstreamErrorDiagnostic diagnostic() {
        return diagnostic;
    }
}
