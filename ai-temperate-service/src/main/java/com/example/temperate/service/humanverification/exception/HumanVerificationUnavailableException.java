package com.example.temperate.service.humanverification.exception;

import com.example.temperate.service.humanverification.HumanVerificationType;
import java.util.Objects;

/**
 * 表示人机验证供应商因网络、TLS、超时或不可信响应而暂时无法给出可靠结论。
 *
 * <p>该异常只携带稳定供应商类型和底层原因供服务端诊断，不表示客户端 Token 已被明确拒绝，也不得把
 * cause、Token、Secret、客户端 IP 或供应商正文暴露给外部响应。</p>
 */
public final class HumanVerificationUnavailableException extends RuntimeException {

    private final HumanVerificationType verificationType;

    public HumanVerificationUnavailableException(
            HumanVerificationType verificationType,
            Throwable cause) {
        super("Human verification provider is unavailable.", cause);
        this.verificationType = Objects.requireNonNull(
                verificationType,
                "verificationType must not be null");
    }

    public HumanVerificationType verificationType() {
        return verificationType;
    }
}
