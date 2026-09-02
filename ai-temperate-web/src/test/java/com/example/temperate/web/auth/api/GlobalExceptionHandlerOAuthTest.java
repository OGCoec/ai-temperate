package com.example.temperate.web.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import com.example.temperate.web.auth.oauth.provider.OAuthProviderErrorCode;
import com.example.temperate.web.auth.oauth.provider.OAuthProviderException;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.PreAuthTransport;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowErrorCode;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowException;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 验证 OAuth 身份拒绝与第三方基础设施失败不会被归类为相同的 HTTP 结果。
 */
final class GlobalExceptionHandlerOAuthTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(
            Clock.systemUTC(),
            mock(AuthCookieWriter.class),
            mock(AuthFlowCookieWriter.class),
            mock(PreAuthTransport.class));

    @Test
    void unverifiedNativeIdentityIsForbiddenRatherThanProviderUnavailable() {
        var response = handler.handleOAuthProvider(new OAuthProviderException(
                OAuthProviderErrorCode.IDENTITY_UNVERIFIED,
                "sensitive provider detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("IDENTITY_UNVERIFIED");
        assertThat(response.getBody().message()).doesNotContain("sensitive provider detail");
    }

    @Test
    void tokenExchangeFailureRemainsBadGateway() {
        var response = handler.handleOAuthProvider(new OAuthProviderException(
                OAuthProviderErrorCode.TOKEN_EXCHANGE_FAILED,
                "provider unavailable"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void missingGithubSubjectOrVerifiedEmailRemainForbidden() {
        var subjectResponse = handler.handleOAuthProvider(new OAuthProviderException(
                OAuthProviderErrorCode.PROVIDER_SUBJECT_MISSING,
                "provider payload must not escape"));
        var emailResponse = handler.handleOAuthProvider(new OAuthProviderException(
                OAuthProviderErrorCode.VERIFIED_EMAIL_MISSING,
                "member@example.com"));

        assertThat(subjectResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(emailResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(subjectResponse.getBody()).isNotNull();
        assertThat(emailResponse.getBody()).isNotNull();
        assertThat(subjectResponse.getBody().message())
                .doesNotContain("provider payload must not escape");
        assertThat(emailResponse.getBody().message()).doesNotContain("member@example.com");
    }

    @Test
    void concurrentOAuthCompletionUsesStableConflictCodes() {
        var inProgress = handler.handleOAuthFlow(new OAuthFlowException(
                OAuthFlowErrorCode.COMPLETION_IN_PROGRESS,
                "internal claim detail"));
        var completed = handler.handleOAuthFlow(new OAuthFlowException(
                OAuthFlowErrorCode.ALREADY_COMPLETED,
                "internal state detail"));

        assertThat(inProgress.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(inProgress.getBody()).isNotNull();
        assertThat(completed.getBody()).isNotNull();
        assertThat(inProgress.getBody().message()).doesNotContain("internal");
        assertThat(completed.getBody().message()).doesNotContain("internal");
    }
}
