package com.example.temperate.web.auth.oauth.diagnostic;

import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.flow.OAuthClientPlatform;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowException;
import com.example.temperate.web.auth.oauth.provider.OAuthProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 记录 OAuth Provider 回调失败的稳定安全分类，供排查换码、身份校验和状态绑定问题。
 *
 * <p>该组件只记录枚举、异常类型和请求 Trace ID，不记录异常消息、堆栈、Provider Code、state、Token、
 * Cookie、邮箱或 Subject，避免诊断日志反向扩大短期凭据和个人信息的暴露面。</p>
 */
@Component
public final class OAuthCallbackFailureLogger {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OAuthCallbackFailureLogger.class);

    /**
     * 记录抛出异常的回调失败，并根据已经完成的边界校验归一化失败类别。
     */
    public void logFailure(
            OAuthProvider provider,
            OAuthClientPlatform platform,
            RuntimeException failure,
            boolean authorizationStateResolved,
            boolean statePresent,
            boolean handshakePresent) {
        log(
                provider,
                platform,
                category(failure, statePresent, handshakePresent),
                failure.getClass().getName(),
                authorizationStateResolved);
    }

    /**
     * 记录用户或 Provider 明确拒绝授权；Provider 返回的原始 error 参数不得进入日志。
     */
    public void logAuthorizationRejected(
            OAuthProvider provider, OAuthClientPlatform platform) {
        log(provider, platform, "AUTHORIZATION_REJECTED", "absent", true);
    }

    private void log(
            OAuthProvider provider,
            OAuthClientPlatform platform,
            String failureCategory,
            String exceptionClass,
            boolean authorizationStateResolved) {
        LOGGER.warn(
                "event=oauth_callback_failed traceId={} provider={} platform={} "
                        + "failureCategory={} exceptionClass={} authorizationStateResolved={}",
                traceId(),
                provider.name(),
                platform.name(),
                failureCategory,
                exceptionClass,
                authorizationStateResolved);
    }

    private static String category(
            RuntimeException failure,
            boolean statePresent,
            boolean handshakePresent) {
        if (!statePresent) {
            return "STATE_INVALID";
        }
        if (!handshakePresent) {
            return "HANDSHAKE_INVALID";
        }
        if (failure instanceof OAuthProviderException providerFailure) {
            return switch (providerFailure.code()) {
                case AUTHORIZATION_REJECTED -> "AUTHORIZATION_REJECTED";
                case TOKEN_EXCHANGE_FAILED -> "TOKEN_EXCHANGE_FAILED";
                case PROVIDER_UNAVAILABLE -> "PROVIDER_API_FAILED";
                case PROVIDER_SUBJECT_MISSING -> "PROVIDER_SUBJECT_MISSING";
                case VERIFIED_EMAIL_MISSING -> "VERIFIED_EMAIL_MISSING";
                case IDENTITY_UNVERIFIED -> "IDENTITY_UNVERIFIED";
            };
        }
        if (failure instanceof OAuthFlowException flowFailure) {
            return switch (flowFailure.code()) {
                case FLOW_NOT_FOUND, FLOW_EXPIRED -> "FLOW_EXPIRED";
                case FLOW_FORBIDDEN -> "HANDSHAKE_INVALID";
				case STATE_REJECTED, NONCE_REJECTED, INVALID_TRANSITION,
					COMPLETION_IN_PROGRESS, ALREADY_COMPLETED -> "STATE_INVALID";
                case INFRASTRUCTURE_UNAVAILABLE -> "UNEXPECTED_CALLBACK_FAILURE";
            };
        }
        return "UNEXPECTED_CALLBACK_FAILURE";
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || !value.matches("^[A-Za-z0-9_-]{1,128}$")
                ? "absent" : value;
    }
}
