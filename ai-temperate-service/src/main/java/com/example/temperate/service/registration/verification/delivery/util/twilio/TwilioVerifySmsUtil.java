package com.example.temperate.service.registration.verification.delivery.util.twilio;

import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.twilio.exception.ApiException;
import com.twilio.http.NetworkHttpClient;
import com.twilio.http.Request;
import com.twilio.http.Response;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.verify.v2.service.Verification;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 通过 Twilio Verify 把项目生成的六位验证码发送到国际手机号。
 *
 * <p>该工具只负责创建带自定义验证码的短信投递请求，不调用 Twilio 的验证码校验接口；验证码摘要存储、
 * 用户输入校验、失败次数控制和校验成功后的原子删除仍由项目 Redis 业务负责。工具返回的 {@code VE}
 * SID 只表示 Twilio 已接受请求，不能证明终端用户已经收到短信。
 */
public final class TwilioVerifySmsUtil {

    private static final String PROVIDER = "twilio-verify";
    private static final String SMS_CHANNEL = "sms";
    private static final String PENDING_STATUS = "pending";
    private static final String ACCOUNT_SID_ENV = "TWILIO_ACCOUNT_SID";
    private static final String AUTH_TOKEN_ENV = "TWILIO_AUTH_TOKEN";
    private static final String VERIFY_SERVICE_SID_ENV = "TWILIO_VERIFY_SERVICE_SID";
    private static final Pattern ACCOUNT_SID_PATTERN =
            Pattern.compile("^AC[0-9a-fA-F]{32}$");
    private static final Pattern VERIFY_SERVICE_SID_PATTERN =
            Pattern.compile("^VA[0-9a-fA-F]{32}$");
    private static final Pattern VERIFICATION_SID_PATTERN =
            Pattern.compile("^VE[0-9a-fA-F]{32}$");
    private static final Pattern VERIFICATION_CODE_PATTERN =
            Pattern.compile("^[0-9]{6}$");

    private final TwilioRestClient twilioRestClient;
    private final String verifyServiceSid;
    private final PhoneNumberUtil phoneNumberUtil;
    private final Clock clock;
    private final VerificationSender verificationSender;

    /**
     * 使用进程环境变量中的 Twilio 凭据创建短信工具。
     *
     * <p>缺少任一环境变量时立即失败，避免应用运行到首次发送时才发现凭据未配置。
     */
    public TwilioVerifySmsUtil() {
        this(System::getenv);
    }

    /**
     * 使用显式凭据创建短信工具，供后续 Spring 配置类通过构造器注入环境配置。
     *
     * @param accountSid Twilio Account SID，必须以 {@code AC} 开头
     * @param authToken Twilio Auth Token
     * @param verifyServiceSid Twilio Verify Service SID，必须以 {@code VA} 开头
     */
    public TwilioVerifySmsUtil(
            String accountSid,
            String authToken,
            String verifyServiceSid) {
        this(
                createClient(accountSid, authToken),
                requireSid(
                        verifyServiceSid,
                        "verifyServiceSid",
                        VERIFY_SERVICE_SID_PATTERN),
                PhoneNumberUtil.getInstance(),
                Clock.systemUTC(),
                TwilioVerifySmsUtil::createVerification);
    }

    /**
     * 使用 Spring 统一管理的 Twilio REST 客户端创建 Verify 短信工具，避免不同投递方式重复建立客户端。
     */
    public TwilioVerifySmsUtil(
            TwilioRestClient twilioRestClient,
            String verifyServiceSid) {
        this(
                twilioRestClient,
                verifyServiceSid,
                PhoneNumberUtil.getInstance(),
                Clock.systemUTC(),
                TwilioVerifySmsUtil::createVerification);
    }

    TwilioVerifySmsUtil(Function<String, String> environment) {
        this(
                readEnvironment(environment, ACCOUNT_SID_ENV),
                readEnvironment(environment, AUTH_TOKEN_ENV),
                readEnvironment(environment, VERIFY_SERVICE_SID_ENV));
    }

    TwilioVerifySmsUtil(
            TwilioRestClient twilioRestClient,
            String verifyServiceSid,
            PhoneNumberUtil phoneNumberUtil,
            Clock clock,
            VerificationSender verificationSender) {
        this.twilioRestClient =
                Objects.requireNonNull(twilioRestClient, "twilioRestClient must not be null");
        this.verifyServiceSid =
                requireSid(verifyServiceSid, "verifyServiceSid", VERIFY_SERVICE_SID_PATTERN);
        this.phoneNumberUtil =
                Objects.requireNonNull(phoneNumberUtil, "phoneNumberUtil must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.verificationSender =
                Objects.requireNonNull(verificationSender, "verificationSender must not be null");
    }

    /**
     * 发送请求中由项目生成的六位验证码。
     *
     * <p>Twilio Java SDK 是阻塞客户端，因此供应商调用固定调度到 bounded-elastic 线程池，避免阻塞
     * Reactor 或 RabbitMQ 消费线程。该方法不会读取或修改 Redis，也不会验证用户随后输入的验证码。
     *
     * @param request 包含国际手机号和六位验证码的受保护投递请求
     * @return Twilio 接受请求后生成的脱敏投递结果
     */
    public Mono<VerificationDeliveryResult> sendVerificationCode(
            VerificationDeliveryRequest request) {
        return Mono.fromCallable(() -> sendBlocking(request))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::deliveryFailure);
    }

    private VerificationDeliveryResult sendBlocking(VerificationDeliveryRequest request) {
        String normalizedDestination = validateAndNormalize(request);
        Verification verification = verificationSender.send(
                twilioRestClient,
                verifyServiceSid,
                normalizedDestination,
                request.code());
        return acceptedResult(verification);
    }

    private VerificationDeliveryResult acceptedResult(Verification verification) {
        if (verification == null
                || verification.getSid() == null
                || !VERIFICATION_SID_PATTERN.matcher(verification.getSid()).matches()) {
            throw new VerificationDeliveryException(
                    true,
                    PROVIDER,
                    "twilio_invalid_verification_sid",
                    new VerificationDeliveryProviderMetadata(
                            null, null, "failed", false, null, null),
                    null);
        }
        if (!PENDING_STATUS.equalsIgnoreCase(verification.getStatus())) {
            throw new VerificationDeliveryException(
                    true,
                    PROVIDER,
                    "twilio_unexpected_verification_status",
                    new VerificationDeliveryProviderMetadata(
                            null,
                            null,
                            verification.getStatus(),
                            false,
                            null,
                            null),
                    null);
        }
        return new VerificationDeliveryResult(
                VerificationChannel.SMS,
                PROVIDER,
                verification.getSid(),
                Instant.now(clock),
                new VerificationDeliveryProviderMetadata(
                        null, null, PENDING_STATUS, true, null, null));
    }

    private String validateAndNormalize(VerificationDeliveryRequest request) {
        if (request == null
                || request.destination() == null
                || request.destination().isBlank()
                || !request.destination().startsWith("+")
                || request.code() == null
                || !VERIFICATION_CODE_PATTERN.matcher(request.code()).matches()) {
            throw invalidRequest(null);
        }
        try {
            var parsedNumber = phoneNumberUtil.parse(request.destination(), "ZZ");
            if (!phoneNumberUtil.isValidNumber(parsedNumber)) {
                throw invalidRequest(null);
            }
            return phoneNumberUtil.format(parsedNumber, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException exception) {
            throw invalidRequest(exception);
        }
    }

    private Throwable deliveryFailure(Throwable failure) {
        if (failure instanceof VerificationDeliveryException) {
            return failure;
        }
        if (failure instanceof ApiException apiException) {
            Integer statusCode = apiException.getStatusCode() != null
                    ? apiException.getStatusCode()
                    : apiException.getHttpStatusCode();
            boolean retryable =
                    statusCode == null || statusCode == 429 || statusCode >= 500;
            return new VerificationDeliveryException(
                    retryable,
                    PROVIDER,
                    "twilio_api_failure",
                    new VerificationDeliveryProviderMetadata(
                            statusCode,
                            apiException.getCode() == null
                                    ? null
                                    : apiException.getCode().toString(),
                            "failed",
                            false,
                            null,
                            ApiException.class.getSimpleName()),
                    apiException);
        }
        return new VerificationDeliveryException(
                true,
                PROVIDER,
                "twilio_request_failed",
                new VerificationDeliveryProviderMetadata(
                        null, null, "failed", false, null, exceptionClass(failure)),
                failure);
    }

    private static VerificationDeliveryException invalidRequest(Throwable cause) {
        return new VerificationDeliveryException(
                false,
                PROVIDER,
                "twilio_invalid_request",
                new VerificationDeliveryProviderMetadata(
                        null, null, "failed", false, null, exceptionClass(cause)),
                cause);
    }

    private static String exceptionClass(Throwable failure) {
        return failure == null ? null : failure.getClass().getSimpleName();
    }

    private static Verification createVerification(
            TwilioRestClient client,
            String serviceSid,
            String destination,
            String code) {
        return Verification.creator(serviceSid, destination, SMS_CHANNEL)
                .setCustomCode(code)
                .create(client);
    }

    private static TwilioRestClient createClient(String accountSid, String authToken) {
        String validatedAccountSid =
                requireSid(accountSid, "accountSid", ACCOUNT_SID_PATTERN);
        String validatedAuthToken = requireText(authToken, "authToken");
        return new TwilioRestClient.Builder(validatedAccountSid, validatedAuthToken)
                // Twilio SDK 默认重试 5xx；这里改为单次调用，避免和项目 RabbitMQ 延迟重试叠加。
                .httpClient(new SingleAttemptNetworkHttpClient())
                .build();
    }

    private static String readEnvironment(
            Function<String, String> environment, String variableName) {
        Objects.requireNonNull(environment, "environment must not be null");
        return requireText(environment.apply(variableName), variableName);
    }

    private static String requireSid(String value, String name, Pattern pattern) {
        String requiredValue = requireText(value, name);
        if (!pattern.matcher(requiredValue).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        return requiredValue;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @FunctionalInterface
    interface VerificationSender {

        Verification send(
                TwilioRestClient client,
                String serviceSid,
                String destination,
                String code);
    }

    /**
     * 把 Twilio SDK 的可靠请求改为单次网络请求，跨时间重试统一交给项目 RabbitMQ 控制。
     */
    private static final class SingleAttemptNetworkHttpClient extends NetworkHttpClient {

        @Override
        public Response reliableRequest(Request request) {
            return makeRequest(request);
        }
    }
}
