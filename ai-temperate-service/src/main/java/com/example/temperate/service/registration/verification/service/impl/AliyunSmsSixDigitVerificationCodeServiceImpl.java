package com.example.temperate.service.registration.verification.service.impl;

import com.example.temperate.common.aliyun.AliyunUtils;
import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationProvider;
import com.example.temperate.service.registration.verification.delivery.classification.AliyunSmsFailureClassifier;
import com.example.temperate.service.registration.verification.delivery.classification.AliyunSmsFailureClassifier.FailureDecision;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.Endpoint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.Operation;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeService;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeVerifier;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 通过统一阿里云工具发送中国大陆六位数短信验证码，并把供应商结果转换成 RabbitMQ 可执行的有限重试决策。
 *
 * <p>该实现不持有可复用 SDK 客户端，不自行决定延迟次数。工具类负责请求参数、代理选路及客户端关闭，分类器负责区分
 * 业务拒绝、明确瞬态故障和结果未知；Redis 验证仍委托共享校验器。</p>
 */
@Service
@ConditionalOnExpression(
        "!'${aliyun.sms.sign-name:}'.isEmpty() && "
                + "!'${app.registration.sms.template-code:}'.isEmpty()")
public final class AliyunSmsSixDigitVerificationCodeServiceImpl
        implements SixDigitVerificationCodeService {

    private static final String PROVIDER = "aliyun-dypnsapi";

    private final AliyunUtils aliyunUtils;
    private final String templateCode;
    private final AliyunSmsFailureClassifier failureClassifier;
    private final SixDigitVerificationCodeVerifier codeVerifier;

    @Autowired
    public AliyunSmsSixDigitVerificationCodeServiceImpl(
            AliyunUtils aliyunUtils,
            @Value("${app.registration.sms.template-code}") String templateCode,
            AliyunSmsFailureClassifier failureClassifier,
            SixDigitVerificationCodeVerifier codeVerifier) {
        this.aliyunUtils = Objects.requireNonNull(aliyunUtils, "aliyunUtils must not be null");
        if (templateCode == null || templateCode.isBlank()) {
            throw new IllegalArgumentException("SMS template code is required.");
        }
        this.templateCode = templateCode;
        this.failureClassifier =
                Objects.requireNonNull(failureClassifier, "failureClassifier must not be null");
        this.codeVerifier = Objects.requireNonNull(codeVerifier, "codeVerifier must not be null");
    }

    @Override
    public VerificationProvider type() {
        return VerificationProvider.ALIYUN_SMS;
    }

    @Override
    public Mono<VerificationDeliveryResult> sendCode(VerificationDeliveryRequest request) {
        return Mono.fromCallable(() -> send(request))
                // shopping 同源工具使用阻塞等待适配异步 SDK；隔离到有界弹性线程，避免占用 Reactor I/O 线程。
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::deliveryFailure);
    }

    @Override
    public RegistrationStatusResult verifyCode(RegistrationVerifyCodeCommand command) {
        return codeVerifier.verify(command);
    }

    private VerificationDeliveryResult send(VerificationDeliveryRequest request) throws Exception {
        Duration validity = requireValidity(request);
        AliyunUtils.SmsSendResult result = aliyunUtils.sendSmsVerifyCode(
                request.destination(), templateCode, request.code(), validity);
        if (result == null || !result.accepted()) {
            FailureDecision decision = failureClassifier.classifyResult(result);
            throw new VerificationDeliveryException(
                    decision.retryable(),
                    PROVIDER,
                    decision.safeReason(),
                    metadata(result, decision, null),
                    null);
        }
        return new VerificationDeliveryResult(
                VerificationChannel.SMS,
                PROVIDER,
                blankToNull(result.requestId()),
                Instant.now(),
                metadata(result, null, null));
    }

    private VerificationDeliveryException deliveryFailure(Throwable failure) {
        if (failure instanceof VerificationDeliveryException deliveryException) {
            return deliveryException;
        }
        FailureDecision decision = failureClassifier.classifyFailure(failure);
        return new VerificationDeliveryException(
                decision.retryable(),
                PROVIDER,
                decision.safeReason(),
                metadata(null, decision, failure),
                failure);
    }

    private static VerificationDeliveryProviderMetadata metadata(
            AliyunUtils.SmsSendResult result,
            FailureDecision decision,
            Throwable failure) {
        return new VerificationDeliveryProviderMetadata(
                result == null ? null : result.httpStatus(),
                result == null ? null : result.providerCode(),
                result != null && result.accepted() ? "accepted" : "failed",
                result == null ? null : result.providerSuccess(),
                result == null ? null : result.requestId(),
                failure == null ? null : failure.getClass().getSimpleName(),
                Operation.SEND_SMS,
                Endpoint.ALIYUN_DYPNSAPI,
                decision == null ? null : decision.failureStage(),
                decision == null ? null : decision.failureCategory(),
                decision == null ? null : decision.failureHint(),
                decision == null ? null : decision.recommendedAction(),
                null,
                null,
                null);
    }

    private static Duration requireValidity(VerificationDeliveryRequest request) {
        if (request == null || request.validity() == null) {
            throw new IllegalArgumentException("SMS delivery validity is required.");
        }
        return request.validity();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
