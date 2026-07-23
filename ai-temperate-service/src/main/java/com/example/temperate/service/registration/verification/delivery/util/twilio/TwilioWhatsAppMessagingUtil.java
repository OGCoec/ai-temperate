package com.example.temperate.service.registration.verification.delivery.util.twilio;

import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryOutcome;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.Endpoint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureCategory;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureHint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureStage;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.Operation;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.RecommendedAction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.twilio.exception.ApiException;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import java.time.Clock;
import java.time.Instant;
import java.net.URI;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 通过 Twilio Programmable Messaging 和已审核 Content Template 发送 WhatsApp 六位验证码。
 *
 * <p>该工具只负责把项目生成的验证码投递到 WhatsApp，不持有验证码状态，也不调用 Twilio Verify；
 * 返回的 Message SID 仅表示 Twilio 接受了创建请求，后续异步送达状态不在本版本职责范围内。</p>
 */
public final class TwilioWhatsAppMessagingUtil {

    private static final String PROVIDER = "twilio-whatsapp";
    private static final String ADDRESS_PREFIX = "whatsapp:";
    private static final Pattern CONTENT_SID_PATTERN = Pattern.compile("^HX[0-9a-fA-F]{32}$");
    private static final Pattern MESSAGE_SID_PATTERN =
            Pattern.compile("^(SM|MM)[0-9a-fA-F]{32}$");
    private static final Pattern CODE_PATTERN = Pattern.compile("^[0-9]{6}$");
    private static final Pattern E164_PATTERN = Pattern.compile("^\\+[1-9][0-9]{1,14}$");
    private static final Set<Integer> RETRYABLE_PROVIDER_CODES = Set.of(
            20429, 63009, 63010, 63012, 63017, 63018, 63117, 63119);
    private static final Set<Message.Status> ACCEPTED_STATUSES = Set.of(
            Message.Status.ACCEPTED,
            Message.Status.QUEUED,
            Message.Status.SENDING,
            Message.Status.SENT,
            Message.Status.DELIVERED,
            Message.Status.READ);

    private final TwilioRestClient twilioRestClient;
    private final String fromAddress;
    private final String contentSid;
    private final String statusCallbackUrl;
    private final PhoneNumberUtil phoneNumberUtil;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final WhatsAppMessageSender messageSender;

    public TwilioWhatsAppMessagingUtil(
            TwilioRestClient twilioRestClient,
            String from,
            String contentSid) {
        this(twilioRestClient, from, contentSid, "", new ObjectMapper());
    }

    public TwilioWhatsAppMessagingUtil(
            TwilioRestClient twilioRestClient,
            String from,
            String contentSid,
            ObjectMapper objectMapper) {
        this(twilioRestClient, from, contentSid, "", objectMapper);
    }

    public TwilioWhatsAppMessagingUtil(
            TwilioRestClient twilioRestClient,
            String from,
            String contentSid,
            String statusCallbackUrl,
            ObjectMapper objectMapper) {
        this(
                twilioRestClient,
                from,
                contentSid,
                statusCallbackUrl,
                PhoneNumberUtil.getInstance(),
                objectMapper,
                Clock.systemUTC(),
                TwilioWhatsAppMessagingUtil::createMessage);
    }

    TwilioWhatsAppMessagingUtil(
            TwilioRestClient twilioRestClient,
            String from,
            String contentSid,
            PhoneNumberUtil phoneNumberUtil,
            ObjectMapper objectMapper,
            Clock clock,
            LegacyWhatsAppMessageSender messageSender) {
        this(
                twilioRestClient,
                from,
                contentSid,
                "",
                phoneNumberUtil,
                objectMapper,
                clock,
                (client, to, configuredFrom, configuredContentSid, variables, callback) ->
                        messageSender.send(client, to, configuredFrom, configuredContentSid, variables));
    }

    TwilioWhatsAppMessagingUtil(
            TwilioRestClient twilioRestClient,
            String from,
            String contentSid,
            PhoneNumberUtil phoneNumberUtil,
            ObjectMapper objectMapper,
            Clock clock,
            WhatsAppMessageSender messageSender) {
        this(
                twilioRestClient,
                from,
                contentSid,
                "",
                phoneNumberUtil,
                objectMapper,
                clock,
                messageSender);
    }

    TwilioWhatsAppMessagingUtil(
            TwilioRestClient twilioRestClient,
            String from,
            String contentSid,
            String statusCallbackUrl,
            PhoneNumberUtil phoneNumberUtil,
            ObjectMapper objectMapper,
            Clock clock,
            WhatsAppMessageSender messageSender) {
        this.twilioRestClient =
                Objects.requireNonNull(twilioRestClient, "twilioRestClient must not be null");
        this.phoneNumberUtil =
                Objects.requireNonNull(phoneNumberUtil, "phoneNumberUtil must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.messageSender = Objects.requireNonNull(messageSender, "messageSender must not be null");
        this.fromAddress = ADDRESS_PREFIX + normalizeConfiguredNumber(from, "from");
        this.contentSid = requirePattern(contentSid, "contentSid", CONTENT_SID_PATTERN);
        this.statusCallbackUrl = normalizeStatusCallbackUrl(statusCallbackUrl);
    }

    /**
     * 在 bounded-elastic 线程执行阻塞 SDK 调用，并把第三方失败收敛成 RabbitMQ 可消费的安全分类。
     */
    public Mono<VerificationDeliveryResult> sendVerificationCode(
            VerificationDeliveryRequest request) {
        return Mono.fromCallable(() -> sendBlocking(request))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::deliveryFailure);
    }

    private VerificationDeliveryResult sendBlocking(VerificationDeliveryRequest request) {
        String destination = validateAndNormalize(request);
        String variables = contentVariables(request.code());
        Message message = messageSender.send(
                twilioRestClient,
                ADDRESS_PREFIX + destination,
                fromAddress,
                contentSid,
                variables,
                statusCallbackUrl);
        return acceptedResult(message);
    }

    private VerificationDeliveryResult acceptedResult(Message message) {
        if (message == null
                || message.getSid() == null
                || !MESSAGE_SID_PATTERN.matcher(message.getSid()).matches()) {
            throw unknownFailure(
                    "twilio_whatsapp_response_missing_sid",
                    (Throwable) null);
        }
        Message.Status status = message.getStatus();
        if (status == Message.Status.FAILED || status == Message.Status.UNDELIVERED) {
            Integer providerCode = message.getErrorCode();
            throw explicitFailure(
                    isRetryable(null, providerCode),
                    "twilio_whatsapp_rejected",
                    null,
                    providerCode,
                    status);
        }
        if (!ACCEPTED_STATUSES.contains(status)) {
            throw unknownFailure("twilio_whatsapp_unrecognized_status", status);
        }
        return new VerificationDeliveryResult(
                VerificationChannel.SMS,
                VerificationDeliveryMethod.WHATSAPP,
                PROVIDER,
                message.getSid(),
                Instant.now(clock),
                metadata(null, null, status, true, null));
    }

    private String validateAndNormalize(VerificationDeliveryRequest request) {
        if (request == null
                || request.destination() == null
                || request.destination().isBlank()
                || !E164_PATTERN.matcher(request.destination()).matches()
                || request.code() == null
                || !CODE_PATTERN.matcher(request.code()).matches()) {
            throw invalidRequest(null);
        }
        try {
            var parsed = phoneNumberUtil.parse(request.destination(), "ZZ");
            if (!phoneNumberUtil.isValidNumber(parsed) || parsed.getCountryCode() == 86) {
                throw invalidRequest(null);
            }
            return phoneNumberUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException exception) {
            throw invalidRequest(exception);
        }
    }

    private String contentVariables(String code) {
        try {
            // 使用 JSON 序列化器生成模板变量，避免未来模板扩展时通过字符串拼接引入转义缺陷。
            return objectMapper.writeValueAsString(Map.of("1", code));
        } catch (JsonProcessingException exception) {
            throw new VerificationDeliveryException(
                    false,
                    PROVIDER,
                    "twilio_whatsapp_template_variables_invalid",
                    localValidationMetadata(exception),
                    exception);
        }
    }

    private Throwable deliveryFailure(Throwable throwable) {
        if (throwable instanceof VerificationDeliveryException) {
            return throwable;
        }
        if (containsTimeout(throwable)) {
            return unknownFailure("twilio_whatsapp_transport_outcome_unknown", throwable);
        }
        if (throwable instanceof ApiException apiException) {
            Integer httpStatus = apiException.getStatusCode() != null
                    ? apiException.getStatusCode()
                    : apiException.getHttpStatusCode();
            Integer providerCode = apiException.getCode();
            if (httpStatus == null && providerCode == null) {
                return unknownFailure(
                        "twilio_whatsapp_transport_outcome_unknown", apiException);
            }
            return new VerificationDeliveryException(
                    VerificationDeliveryOutcome.EXPLICIT_FAILURE,
                    isRetryable(httpStatus, providerCode),
                    PROVIDER,
                    "twilio_whatsapp_api_failure",
                    metadata(httpStatus, providerCode, Message.Status.FAILED, false, apiException),
                    apiException);
        }
        return new VerificationDeliveryException(
                VerificationDeliveryOutcome.UNKNOWN,
                false,
                PROVIDER,
                "twilio_whatsapp_transport_outcome_unknown",
                unknownMetadata(throwable),
                throwable);
    }

    private static boolean containsTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String type = current.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            if (type.contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static VerificationDeliveryException invalidRequest(Throwable cause) {
        return new VerificationDeliveryException(
                false,
                PROVIDER,
                "twilio_whatsapp_invalid_request",
                localValidationMetadata(cause),
                cause);
    }

    private static VerificationDeliveryException explicitFailure(
            boolean retryable,
            String safeReason,
            Integer httpStatus,
            Integer providerCode,
            Message.Status status) {
        return new VerificationDeliveryException(
                VerificationDeliveryOutcome.EXPLICIT_FAILURE,
                retryable,
                PROVIDER,
                safeReason,
                metadata(httpStatus, providerCode, status, false, null),
                null);
    }

    private static VerificationDeliveryException unknownFailure(
            String safeReason, Throwable cause) {
        return new VerificationDeliveryException(
                VerificationDeliveryOutcome.UNKNOWN,
                false,
                PROVIDER,
                safeReason,
                unknownMetadata(cause),
                cause);
    }

    private static VerificationDeliveryException unknownFailure(
            String safeReason, Message.Status status) {
        return new VerificationDeliveryException(
                VerificationDeliveryOutcome.UNKNOWN,
                false,
                PROVIDER,
                safeReason,
                unknownMetadata(status),
                null);
    }

    private static VerificationDeliveryProviderMetadata metadata(
            Integer httpStatus,
            Integer providerCode,
            Message.Status status,
            Boolean success,
            Throwable failure) {
        if (Boolean.TRUE.equals(success)) {
            return new VerificationDeliveryProviderMetadata(
                    httpStatus,
                    providerCode == null ? null : providerCode.toString(),
                    status == null ? null : status.toString().toLowerCase(Locale.ROOT),
                    true,
                    null,
                    null,
                    Operation.SEND_WHATSAPP,
                    Endpoint.TWILIO_MESSAGES,
                    null,
                    null,
                    null,
                    null,
                    true,
                    null,
                    null);
        }
        boolean sandboxNotJoined = providerCode != null && providerCode == 63015;
        boolean retryable = isRetryable(httpStatus, providerCode);
        boolean providerResponse = httpStatus != null || providerCode != null || status != null;
        boolean throttled = (httpStatus != null && httpStatus == 429)
                || (providerCode != null && (providerCode == 20429 || providerCode == 63018));
        return new VerificationDeliveryProviderMetadata(
                httpStatus,
                providerCode == null ? null : providerCode.toString(),
                status == null ? null : status.toString().toLowerCase(Locale.ROOT),
                success,
                null,
                failure == null ? null : failure.getClass().getSimpleName(),
                Operation.SEND_WHATSAPP,
                Endpoint.TWILIO_MESSAGES,
                providerResponse ? FailureStage.PROVIDER_API : FailureStage.TRANSPORT,
                sandboxNotJoined
                        ? FailureCategory.PERMISSION_DENIED
                        : !providerResponse
                                ? FailureCategory.TRANSPORT_FAILURE
                                : throttled
                                        ? FailureCategory.THROTTLED
                                        : retryable
                                                ? FailureCategory.TRANSIENT_PROVIDER_FAILURE
                                                : FailureCategory.INVALID_REQUEST,
                sandboxNotJoined
                        ? FailureHint.WHATSAPP_SANDBOX_NOT_JOINED
                        : !providerResponse
                                ? FailureHint.PROVIDER_CONNECTION_FAILED
                                : retryable
                                        ? FailureHint.PROVIDER_TEMPORARILY_UNAVAILABLE
                                        : providerCode != null
                                                ? FailureHint.WHATSAPP_SENDER_OR_TEMPLATE_INVALID
                                                : FailureHint.PROVIDER_REJECTED_REQUEST,
                sandboxNotJoined
                        ? RecommendedAction.JOIN_WHATSAPP_SANDBOX
                        : !providerResponse
                                ? RecommendedAction.CHECK_NETWORK_AND_RETRY
                                : retryable
                                        ? RecommendedAction.RETRY_WITH_BACKOFF
                                        : RecommendedAction.VERIFY_WHATSAPP_SENDER_AND_TEMPLATE,
                true,
                null,
                null);
    }

    private static VerificationDeliveryProviderMetadata unknownMetadata(Throwable failure) {
        return new VerificationDeliveryProviderMetadata(
                null,
                null,
                null,
                false,
                null,
                failure == null ? null : failure.getClass().getSimpleName(),
                Operation.SEND_WHATSAPP,
                Endpoint.TWILIO_MESSAGES,
                FailureStage.TRANSPORT,
                FailureCategory.OUTCOME_UNKNOWN,
                FailureHint.SMS_DELIVERY_OUTCOME_UNKNOWN,
                RecommendedAction.INSPECT_DELIVERY_BEFORE_RETRY,
                true,
                null,
                null);
    }

    private static VerificationDeliveryProviderMetadata unknownMetadata(Message.Status status) {
        return new VerificationDeliveryProviderMetadata(
                null,
                null,
                status == null ? null : status.toString().toLowerCase(Locale.ROOT),
                false,
                null,
                null,
                Operation.SEND_WHATSAPP,
                Endpoint.TWILIO_MESSAGES,
                FailureStage.PROVIDER_API,
                FailureCategory.OUTCOME_UNKNOWN,
                FailureHint.SMS_DELIVERY_OUTCOME_UNKNOWN,
                RecommendedAction.INSPECT_DELIVERY_BEFORE_RETRY,
                true,
                null,
                null);
    }

    private static VerificationDeliveryProviderMetadata localValidationMetadata(
            Throwable failure) {
        return new VerificationDeliveryProviderMetadata(
                null,
                null,
                null,
                false,
                null,
                failure == null ? null : failure.getClass().getSimpleName(),
                Operation.SEND_WHATSAPP,
                Endpoint.TWILIO_MESSAGES,
                FailureStage.REQUEST_VALIDATION,
                FailureCategory.INVALID_REQUEST,
                FailureHint.WHATSAPP_SENDER_OR_TEMPLATE_INVALID,
                RecommendedAction.VERIFY_WHATSAPP_SENDER_AND_TEMPLATE,
                true,
                null,
                null);
    }

    private static boolean isRetryable(Integer httpStatus, Integer providerCode) {
        if (providerCode != null && providerCode == 63015) {
            return false;
        }
        return (providerCode != null && RETRYABLE_PROVIDER_CODES.contains(providerCode))
                || (httpStatus != null && (httpStatus == 429 || httpStatus >= 500));
    }

    private String normalizeConfiguredNumber(String value, String name) {
        String required = requireText(value, name);
        if (!E164_PATTERN.matcher(required).matches()) {
            throw new IllegalArgumentException(name + " must use E.164 format");
        }
        try {
            var parsed = phoneNumberUtil.parse(required, "ZZ");
            if (!phoneNumberUtil.isValidNumber(parsed)) {
                throw new IllegalArgumentException(name + " must be a valid E.164 number");
            }
            return phoneNumberUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException exception) {
            throw new IllegalArgumentException(name + " must be a valid E.164 number", exception);
        }
    }

    private static String requirePattern(String value, String name, Pattern pattern) {
        String required = requireText(value, name);
        if (!pattern.matcher(required).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        return required;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String normalizeStatusCallbackUrl(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isEmpty()) {
            return "";
        }
        try {
            URI uri = URI.create(candidate);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException(
                        "statusCallbackUrl must be an HTTPS URL without a fragment");
            }
            return candidate;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("statusCallbackUrl must be a valid HTTPS URL", exception);
        }
    }

    private static Message createMessage(
            TwilioRestClient client,
            String to,
            String from,
            String contentSid,
            String contentVariables,
            String statusCallbackUrl) {
        var creator = Message.creator(new PhoneNumber(to), new PhoneNumber(from), (String) null)
                .setContentSid(contentSid)
                .setContentVariables(contentVariables);
        if (!statusCallbackUrl.isBlank()) {
            creator.setStatusCallback(statusCallbackUrl);
        }
        return creator.create(client);
    }

    @FunctionalInterface
    interface WhatsAppMessageSender {

        Message send(
                TwilioRestClient client,
                String to,
                String from,
                String contentSid,
                String contentVariables,
                String statusCallbackUrl);
    }

    @FunctionalInterface
    interface LegacyWhatsAppMessageSender {

        Message send(
                TwilioRestClient client,
                String to,
                String from,
                String contentSid,
                String contentVariables);
    }
}
