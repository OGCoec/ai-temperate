package com.example.temperate.service.admin.aimodel.icon.remote.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageValidationContext;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageValidator;
import com.example.temperate.service.admin.aimodel.icon.remote.AiModelIconTrustedOriginRegistry;
import com.example.temperate.service.admin.aimodel.icon.remote.config.AiModelIconVendor;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;
import javax.net.ssl.SSLHandshakeException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.junit.jupiter.api.Test;

/**
 * 验证外部图标每跳都满足 HTTPS 安全边界，且只有最终响应主机能够选择 SVG 档位。
 */
final class AiModelIconRemoteImageValidatorImplTest {

    @Test
    void acceptsAbsoluteAndRelativeHttpsRedirects() {
        URI current = AiModelIconRemoteImageValidatorImpl.requireHttpsUri(
                "https://images.example.test/start");

        assertThat(AiModelIconRemoteImageValidatorImpl.resolveRedirect(
                current,
                "/icons/openai.png"))
                .isEqualTo(URI.create("https://images.example.test/icons/openai.png"));
        assertThat(AiModelIconRemoteImageValidatorImpl.resolveRedirect(
                current,
                "https://cdn.example.test/openai.webp"))
                .isEqualTo(URI.create("https://cdn.example.test/openai.webp"));
    }

    @Test
    void rejectsUnsafeInitialAndRedirectUrls() {
        for (String value : new String[] {
                "http://images.example.test/openai.png",
                "https://user:password@images.example.test/openai.png",
                "https://images.example.test/openai.png#fragment",
                "https://images.example.test/" + "a".repeat(1100)
        }) {
            assertInvalid(() -> AiModelIconRemoteImageValidatorImpl.requireHttpsUri(value));
        }

        URI current = URI.create("https://images.example.test/start");
        assertThatThrownBy(() -> AiModelIconRemoteImageValidatorImpl.resolveRedirect(
                current,
                "http://169.254.169.254/latest/meta-data"))
                .isInstanceOfSatisfying(AiModelIconException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_REDIRECT_INVALID));
    }

    @Test
    void preservesSpecificFormatSafetyAndDecoderErrors() {
        for (AiModelIconErrorCode code : new AiModelIconErrorCode[] {
                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_FORMAT_UNSUPPORTED,
                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE,
                AiModelIconErrorCode.AI_MODEL_ICON_DECODER_UNAVAILABLE
        }) {
            AiModelIconException original = new AiModelIconException(code, "internal");

            assertThat(AiModelIconRemoteImageValidatorImpl
                    .mapImageValidationFailure(original))
                    .isSameAs(original);
        }
    }

    @Test
    void mapsCorruptRemoteImageToInvalidRemoteResponse() {
        AiModelIconException mapped =
                AiModelIconRemoteImageValidatorImpl.mapImageValidationFailure(
                        new AiModelIconException(
                                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_INVALID,
                                "internal"));

        assertThat(mapped.code())
                .isEqualTo(AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_RESPONSE_INVALID);
    }

    @Test
    void classifiesDnsAndRejectedNonPublicAddressesSeparately() {
        UnknownHostException dnsFailure = new UnknownHostException("name not found");
        assertTransportCode(
                dnsFailure,
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_DNS_RESOLUTION_FAILED);

        UnknownHostException rejectedHost =
                new UnknownHostException("host is not allowed for remote image validation");
        rejectedHost.initCause(new IllegalArgumentException("private address"));
        assertTransportCode(
                rejectedHost,
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_HOST_NOT_PUBLIC);
    }

    @Test
    void classifiesConnectReadTimeoutTlsAndConnectFailures() {
        assertTransportCode(
                new ConnectTimeoutException("connect timed out"),
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_CONNECT_TIMEOUT);
        assertTransportCode(
                new SocketTimeoutException("read timed out"),
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_READ_TIMEOUT);

        SSLHandshakeException tlsFailure =
                new SSLHandshakeException("Remote host terminated the handshake");
        tlsFailure.initCause(new EOFException("SSL peer shut down incorrectly"));
        assertTransportCode(
                tlsFailure,
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_TLS_HANDSHAKE_FAILED);

        assertTransportCode(
                new ConnectException("Connection refused"),
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_CONNECT_FAILED);
    }

    @Test
    void remoteAcceptHeaderAdvertisesAllAllowedMimeTypes() {
        assertThat(Set.of(
                AiModelIconRemoteImageValidatorImpl.ACCEPTED_IMAGE_TYPES.split(",")))
                .contains(
                        "image/png",
                        "image/jpeg",
                        "image/jpg",
                        "image/webp",
                        "image/gif",
                        "image/x-icon",
                        "image/vnd.microsoft.icon",
                        "image/avif",
                        "image/svg+xml");
    }

    @Test
    void forwardsFinalResponseHostContextWithoutNetworkAccess() {
        AiModelIconImageValidator imageValidator =
                mock(AiModelIconImageValidator.class);
        AiModelIconTrustedOriginRegistry trustedOriginRegistry =
                mock(AiModelIconTrustedOriginRegistry.class);
        AiModelIconImageValidationContext expectedContext =
                AiModelIconImageValidationContext.trustedOfficial(
                        AiModelIconVendor.OPENAI);
        when(trustedOriginRegistry.resolve("cdn.chatgpt.com"))
                .thenReturn(expectedContext);
        AiModelIconRemoteImageValidatorImpl validator =
                new AiModelIconRemoteImageValidatorImpl(
                        imageValidator,
                        trustedOriginRegistry);
        byte[] bytes = {1, 2, 3};

        try {
            validator.validateFetchedImage(
                    bytes,
                    "image/svg+xml",
                    URI.create("https://cdn.chatgpt.com/final.svg"));
        } finally {
            validator.close();
        }

        verify(imageValidator).validate(
                bytes,
                "image/svg+xml",
                expectedContext);
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(AiModelIconException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_URL_INVALID));
    }

    private static void assertTransportCode(
            Throwable failure,
            AiModelIconErrorCode expectedCode) {
        assertThat(AiModelIconRemoteImageValidatorImpl
                .mapTransportFailure(failure)
                .code())
                .isEqualTo(expectedCode);
    }
}
