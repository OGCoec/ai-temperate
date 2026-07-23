package com.example.temperate.service.registration.verification.delivery.util.microsoft;

import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.Endpoint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureCategory;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureHint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureStage;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.Operation;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.RecommendedAction;
import com.example.temperate.service.registration.verification.delivery.util.email.VerificationEmailContent;
import com.example.temperate.service.registration.verification.delivery.util.email.VerificationEmailContentFactory;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 使用 WebClient 通过 Microsoft Graph 的 {@code /me/sendMail} 发送六位数邮箱验证码。
 *
 * <p>整个 OAuth 到邮件请求链保持响应式，不在内部执行同步阻塞；请求固定不包含 {@code from} 或
 * {@code sender}，由访问令牌主体决定发件身份。适配器只保留 HTTP 状态、Graph 错误码、request-id
 * 和数值型 Retry-After，不保存第三方原始错误文本或响应体。</p>
 */
public final class MicrosoftGraphApiMailUtil {

    private static final String PROVIDER = "microsoft_graph";
    private static final int ACCEPTED_STATUS = 202;

    private final Duration sendTimeout;
    private final MicrosoftGraphAccessTokenSupplier tokenSupplier;
    private final MicrosoftGraphMailRequester requester;
    private final Clock clock;

    public MicrosoftGraphApiMailUtil(
            WebClient webClient,
            MicrosoftGraphApiProperties properties,
            MicrosoftGraphAccessTokenSupplier tokenSupplier) {
        this(
                properties.sendTimeout(),
                tokenSupplier,
                request -> requestGraph(webClient, properties.sendUri(), request),
                Clock.systemUTC());
    }

    MicrosoftGraphApiMailUtil(
            Duration sendTimeout,
            MicrosoftGraphAccessTokenSupplier tokenSupplier,
            MicrosoftGraphMailRequester requester,
            Clock clock) {
        if (sendTimeout == null || sendTimeout.isZero() || sendTimeout.isNegative()) {
            throw new IllegalArgumentException("sendTimeout must be positive");
        }
        this.sendTimeout = sendTimeout;
        this.tokenSupplier =
                Objects.requireNonNull(tokenSupplier, "tokenSupplier must not be null");
        this.requester = Objects.requireNonNull(requester, "requester must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public Mono<VerificationDeliveryResult> sendVerificationCode(
            VerificationDeliveryRequest request) {
        // 请求构造和地址校验延迟到订阅阶段，使 AOP 能用同一个 Reactor Context 记录同步校验失败。
        return Mono.defer(() -> {
            MicrosoftGraphSendMailBody requestBody = requestBody(request);
            return sendWithCurrentToken(requestBody, false);
        });
    }

    private Mono<VerificationDeliveryResult> sendWithCurrentToken(
            MicrosoftGraphSendMailBody requestBody,
            boolean alreadyRefreshed) {
        // OAuth 的十秒预算由令牌组件独立控制，sendMail 的十秒预算只包围实际 Graph 邮件请求。
        return tokenSupplier.accessToken()
                .flatMap(accessToken -> sendOnce(
                        new MicrosoftGraphMailRequest(accessToken, requestBody),
                        alreadyRefreshed))
                .onErrorMap(this::preserveClassifiedFailure);
    }

    private Mono<VerificationDeliveryResult> sendOnce(
            MicrosoftGraphMailRequest request,
            boolean alreadyRefreshed) {
        return Mono.defer(() -> requester.send(request))
                .timeout(sendTimeout)
                .switchIfEmpty(Mono.error(emptyResponseFailure(alreadyRefreshed)))
                .onErrorMap(failure -> deliveryFailure(failure, alreadyRefreshed))
                .flatMap(response -> handleResponse(
                        request.requestBody(), response, alreadyRefreshed));
    }

    private Mono<VerificationDeliveryResult> handleResponse(
            MicrosoftGraphSendMailBody requestBody,
            MicrosoftGraphMailResponse response,
            boolean alreadyRefreshed) {
        if (response.httpStatus() == ACCEPTED_STATUS) {
            return Mono.just(success(response, alreadyRefreshed));
        }
        if (response.httpStatus() == 401 && !alreadyRefreshed) {
            // Graph 尚未接受邮件时才能安全失效短期令牌；同一次投递最多刷新并重试一次。
            tokenSupplier.invalidate();
            return sendWithCurrentToken(requestBody, true);
        }
        return Mono.error(deliveryFailure(response, alreadyRefreshed));
    }

    private VerificationDeliveryResult success(
            MicrosoftGraphMailResponse response,
            boolean authRefreshAttempted) {
        return new VerificationDeliveryResult(
                VerificationChannel.EMAIL,
                PROVIDER,
                null,
                clock.instant(),
                new VerificationDeliveryProviderMetadata(
                        response.httpStatus(),
                        null,
                        "accepted",
                        true,
                        response.requestId(),
                        null,
                        Operation.SEND_MAIL,
                        Endpoint.ME_SEND_MAIL,
                        null,
                        null,
                        null,
                        null,
                        false,
                        authRefreshAttempted,
                        null));
    }

    private static MicrosoftGraphSendMailBody requestBody(
            VerificationDeliveryRequest request) {
        validate(request);
        String destination = validatedAddress(request.destination());
        VerificationEmailContent content =
                VerificationEmailContentFactory.create(request.purpose(), request.code());
        MicrosoftGraphMessage message = new MicrosoftGraphMessage(
                content.subject(),
                new MicrosoftGraphItemBody("Text", content.body()),
                List.of(new MicrosoftGraphRecipient(
                        new MicrosoftGraphEmailAddress(destination))));
        return new MicrosoftGraphSendMailBody(message, true);
    }

    private static String validatedAddress(String address) {
        try {
            return new InternetAddress(address, true).getAddress();
        } catch (AddressException exception) {
            throw new VerificationDeliveryException(
                    false,
                    PROVIDER,
                    "microsoft_graph_invalid_email",
                    validationFailureMetadata(exception.getClass().getSimpleName()),
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
                    "microsoft_graph_invalid_request",
                    validationFailureMetadata(null),
                    null);
        }
    }

    private Throwable preserveClassifiedFailure(Throwable failure) {
        return failure instanceof VerificationDeliveryException
                ? failure
                : deliveryFailure(failure, false);
    }

    private Throwable deliveryFailure(
            Throwable failure,
            boolean authRefreshAttempted) {
        if (failure instanceof VerificationDeliveryException) {
            return failure;
        }
        MicrosoftGraphFailureClassifier.Classification classification =
                MicrosoftGraphFailureClassifier.classify(null, null, failure);
        return new VerificationDeliveryException(
                true,
                PROVIDER,
                "microsoft_graph_request_failed",
                graphFailureMetadata(
                        null,
                        null,
                        null,
                        failure == null ? null : failure.getClass().getSimpleName(),
                        authRefreshAttempted,
                        null,
                        classification),
                failure);
    }

    private static VerificationDeliveryException deliveryFailure(
            MicrosoftGraphMailResponse response,
            boolean authRefreshAttempted) {
        MicrosoftGraphFailureClassifier.Classification classification =
                MicrosoftGraphFailureClassifier.classify(
                        response.httpStatus(), response.providerCode(), null);
        boolean retryable = response.httpStatus() == 408
                || response.httpStatus() == 429
                || response.httpStatus() >= 500;
        return new VerificationDeliveryException(
                retryable,
                PROVIDER,
                "microsoft_graph_http_error",
                graphFailureMetadata(
                        response.httpStatus(),
                        response.providerCode(),
                        response.requestId(),
                        "MicrosoftGraphHttpResponse",
                        authRefreshAttempted,
                        response.retryAfterSeconds(),
                        classification),
                null);
    }

    private static VerificationDeliveryException emptyResponseFailure(
            boolean authRefreshAttempted) {
        MicrosoftGraphFailureClassifier.Classification classification =
                MicrosoftGraphFailureClassifier.classify(null, null, null);
        return new VerificationDeliveryException(
                true,
                PROVIDER,
                "microsoft_graph_empty_response",
                graphFailureMetadata(
                        null,
                        null,
                        null,
                        null,
                        authRefreshAttempted,
                        null,
                        classification),
                null);
    }

    private static VerificationDeliveryProviderMetadata graphFailureMetadata(
            Integer httpStatus,
            String providerCode,
            String requestId,
            String exceptionClass,
            boolean authRefreshAttempted,
            Long retryAfterSeconds,
            MicrosoftGraphFailureClassifier.Classification classification) {
        return new VerificationDeliveryProviderMetadata(
                httpStatus,
                providerCode,
                "failed",
                false,
                requestId,
                exceptionClass,
                Operation.SEND_MAIL,
                Endpoint.ME_SEND_MAIL,
                classification.failureStage(),
                classification.failureCategory(),
                classification.failureHint(),
                classification.recommendedAction(),
                false,
                authRefreshAttempted,
                retryAfterSeconds);
    }

    private static VerificationDeliveryProviderMetadata validationFailureMetadata(
            String exceptionClass) {
        return new VerificationDeliveryProviderMetadata(
                null,
                null,
                "failed",
                false,
                null,
                exceptionClass,
                Operation.SEND_MAIL,
                Endpoint.ME_SEND_MAIL,
                FailureStage.REQUEST_VALIDATION,
                FailureCategory.INVALID_REQUEST,
                FailureHint.PROVIDER_REJECTED_REQUEST,
                RecommendedAction.VERIFY_GRAPH_REQUEST_CONFIGURATION,
                false,
                false,
                null);
    }

    private static Mono<MicrosoftGraphMailResponse> requestGraph(
            WebClient webClient,
            String sendUri,
            MicrosoftGraphMailRequest request) {
        return webClient.post()
                .uri(sendUri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + request.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request.requestBody())
                .exchangeToMono(MicrosoftGraphApiMailUtil::safeResponse);
    }

    private static Mono<MicrosoftGraphMailResponse> safeResponse(
            ClientResponse response) {
        int httpStatus = response.statusCode().value();
        HttpHeaders headers = response.headers().asHttpHeaders();
        String requestId = headers.getFirst("request-id");
        Long retryAfterSeconds = numericRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER));
        if (httpStatus == ACCEPTED_STATUS) {
            return response.releaseBody().thenReturn(new MicrosoftGraphMailResponse(
                    httpStatus, null, requestId, retryAfterSeconds));
        }
        // 只反序列化 error.code；其余 message、details、innerError 和 additionalData 字段不会进入对象模型。
        return response.bodyToMono(MicrosoftGraphErrorEnvelope.class)
                .map(error -> Optional.ofNullable(error.providerCode()))
                .defaultIfEmpty(Optional.empty())
                .onErrorReturn(Optional.empty())
                .map(providerCode -> new MicrosoftGraphMailResponse(
                        httpStatus,
                        providerCode.orElse(null),
                        requestId,
                        retryAfterSeconds));
    }

    private static Long numericRetryAfter(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            // HTTP 日期和非法值不进入结构化日志，退避仍由既有 RabbitMQ 延迟表控制。
            return null;
        }
    }

    /**
     * 隔离实际 WebClient 调用，使请求模型、超时和错误分类能够由无网络单元测试覆盖。
     */
    @FunctionalInterface
    interface MicrosoftGraphMailRequester {

        Mono<MicrosoftGraphMailResponse> send(MicrosoftGraphMailRequest request);
    }

    /**
     * 表示一次内存中的 Graph 邮件请求；字符串表示固定脱敏，避免调试输出令牌或邮件内容。
     */
    record MicrosoftGraphMailRequest(
            String accessToken,
            MicrosoftGraphSendMailBody requestBody) {

        MicrosoftGraphMailRequest {
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalArgumentException("accessToken must not be blank");
            }
            Objects.requireNonNull(requestBody, "requestBody must not be null");
        }

        @Override
        public String toString() {
            return "MicrosoftGraphMailRequest[credentialsAndMessage=protected]";
        }
    }

    /** 表示 Graph sendMail 接口允许保留的最小安全响应，不携带原始响应文本。 */
    record MicrosoftGraphMailResponse(
            int httpStatus,
            String providerCode,
            String requestId,
            Long retryAfterSeconds) {

        MicrosoftGraphMailResponse {
            providerCode = safeOptional(providerCode);
            requestId = safeOptional(requestId);
        }

        @Override
        public String toString() {
            return "MicrosoftGraphMailResponse[safeMetadata=protected]";
        }

        private static String safeOptional(String value) {
            String sanitized =
                    VerificationDeliveryProviderMetadata.sanitizeDiagnosticValue(value);
            return "unavailable".equals(sanitized) ? null : sanitized;
        }
    }

    /** Graph sendMail 的顶层请求，只包含邮件和是否保存到已发送邮件。 */
    record MicrosoftGraphSendMailBody(
            MicrosoftGraphMessage message,
            boolean saveToSentItems) {

        @Override
        public String toString() {
            return "MicrosoftGraphSendMailBody[message=protected]";
        }
    }

    /** Graph 邮件模型固定只包含主题、纯文本正文和收件人列表。 */
    record MicrosoftGraphMessage(
            String subject,
            MicrosoftGraphItemBody body,
            List<MicrosoftGraphRecipient> toRecipients) {

        @Override
        public String toString() {
            return "MicrosoftGraphMessage[content=protected]";
        }
    }

    /** Graph 纯文本正文模型。 */
    record MicrosoftGraphItemBody(String contentType, String content) {

        @Override
        public String toString() {
            return "MicrosoftGraphItemBody[content=protected]";
        }
    }

    /** Graph 收件人模型。 */
    record MicrosoftGraphRecipient(MicrosoftGraphEmailAddress emailAddress) {

        @Override
        public String toString() {
            return "MicrosoftGraphRecipient[address=protected]";
        }
    }

    /** Graph 邮箱地址模型。 */
    record MicrosoftGraphEmailAddress(String address) {

        @Override
        public String toString() {
            return "MicrosoftGraphEmailAddress[address=protected]";
        }
    }

    /** Graph 错误响应的最小白名单模型，只接收稳定机器码。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MicrosoftGraphErrorEnvelope(MicrosoftGraphError error) {

        private String providerCode() {
            return error == null ? null : error.code();
        }
    }

    /** Graph error 节点的最小白名单模型，明确忽略原始错误消息和详情。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MicrosoftGraphError(String code) {
    }
}
