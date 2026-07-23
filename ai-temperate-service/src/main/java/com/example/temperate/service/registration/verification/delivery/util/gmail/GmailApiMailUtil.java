package com.example.temperate.service.registration.verification.delivery.util.gmail;

import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata;
import com.example.temperate.service.registration.verification.delivery.util.email.VerificationEmailContent;
import com.example.temperate.service.registration.verification.delivery.util.email.VerificationEmailContentFactory;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * 通过 Gmail REST API 发送邮箱验证码。
 *
 * <p>工具类只把 Gmail API 返回非空 Message.id 视为供应商接受请求；它不声明用户已经实际收信，也不在日志或结果中暴露验证码。</p>
 */
public final class GmailApiMailUtil {

    private static final String PROVIDER = "gmail";
    private static final Base64.Encoder BASE64_URL =
            Base64.getUrlEncoder().withoutPadding();

    private final String fromAddress;
    private final String sendUri;
    private final Duration requestTimeout;
    private final GmailAccessTokenSupplier tokenSupplier;
    private final Function<GmailSendRequest, Mono<GmailMessageResponse>> sender;

    public GmailApiMailUtil(
            WebClient webClient,
            GmailApiProperties properties,
            GmailAccessTokenSupplier tokenSupplier) {
        this(
                properties.fromAddress(),
                properties.sendUri(),
                properties.requestTimeout(),
                tokenSupplier,
                request -> webClient.post()
                        .uri(properties.sendUri())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + request.accessToken())
                        .bodyValue(Map.of("raw", request.raw()))
                        .retrieve()
                        .bodyToMono(GmailMessageResponse.class));
    }

    GmailApiMailUtil(
            String fromAddress,
            String sendUri,
            Duration requestTimeout,
            GmailAccessTokenSupplier tokenSupplier,
            Function<GmailSendRequest, Mono<GmailMessageResponse>> sender) {
        this.fromAddress = requireText(fromAddress, "fromAddress");
        this.sendUri = requireText(sendUri, "sendUri");
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.requestTimeout = requestTimeout;
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier must not be null");
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
    }

    public Mono<VerificationDeliveryResult> sendVerificationCode(
            VerificationDeliveryRequest request) {
        // 邮件构造可能同步校验失败，延迟到订阅阶段后才能由统一 AOP 记录同一消息上下文。
        return Mono.defer(() -> sendWithCurrentToken(request, false));
    }

    private Mono<VerificationDeliveryResult> sendWithCurrentToken(
            VerificationDeliveryRequest request, boolean alreadyRefreshed) {
        String raw = rawMessage(request);
        return tokenSupplier.accessToken()
                .flatMap(token -> sender.apply(new GmailSendRequest(token, raw)))
                .timeout(requestTimeout)
                .map(this::success)
                .onErrorResume(WebClientResponseException.Unauthorized.class, failure -> {
                    if (alreadyRefreshed) {
                        return Mono.error(deliveryFailure(failure));
                    }
                    tokenSupplier.invalidate();
                    return sendWithCurrentToken(request, true);
                })
                .onErrorMap(this::deliveryFailure);
    }

    private VerificationDeliveryResult success(GmailMessageResponse response) {
        if (response == null || response.id() == null || response.id().isBlank()) {
            throw new VerificationDeliveryException(
                    true,
                    PROVIDER,
                    "gmail_empty_message_id",
                    new VerificationDeliveryProviderMetadata(
                            null, null, "failed", false, null, null),
                    null);
        }
        return new VerificationDeliveryResult(
                VerificationChannel.EMAIL,
                PROVIDER,
                response.id(),
                Instant.now(),
                new VerificationDeliveryProviderMetadata(
                        null, null, "accepted", true, null, null));
    }

    private String rawMessage(VerificationDeliveryRequest request) {
        validate(request);
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            message.setFrom(new InternetAddress(fromAddress));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(request.destination()));
            VerificationEmailContent content =
                    VerificationEmailContentFactory.create(request.purpose(), request.code());
            message.setSubject(content.subject(), StandardCharsets.UTF_8.name());
            message.setText(content.body(), StandardCharsets.UTF_8.name());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            message.writeTo(output);
            return BASE64_URL.encodeToString(output.toByteArray());
        } catch (MessagingException | IOException exception) {
            throw new VerificationDeliveryException(
                    false,
                    PROVIDER,
                    "gmail_mime_build_failed",
                    failureMetadata(null, exception),
                    exception);
        }
    }

    private static void validate(VerificationDeliveryRequest request) {
        if (request == null
                || request.destination() == null
                || request.destination().isBlank()
                || request.code() == null
                || !request.code().matches("^[0-9]{6}$")) {
            throw new VerificationDeliveryException(
                    false,
                    PROVIDER,
                    "gmail_invalid_request",
                    failureMetadata(null, null),
                    null);
        }
    }

    private Throwable deliveryFailure(Throwable failure) {
        if (failure instanceof VerificationDeliveryException) {
            return failure;
        }
        if (failure instanceof WebClientResponseException responseException) {
            boolean retryable = responseException.getStatusCode().is5xxServerError()
                    || responseException.getStatusCode().value() == 429;
            return new VerificationDeliveryException(
                    retryable,
                    PROVIDER,
                    "gmail_http_error",
                    failureMetadata(responseException.getStatusCode().value(), responseException),
                    responseException);
        }
        return new VerificationDeliveryException(
                true,
                PROVIDER,
                "gmail_request_failed",
                failureMetadata(null, failure),
                failure);
    }

    private static VerificationDeliveryProviderMetadata failureMetadata(
            Integer httpStatus, Throwable failure) {
        return new VerificationDeliveryProviderMetadata(
                httpStatus,
                null,
                "failed",
                false,
                null,
                failure == null ? null : failure.getClass().getSimpleName());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record GmailSendRequest(String accessToken, String raw) {
    }

    public record GmailMessageResponse(
            String id,
            String threadId) {
    }
}
