package com.example.temperate.service.registration.exception;

import com.example.temperate.service.registration.enums.RegistrationDiagnosticCode;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import java.util.Objects;
import java.util.Optional;

/**
 * 表示注册业务流程可安全映射为客户端响应的异常。
 *
 * <p>异常携带稳定业务错误码、受控消息和可选的服务端诊断原因。诊断原因只能写入脱敏日志，调用方不得把
 * Redis、数据库、外部供应商或安全绑定细节暴露给客户端。</p>
 */
public final class RegistrationException extends RuntimeException {

    private final RegistrationErrorCode code;
    private final RegistrationDiagnosticCode diagnosticCode;

    public RegistrationException(RegistrationErrorCode code, String message) {
        this(code, message, null, null);
    }

    public RegistrationException(
            RegistrationErrorCode code, String message, Throwable cause) {
        this(code, message, null, cause);
    }

    public RegistrationException(
            RegistrationErrorCode code,
            String message,
            RegistrationDiagnosticCode diagnosticCode) {
        this(code, message, diagnosticCode, null);
    }

    public RegistrationException(
            RegistrationErrorCode code,
            String message,
            RegistrationDiagnosticCode diagnosticCode,
            Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.diagnosticCode = diagnosticCode;
    }

    public RegistrationErrorCode code() {
        return code;
    }

    public Optional<RegistrationDiagnosticCode> diagnosticCode() {
        return Optional.ofNullable(diagnosticCode);
    }
}
