package com.example.temperate.service.user.membership.payment.provider.liuhao.impl;

import com.example.temperate.common.net.ip.IpAddressIdentity;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentLifecycleDiagnostics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTraceContext;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutMode;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutSubmission;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutSubmissionFields;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCreateCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCreateResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderReference;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundResult;
import com.example.temperate.service.user.membership.payment.provider.liuhao.LiuhaoPaymentClient;
import com.example.temperate.service.user.membership.payment.provider.liuhao.LiuhaoPaymentSignatureService;
import com.example.temperate.service.user.membership.payment.provider.liuhao.LiuhaoSignatureVerificationReason;
import com.example.temperate.service.user.membership.payment.provider.liuhao.LiuhaoSignatureVerificationResult;
import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 该实现是来按六号易支付 V2 RSA 合同完成支付宝统一下单、微信服务端页面提交、交易事实查询、退款和关闭。
 *
 * <p>所有响应必须依次完成大小限制、标量检查、平台公钥验签和时间戳校验，随后才能转换为项目内支付状态；
 * 微信提交由后端禁止跟随重定向，所有可能已落单的响应都通过签名查询确认后才允许绑定和返回二维码页面；
 * 页面表单只作为兼容能力，不能用于绕过真实流水绑定。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment.liuhao",
        name = "enabled",
        havingValue = "true")
public final class LiuhaoPaymentRestClientImpl implements LiuhaoPaymentClient {

    private static final String SUBMIT_PATH = "/api/pay/submit";
    private static final String CREATE_PATH = "/api/pay/create";
    private static final String QUERY_PATH = "/api/pay/query";
    private static final String REFUND_PATH = "/api/pay/refund";
    private static final String CLOSE_PATH = "/api/pay/close";
    private static final MediaType FORM_UTF8 =
            MediaType.parseMediaType("application/x-www-form-urlencoded; charset=UTF-8");
    private static final Pattern ORDER_ID = Pattern.compile("^[A-Za-z0-9_-]{22}$");
    private static final Pattern SAFE_REFERENCE = Pattern.compile("^[A-Za-z0-9._:-]{1,112}$");
    private static final int MAX_REDIRECT_URL_LENGTH = 4_096;
    private static final DateTimeFormatter LEGACY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> COMMON_RESPONSE_FIELDS = Set.of(
            "code", "msg", "timestamp", "sign", "sign_type", "pid");
    private static final Set<String> CREATE_RESPONSE_FIELDS = Set.of(
            "code", "msg", "timestamp", "sign", "sign_type", "pid",
            "out_trade_no", "trade_no", "type", "money", "pay_type", "pay_info");
    private static final Set<String> QUERY_RESPONSE_FIELDS = Set.of(
            "code", "msg", "timestamp", "sign", "sign_type", "pid",
            "out_trade_no", "trade_no", "api_trade_no", "status", "trade_status",
            "money", "finished_at", "endtime", "end_time");
    private static final Set<String> REFUND_RESPONSE_FIELDS = Set.of(
            "code", "msg", "timestamp", "sign", "sign_type", "pid",
            "out_trade_no", "trade_no", "out_refund_no", "refund_no",
            "money", "refund_money", "status", "trade_status");
    private static final Set<String> CLOSE_RESPONSE_FIELDS = Set.of(
            "code", "msg", "timestamp", "sign", "sign_type", "pid",
            "trade_no", "out_trade_no", "status", "trade_status");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final LiuhaoPaymentSignatureService signatures;
    private final MembershipPaymentProperties.Liuhao properties;
    private final Clock clock;
    private final MembershipPaymentMetrics metrics;

    @Autowired
    public LiuhaoPaymentRestClientImpl(
            @Qualifier("liuhaoPaymentRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            LiuhaoPaymentSignatureService signatures,
            MembershipPaymentProperties membershipPaymentProperties,
            Clock clock,
            MembershipPaymentMetrics metrics) {
        this.restClient = Objects.requireNonNull(restClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.signatures = Objects.requireNonNull(signatures);
        this.properties = Objects.requireNonNull(membershipPaymentProperties).liuhao();
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public PaymentCheckoutResult createCheckout(PaymentCheckoutCommand command) {
        PaymentCheckoutCommand value = requireCheckout(command);
        Map<String, String> signed = signatures.sign(checkoutFields(value));
        Instant expiresAt = clock.instant().plus(properties.timestampTolerance());
        PaymentCheckoutSubmissionFields fields = new PaymentCheckoutSubmissionFields(
                signed.get("pid"),
                signed.get("out_trade_no"),
                signed.get("type"),
                signed.get("name"),
                signed.get("money"),
                signed.get("notify_url"),
                signed.get("return_url"),
                signed.get("timestamp"),
                null,
                signed.get("sign_type"),
                signed.get("sign"));
        // 页面跳转只返回当前请求的一次性签名描述，六号收银台后续自行渲染二维码。
        PaymentCheckoutSubmission submission = new PaymentCheckoutSubmission(
                PaymentProviderType.LIUHAO,
                PaymentCheckoutMode.FORM_POST,
                approvedAction(SUBMIT_PATH),
                "POST",
                FORM_UTF8.toString(),
                MembershipPaymentTime.fromInstant(expiresAt),
                fields);
        MembershipPaymentLifecycleDiagnostics.liuhaoCheckoutSubmissionCreated(
                value.payType(),
                fields.outTradeNo() != null,
                fields.signType() != null && fields.sign() != null,
                "accepted",
                "VALIDATED",
                MembershipPaymentTraceContext.currentTraceId());
        return new PaymentCheckoutResult(null, submission.submitExpiresAt(), true, submission);
    }

    @Override
    public PaymentCreateResult createPayment(PaymentCreateCommand command) {
        PaymentCreateCommand value = Objects.requireNonNull(command);
        PaymentCheckoutCommand checkout = requireCheckout(new PaymentCheckoutCommand(
                value.orderId(), value.amountYuan(), value.payType(), value.orderName()));
        String clientIp = requireClientIp(value.clientIp());
        if ("wxpay".equals(checkout.payType())) {
            return createWxpayPayment(checkout, clientIp);
        }
        Map<String, Object> fields = new LinkedHashMap<>(checkoutFields(checkout));
        fields.put("method", "web");
        fields.put("device", "pc");
        fields.put("clientip", clientIp);
        Map<String, Object> body = postVerified(CREATE_PATH, signatures.sign(fields));
        String payTypeClass = createPayTypeClass(body);
        String payInfo = payloadScalar(body, "pay_info");
        String reason = "PAYLOAD_INVALID";
        String outcome = "rejected";
        try {
            // 可选回显字段存在时必须逐项归属核对；pay_type 是载体类型，不能与 alipay/wxpay 混为一谈。
            validateOptionalIdentity(body, checkout.orderId(), null);
            validateOptionalCreateEcho(body, checkout);
            String tradeNo = requiredSafe(body, "trade_no", 112);
            String payType;
            try {
                payType = requiredSafe(body, "pay_type", 32);
            } catch (MembershipPaymentException exception) {
                reason = "PAY_TYPE_INVALID";
                throw checkoutUnavailableAfterCreation(
                        "Liuhao created the order but returned an invalid payment carrier.",
                        tradeNo);
            }
            if (!isBrowserRedirectCarrier(payType)) {
                reason = "PAY_TYPE_UNSUPPORTED";
                throw failure(
                        MembershipPaymentErrorCode.LIUHAO_CHECKOUT_UNAVAILABLE,
                        "Liuhao created the order but returned an unsupported payment carrier.",
                        tradeNo);
            }
            String returnedPayInfo;
            try {
                returnedPayInfo = requiredText(
                        body, "pay_info", MAX_REDIRECT_URL_LENGTH);
            } catch (MembershipPaymentException exception) {
                reason = "PAY_INFO_INVALID";
                throw checkoutUnavailableAfterCreation(
                        "Liuhao created the order but did not return a valid payment entry.",
                        tradeNo);
            }
            if ("qrcode".equals(payType)
                    && !isHttpsRedirectCandidate(returnedPayInfo)) {
                reason = "PAY_INFO_UNSUPPORTED";
                throw failure(
                        MembershipPaymentErrorCode.LIUHAO_CHECKOUT_UNAVAILABLE,
                        "Liuhao created the order but returned a non-navigable QR carrier.",
                        tradeNo);
            }
            try {
                payInfo = requireRedirectUrl(returnedPayInfo);
            } catch (MembershipPaymentException exception) {
                reason = "PAY_INFO_UNSUPPORTED";
                throw checkoutUnavailableAfterCreation(
                        "Liuhao created the order but returned an unsafe payment entry.",
                        tradeNo);
            }
            reason = "VALIDATED";
            outcome = "accepted";
            return new PaymentCreateResult(tradeNo, payType, payInfo, true);
        } finally {
            MembershipPaymentLifecycleDiagnostics.liuhaoCreatePayloadValidation(
                    checkout.payType(),
                    payloadPresent(body, "trade_no"),
                    payTypeClass,
                    payloadPresent(body, "pay_info"),
                    createPayInfoKind(payTypeClass, payInfo, properties.baseUrl()),
                    payloadTypeMatches(body, checkout.payType()),
                    payloadAmountMatches(body, checkout.amountYuan()),
                    outcome,
                    reason,
                    MembershipPaymentTraceContext.currentTraceId());
        }
    }

    /**
     * 该恢复流程是来处理六号订单已经可能创建但首次响应没有支付入口的场景；只执行签名查询并重建二维码地址，绝不再次提交订单。
     */
    @Override
    public PaymentCreateResult recoverPayment(
            PaymentCreateCommand command,
            String providerTradeNo) {
        PaymentCreateCommand value = Objects.requireNonNull(command);
        PaymentCheckoutCommand checkout = requireCheckout(new PaymentCheckoutCommand(
                value.orderId(), value.amountYuan(), value.payType(), value.orderName()));
        if (!"wxpay".equals(checkout.payType())) {
            throw createOutcomeUnknown(
                    "Only an existing Liuhao wxpay order can be recovered safely.");
        }
        String rawTradeNo = PaymentProviderReference.rawTradeNo(providerTradeNo);
        PaymentQueryResult confirmed;
        try {
            // 恢复只能查询既有订单；查询真实性无法确认时保持 started + trade null/原真实流水，禁止退回重新提交。
            confirmed = queryPayment(new PaymentQueryCommand(
                    checkout.orderId(), rawTradeNo));
        } catch (RuntimeException exception) {
            if (exception instanceof MembershipPaymentException paymentException
                    && paymentException.code()
                            == MembershipPaymentErrorCode.LIUHAO_ORDER_CONFLICT) {
                throw exception;
            }
            throw createOutcomeUnknown(
                    "The existing Liuhao wxpay transaction could not be confirmed safely.");
        }
        if (confirmed.amountYuan() == null
                || confirmed.amountYuan().compareTo(requireAmount(checkout.amountYuan())) != 0) {
            throw failure(
                    MembershipPaymentErrorCode.LIUHAO_ORDER_CONFLICT,
                    "Liuhao wxpay transaction amount does not match the local order.");
        }
        if (confirmed.status() != PaymentProviderStatus.PENDING
                && confirmed.status() != PaymentProviderStatus.PAID) {
            throw checkoutUnavailableAfterCreation(
                    "Liuhao wxpay transaction is no longer payable.",
                    confirmed.providerTradeNo());
        }
        URI action = requireWxpayQrcodeAction(confirmed.providerTradeNo());
        return new PaymentCreateResult(
                confirmed.providerTradeNo(),
                "qrcode",
                action.toString(),
                true);
    }

    /**
     * 微信支付先由后端提交六号页面接口，再使用本地商户订单号执行签名查询确认。
     * 六号可能返回 2xx HTML、302 或 303，但这些响应都不是支付事实；只有签名查询确认真实流水、订单和金额后，
     * 才能绑定交易并从真实流水号独立构造二维码收银台地址。
     */
    private PaymentCreateResult createWxpayPayment(
            PaymentCheckoutCommand checkout,
            String clientIp) {
        Map<String, Object> fields = new LinkedHashMap<>(checkoutFields(checkout));
        fields.put("method", "web");
        fields.put("device", "pc");
        fields.put("clientip", clientIp);

        String httpStatus = "unavailable";
        String locationCount = "unavailable";
        String routeKind = "missing";
        boolean tradeNoPresent = false;
        String queryOutcome = "not_attempted";
        String outcome = "uncertain";
        String reason = "SUBMIT_NOT_COMPLETED";
        try {
            LiuhaoRedirectHttpResponse response = null;
            try {
                response = postSubmitWithoutRedirect(signatures.sign(fields));
            } catch (MembershipPaymentException exception) {
                reason = "SUBMIT_TRANSPORT_FAILED";
            }
            if (response != null) {
                httpStatus = httpStatusClass(response.status());
                locationCount = locationCountClass(response.locations().size());
                if (response.locations().size() == 1) {
                    routeKind = wxpayRedirectKind(
                            response.locations().getFirst(), properties.baseUrl());
                } else if (response.locations().size() > 1) {
                    routeKind = "ambiguous";
                }
            }

            WxpayRedirectCandidate candidate;
            if (response != null && response.locations().size() == 1) {
                try {
                    candidate = requireWxpayRedirectCandidate(
                            response.locations().getFirst(), properties.baseUrl());
                } catch (IllegalArgumentException exception) {
                    // Location 仅作诊断；不可信时仍可用本地商户订单号查询真实支付事实。
                    candidate = null;
                }
            } else {
                candidate = null;
            }

            PaymentQueryResult confirmed;
            try {
                // Location 只是未验签的导航元数据；查询验签成功前禁止把路径交易号写入数据库或 Redis。
                // 使用 out_trade_no 查询可覆盖 2xx 无 Location、302/303 以及提交超时等所有可能已落单的响应。
                confirmed = queryPayment(new PaymentQueryCommand(
                        checkout.orderId(), null));
            } catch (RuntimeException exception) {
                if (exception instanceof MembershipPaymentException paymentException
                        && paymentException.code()
                                == MembershipPaymentErrorCode.LIUHAO_ORDER_CONFLICT) {
                    queryOutcome = "rejected";
                    outcome = "rejected";
                    reason = "QUERY_ORDER_CONFLICT";
                    throw exception;
                }
                queryOutcome = "unknown";
                reason = "QUERY_CONFIRMATION_FAILED";
                throw createOutcomeUnknown(
                        "Liuhao wxpay transaction could not be confirmed safely.");
            }
            queryOutcome = "verified";
            String confirmedTradeNo = confirmed.providerTradeNo();
            tradeNoPresent = confirmedTradeNo != null;

            if (confirmed.amountYuan() == null
                    || confirmed.amountYuan().compareTo(requireAmount(checkout.amountYuan())) != 0) {
                queryOutcome = "rejected";
                outcome = "rejected";
                reason = "QUERY_AMOUNT_MISMATCH";
                // 金额不一致意味着第三方事实不能归属于当前订单；真实流水不得随异常进入业务层绑定。
                throw failure(
                        MembershipPaymentErrorCode.LIUHAO_ORDER_CONFLICT,
                        "Liuhao wxpay transaction amount does not match the local order.");
            }
            if (candidate != null && !Objects.equals(candidate.tradeNo(), confirmedTradeNo)) {
                queryOutcome = "rejected";
                outcome = "rejected";
                reason = "QUERY_TRADE_CONFLICT";
                throw failure(
                        MembershipPaymentErrorCode.LIUHAO_ORDER_CONFLICT,
                        "Liuhao wxpay redirect does not belong to the confirmed transaction.");
            }
            if (confirmed.status() != PaymentProviderStatus.PENDING
                    && confirmed.status() != PaymentProviderStatus.PAID) {
                queryOutcome = "rejected";
                outcome = "rejected";
                reason = "QUERY_STATUS_NOT_PAYABLE";
                throw checkoutUnavailableAfterCreation(
                        "Liuhao wxpay transaction is no longer payable.",
                        confirmedTradeNo);
            }

            if ("missing".equals(routeKind)) {
                routeKind = "derived_qrcode_page_url";
            }
            URI action;
            try {
                action = requireWxpayQrcodeAction(confirmedTradeNo);
            } catch (MembershipPaymentException exception) {
                queryOutcome = "rejected";
                outcome = "rejected";
                reason = "QRCODE_ROUTE_INVALID";
                throw exception;
            }
            outcome = "accepted";
            reason = "QUERY_CONFIRMED_QRCODE_DERIVED";
            return new PaymentCreateResult(
                    confirmedTradeNo,
                    "qrcode",
                    action.toString(),
                    true);
        } finally {
            MembershipPaymentLifecycleDiagnostics.liuhaoSubmitCheckoutResolution(
                    checkout.payType(),
                    httpStatus,
                    locationCount,
                    routeKind,
                    tradeNoPresent,
                    "out_trade_no",
                    queryOutcome,
                    outcome,
                    reason,
                    MembershipPaymentTraceContext.currentTraceId());
        }
    }

    @Override
    public PaymentQueryResult queryPayment(PaymentQueryCommand command) {
        PaymentQueryCommand value = Objects.requireNonNull(command);
        Map<String, Object> body = postVerified(QUERY_PATH, signedLocator(
                value.orderId(), value.providerTradeNo(), null, null));
        String orderId = requiredSafe(body, "out_trade_no", 128);
        String tradeNo = requiredSafe(body, "trade_no", 112);
        requireSameOrder(value.orderId(), value.providerTradeNo(), orderId, tradeNo);
        PaymentProviderStatus status = status(body);
        BigDecimal amount = optionalAmount(body, "money");
        String channelTradeNo = optionalSafe(body, "api_trade_no", 128);
        OffsetDateTime finishedAt = optionalTime(body);
        if (status == PaymentProviderStatus.PAID && finishedAt == null) {
            // 部分插件查询响应不返回完成时间；验签响应到达时刻仅作为保守的支付事实接收时间。
            finishedAt = MembershipPaymentTime.now(clock);
        }
        return new PaymentQueryResult(
                orderId, tradeNo, channelTradeNo, status, amount, finishedAt, null);
    }

    @Override
    public PaymentCloseResult closePayment(PaymentCloseCommand command) {
        PaymentCloseCommand value = Objects.requireNonNull(command);
        boolean tradeLocator = value.providerTradeNo() != null
                && !value.providerTradeNo().isBlank();
        MembershipPaymentLifecycleDiagnostics.liuhaoCloseClient(
                tradeLocator,
                "sent",
                "not_available",
                "not_available",
                "not_available",
                PaymentProviderStatus.UNKNOWN,
                tradeLocator,
                "not_required",
                "wait_callback_window",
                "BEFORE_CLOSING_DEADLINE");
        try {
            Map<String, Object> body = postVerified(CLOSE_PATH, signedLocator(
                    value.orderId(), value.providerTradeNo(), null, null));
            validateOptionalIdentity(body, value.orderId(), value.providerTradeNo());
            String returnedTradeNo = optionalSafe(body, "trade_no", 112);
            if (body.containsKey("status") || body.containsKey("trade_status")) {
                PaymentProviderStatus responseStatus = status(body);
                MembershipPaymentLifecycleDiagnostics.liuhaoCloseClient(
                        tradeLocator,
                        "sent",
                        "success",
                        "verified",
                        "0",
                        responseStatus,
                        returnedTradeNo != null,
                        "not_required",
                        closeNextAction(responseStatus),
                        closeReason(responseStatus));
                return new PaymentCloseResult(responseStatus, returnedTradeNo);
            }

            // code=0 只确认关单接口被平台受理；缺少状态字段时必须补查订单事实，禁止推断为 CLOSED。
            MembershipPaymentLifecycleDiagnostics.liuhaoCloseClient(
                    tradeLocator,
                    "sent",
                    "success",
                    "verified",
                    "0",
                    PaymentProviderStatus.UNKNOWN,
                    returnedTradeNo != null,
                    "unknown",
                    "retry_close",
                    "CLOSE_ACK_STATUS_MISSING");
            PaymentQueryResult confirmed = queryPayment(new PaymentQueryCommand(
                    value.orderId(), returnedTradeNo == null
                            ? value.providerTradeNo()
                            : returnedTradeNo));
            MembershipPaymentLifecycleDiagnostics.liuhaoCloseClient(
                    returnedTradeNo != null || tradeLocator,
                    "sent",
                    "success",
                    "verified",
                    "0",
                    confirmed.status(),
                    confirmed.providerTradeNo() != null,
                    followupOutcome(confirmed.status()),
                    closeNextAction(confirmed.status()),
                    closeReason(confirmed.status()));
            return new PaymentCloseResult(
                    confirmed.status(), confirmed.providerTradeNo());
        } catch (RuntimeException exception) {
            MembershipPaymentErrorCode code = exception instanceof MembershipPaymentException payment
                    ? payment.code()
                    : MembershipPaymentErrorCode.LIUHAO_UNAVAILABLE;
            String reason = code == MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID
                    ? "CLOSE_SIGNATURE_INVALID"
                    : code == MembershipPaymentErrorCode.LIUHAO_RESPONSE_INVALID
                            ? businessCodeRejected(exception)
                                    ? "CLOSE_BUSINESS_CODE_REJECTED"
                                    : "CLOSE_RESPONSE_INVALID"
                            : "CLOSE_REQUEST_FAILED";
            String providerCode = code == MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID
                    ? "untrusted"
                    : businessCodeRejected(exception)
                            ? "nonzero"
                            : "not_available";
            MembershipPaymentLifecycleDiagnostics.liuhaoCloseClient(
                    tradeLocator,
                    "failed",
                    "failed",
                    code == MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID
                            ? "failed" : "not_available",
                    providerCode,
                    PaymentProviderStatus.UNKNOWN,
                    tradeLocator,
                    "failed",
                    "retry_close",
                    reason);
            throw exception;
        }
    }

    private static String followupOutcome(PaymentProviderStatus status) {
        return switch (status) {
            case CLOSED, EXPIRED, FAILED, REFUNDED -> "confirmed_closed";
            case PENDING -> "still_pending";
            case PAID -> "paid";
            case UNKNOWN -> "unknown";
        };
    }

    private static String closeNextAction(PaymentProviderStatus status) {
        return switch (status) {
            case CLOSED, EXPIRED, FAILED, REFUNDED -> "wait_callback_window";
            case PAID -> "reconcile_paid";
            case PENDING, UNKNOWN -> "retry_close";
        };
    }

    private static String closeReason(PaymentProviderStatus status) {
        return switch (status) {
            case CLOSED, EXPIRED, FAILED, REFUNDED ->
                    "CLOSE_CONFIRMED_WAITING_CALLBACK_WINDOW";
            case PAID -> "PROVIDER_PAID_DURING_CLOSE";
            case PENDING -> "FOLLOWUP_QUERY_PENDING";
            case UNKNOWN -> "FOLLOWUP_QUERY_UNKNOWN";
        };
    }

    private static boolean businessCodeRejected(RuntimeException exception) {
        return exception instanceof MembershipPaymentException paymentException
                && paymentException.code() == MembershipPaymentErrorCode.LIUHAO_RESPONSE_INVALID
                && "Liuhao rejected the payment operation."
                        .equals(paymentException.getMessage());
    }

    @Override
    public PaymentRefundResult refundPayment(PaymentRefundCommand command) {
        PaymentRefundCommand value = Objects.requireNonNull(command);
        BigDecimal amount = requireAmount(value.amountYuan());
        String refundNo = "RF-" + requireOrderId(value.orderId());
        Map<String, Object> body = postVerified(REFUND_PATH, signedLocator(
                value.orderId(), value.providerTradeNo(), amount.toPlainString(), refundNo));
        validateOptionalIdentity(body, value.orderId(), value.providerTradeNo());
        String returnedRefundNo = optionalSafe(body, "out_refund_no", 128);
        if (returnedRefundNo == null) {
            returnedRefundNo = optionalSafe(body, "refund_no", 128);
        }
        if (returnedRefundNo != null && !refundNo.equals(returnedRefundNo)) {
            throw conflict("Liuhao refund response belongs to another refund.");
        }
        BigDecimal returnedAmount = optionalAmount(body, "money");
        if (returnedAmount == null) {
            returnedAmount = optionalAmount(body, "refund_money");
        }
        if (returnedAmount != null && returnedAmount.compareTo(amount) != 0) {
            throw invalid("Liuhao refund response amount is invalid.");
        }
        return new PaymentRefundResult(
                PaymentProviderStatus.REFUNDED,
                optionalSafe(body, "trade_no", 112),
                returnedRefundNo == null ? refundNo : returnedRefundNo,
                amount);
    }

    private Map<String, Object> checkoutFields(PaymentCheckoutCommand command) {
        Map<String, Object> fields = commonFields();
        fields.put("type", command.payType());
        fields.put("out_trade_no", command.orderId());
        fields.put("notify_url", properties.notifyUrl().toString());
        fields.put("return_url", properties.returnUrl().toString());
        fields.put("name", command.orderName());
        fields.put("money", requireAmount(command.amountYuan()).toPlainString());
        return fields;
    }

    private Map<String, Object> commonFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("pid", properties.pid());
        fields.put("timestamp", Long.toString(clock.instant().getEpochSecond()));
        return fields;
    }

    private Map<String, String> signedLocator(
            String orderId,
            String providerTradeNo,
            String money,
            String refundNo) {
        Map<String, Object> fields = commonFields();
        if (providerTradeNo != null && !providerTradeNo.isBlank()) {
            fields.put("trade_no", requireSafe(providerTradeNo, 112, "trade number"));
        } else {
            fields.put("out_trade_no", requireOrderId(orderId));
        }
        if (money != null) {
            fields.put("money", money);
        }
        if (refundNo != null) {
            fields.put("out_refund_no", refundNo);
        }
        return signatures.sign(fields);
    }

    private Map<String, Object> postVerified(String path, Map<String, String> request) {
        boolean succeeded = false;
        LiuhaoResponseObservation observation = new LiuhaoResponseObservation(path);
        try {
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            request.forEach(form::add);
            if (CLOSE_PATH.equals(path) || REFUND_PATH.equals(path)) {
                boolean locatorPresent = hasText(form.getFirst("trade_no"))
                        || hasText(form.getFirst("out_trade_no"));
                boolean operationFieldsPresent = !REFUND_PATH.equals(path)
                        || (hasText(form.getFirst("money"))
                                && hasText(form.getFirst("out_refund_no")));
                boolean requestFieldsValid = hasText(form.getFirst("pid"))
                        && hasText(form.getFirst("timestamp"))
                        && locatorPresent
                        && operationFieldsPresent
                        && "RSA".equals(form.getFirst("sign_type"))
                        && hasText(form.getFirst("sign"));
                boolean signatureVerified = requestFieldsValid
                        && signatures.verifyMerchantRequest(request);
                MembershipPaymentLifecycleDiagnostics.liuhaoRequestSignature(
                        operationName(path),
                        path,
                        hasText(form.getFirst("pid")),
                        hasText(form.getFirst("timestamp")),
                        hasText(form.getFirst("trade_no")),
                        hasText(form.getFirst("out_trade_no")),
                        hasText(form.getFirst("money")),
                        hasText(form.getFirst("out_refund_no")),
                        hasText(form.getFirst("sign_type")),
                        hasText(form.getFirst("sign")),
                        signTypeClass(form.getFirst("sign_type")),
                        "SHA256WithRSA",
                        "form_prepared",
                        requestFieldsValid,
                        signatureVerified ? "verified" : "failed",
                        MembershipPaymentTraceContext.currentTraceId());
                if (!requestFieldsValid || !signatureVerified) {
                    observation.failed("request_signature", "MERCHANT_SIGNATURE_SELF_CHECK_FAILED");
                    throw failure(
                            MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID,
                            "Liuhao request signature self-check failed.");
                }
            }
            LiuhaoHttpResponse response = restClient.post()
                    .uri(path)
                    .contentType(FORM_UTF8)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .exchange((outbound, upstream) -> {
                        int status = upstream.getStatusCode().value();
                        byte[] body = upstream.getBody().readNBytes(
                                properties.responseMaxBytes() + 1);
                        return new LiuhaoHttpResponse(
                                status,
                                contentTypeClass(upstream.getHeaders().getContentType()),
                                bodySizeBucket(body.length, properties.responseMaxBytes()),
                                body);
                    });
            if (response == null) {
                observation.failed("transport", "TRANSPORT_EMPTY_RESPONSE");
                throw failure(
                        MembershipPaymentErrorCode.LIUHAO_UNAVAILABLE,
                        "Liuhao returned no HTTP response.");
            }
            observation.observeHttp(response);
            if (response.body().length == 0
                    || response.body().length > properties.responseMaxBytes()) {
                observation.failed("response_size", "RESPONSE_SIZE_INVALID");
                throw invalid("Liuhao response size is invalid.");
            }
            if (response.status() == 401 || response.status() == 403) {
                observation.failed("transport", "HTTP_AUTH_REJECTED");
                throw failure(
                        MembershipPaymentErrorCode.LIUHAO_AUTH_FAILED,
                        "Liuhao rejected backend authentication.");
            }
            if (response.status() >= 500) {
                observation.failed("transport", "HTTP_UPSTREAM_UNAVAILABLE");
                throw failure(
                        MembershipPaymentErrorCode.LIUHAO_UNAVAILABLE,
                        "Liuhao is temporarily unavailable.");
            }
            if (response.status() < 200 || response.status() >= 300) {
                observation.failed("transport", "HTTP_STATUS_UNEXPECTED");
                throw invalid("Liuhao returned an unexpected HTTP status.");
            }

            Map<String, Object> body;
            try {
                body = readObject(response.body());
            } catch (IOException exception) {
                observation.failed("json_shape", "JSON_UNREADABLE");
                throw exception;
            }
            observation.observeJson(body);
            if (body == null || body.isEmpty()) {
                observation.failed("json_shape", "JSON_OBJECT_EMPTY");
                throw invalid("Liuhao returned an empty JSON object.");
            }
            if (containsNonScalarValue(body)) {
                observation.failed("json_shape", "JSON_FIELDS_NON_SCALAR");
                throw invalid("Liuhao response contains non-scalar fields.");
            }
            try {
                signatures.canonicalize(body);
            } catch (IllegalArgumentException exception) {
                observation.failed("json_shape", "JSON_FIELDS_INVALID");
                throw invalid("Liuhao response contains non-scalar fields.");
            }

            // 平台公钥验签和时间窗口校验必须先于 code 与任何订单字段读取。
            LiuhaoSignatureVerificationResult verification = signatures.verifyDetailed(body);
            if (!verification.verified()) {
                observation.signatureFailed(verification.reason());
                throw failure(
                        MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID,
                        "Liuhao response signature is invalid.");
            }
            observation.failed("timestamp_validation", "TIMESTAMP_INVALID");
            requireFreshTimestamp(requiredText(body, "timestamp", 10));
            String providerCode = providerCode(body.get("code"));
            observation.providerCode(providerCode);
            observation.providerCodeVerified();
            String pid = scalar(body.get("pid"));
            if (pid != null && !properties.pid().equals(pid)) {
                observation.failed("business_code", "MERCHANT_MISMATCH");
                throw invalid("Liuhao response merchant is invalid.");
            }
            // 六号 V2 的 code=0 仅表示本次接口调用成功；支付事实仍由查询状态字段单独裁决。
            if (!"0".equals(scalar(body.get("code")))) {
                observation.failed("business_code", "BUSINESS_CODE_REJECTED");
                throw invalid("Liuhao rejected the payment operation.");
            }
            observation.verified(providerCode);
            succeeded = true;
            return body;
        } catch (MembershipPaymentException exception) {
            metrics.providerFailure(
                    PaymentProviderType.LIUHAO, operationName(path), exception.code());
            throw exception;
        } catch (ResourceAccessException exception) {
            MembershipPaymentException failure;
            if (hasTimeoutCause(exception)) {
                observation.failed("transport", "TRANSPORT_TIMEOUT");
                failure = failure(
                        MembershipPaymentErrorCode.LIUHAO_TIMEOUT,
                        "Liuhao request timed out.",
                        exception);
            } else {
                observation.failed("transport", "TRANSPORT_UNAVAILABLE");
                failure = failure(
                        MembershipPaymentErrorCode.LIUHAO_UNAVAILABLE,
                        "Liuhao cannot be reached.");
            }
            metrics.providerFailure(
                    PaymentProviderType.LIUHAO, operationName(path), failure.code());
            throw failure;
        } catch (RestClientException exception) {
            observation.failed("transport", "TRANSPORT_FAILED");
            MembershipPaymentException failure =
                    invalid("Liuhao returned an unreadable response.");
            metrics.providerFailure(
                    PaymentProviderType.LIUHAO, operationName(path), failure.code());
            throw failure;
        } catch (IOException exception) {
            observation.failed("json_shape", "JSON_UNREADABLE");
            MembershipPaymentException failure =
                    invalid("Liuhao returned an unreadable response.");
            metrics.providerFailure(
                    PaymentProviderType.LIUHAO, operationName(path), failure.code());
            throw failure;
        } finally {
            observation.log();
            metrics.providerOperation(
                    PaymentProviderType.LIUHAO, operationName(path), succeeded);
        }
    }

    /**
     * 服务端提交微信表单时只读取 HTTP 状态和 Location；正文既不参与裁决也不进入内存或日志。
     * RestClient 已在专用配置中关闭自动重定向，因此这里观察到的是六号返回的第一跳。
     */
    private LiuhaoRedirectHttpResponse postSubmitWithoutRedirect(
            Map<String, String> request) {
        boolean succeeded = false;
        try {
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            request.forEach(form::add);
            LiuhaoRedirectHttpResponse response = restClient.post()
                    .uri(SUBMIT_PATH)
                    .contentType(FORM_UTF8)
                    .body(form)
                    .exchange((outbound, upstream) -> {
                        List<String> locations = upstream.getHeaders().get(HttpHeaders.LOCATION);
                        return new LiuhaoRedirectHttpResponse(
                                upstream.getStatusCode().value(),
                                locations == null ? List.of() : List.copyOf(locations),
                                contentTypeClass(upstream.getHeaders().getContentType()));
                    });
            if (response == null) {
                throw createOutcomeUnknown(
                        "Liuhao wxpay submission returned no HTTP response.");
            }
            succeeded = true;
            return response;
        } catch (MembershipPaymentException exception) {
            metrics.providerFailure(
                    PaymentProviderType.LIUHAO, "submit", exception.code());
            throw exception;
        } catch (ResourceAccessException exception) {
            MembershipPaymentException failure = createOutcomeUnknown(
                    "Liuhao wxpay submission outcome is unknown.");
            metrics.providerFailure(
                    PaymentProviderType.LIUHAO, "submit", failure.code());
            throw failure;
        } catch (RestClientException exception) {
            MembershipPaymentException failure = createOutcomeUnknown(
                    "Liuhao wxpay submission returned an unreadable response.");
            metrics.providerFailure(
                    PaymentProviderType.LIUHAO, "submit", failure.code());
            throw failure;
        } finally {
            metrics.providerOperation(PaymentProviderType.LIUHAO, "submit", succeeded);
        }
    }

    private static String operationName(String path) {
        return switch (path) {
            case CREATE_PATH -> "create";
            case SUBMIT_PATH -> "submit";
            case QUERY_PATH -> "query";
            case REFUND_PATH -> "refund";
            case CLOSE_PATH -> "close";
            default -> "unavailable";
        };
    }

    private Map<String, Object> readObject(byte[] body) throws IOException {
        return objectMapper.readValue(
                body, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private static boolean containsNonScalarValue(Map<String, Object> body) {
        return body.values().stream().anyMatch(value -> value != null
                && (value.getClass().isArray()
                        || value instanceof Iterable<?>
                        || value instanceof Map<?, ?>
                        || value instanceof org.springframework.core.io.Resource));
    }

    private static String contentTypeClass(MediaType contentType) {
        if (contentType == null) {
            return "missing";
        }
        return MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                ? "application_json"
                : "unexpected";
    }

    private static String bodySizeBucket(int size, int limit) {
        if (size == 0) {
            return "zero";
        }
        if (size > limit) {
            return "over_limit";
        }
        if (size <= 1_024) {
            return "le_1k";
        }
        if (size <= 4_096) {
            return "le_4k";
        }
        return "le_limit";
    }

    private static String httpStatusClass(int status) {
        if (status >= 100 && status < 200) {
            return "1xx";
        }
        if (status >= 200 && status < 300) {
            return "2xx";
        }
        if (status >= 300 && status < 400) {
            return "3xx";
        }
        if (status >= 400 && status < 500) {
            return "4xx";
        }
        if (status >= 500 && status < 600) {
            return "5xx";
        }
        return "unexpected";
    }

    private static String locationCountClass(int count) {
        if (count == 0) {
            return "zero";
        }
        return count == 1 ? "one" : "multiple";
    }

    private static boolean hasUnexpectedResponseField(
            String path,
            Map<String, Object> body) {
        Set<String> allowed = switch (path) {
            case CREATE_PATH -> CREATE_RESPONSE_FIELDS;
            case QUERY_PATH -> QUERY_RESPONSE_FIELDS;
            case REFUND_PATH -> REFUND_RESPONSE_FIELDS;
            case CLOSE_PATH -> CLOSE_RESPONSE_FIELDS;
            default -> COMMON_RESPONSE_FIELDS;
        };
        return body.keySet().stream().anyMatch(name -> !allowed.contains(name));
    }

    private static String jsonType(Object value) {
        if (value == null) {
            return "missing";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof String) {
            return "string";
        }
        return "unexpected";
    }

    private static String messageCharacterClass(Object value) {
        if (value == null) {
            return "missing";
        }
        if (!(value instanceof String text)) {
            return "unexpected";
        }
        return text.chars().allMatch(character -> character <= 0x7f)
                ? "ascii"
                : "non_ascii";
    }

    private static String messageWhitespaceProfile(Object value) {
        if (value == null) {
            return "missing";
        }
        if (!(value instanceof String text)) {
            return "unexpected";
        }
        boolean leading = !text.equals(text.stripLeading());
        boolean trailing = !text.equals(text.stripTrailing());
        if (leading && trailing) {
            return "both";
        }
        if (leading) {
            return "leading";
        }
        return trailing ? "trailing" : "none";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String signTypeClass(Object value) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            return "missing";
        }
        return value instanceof String text && "RSA".equals(text)
                ? "rsa"
                : "unexpected";
    }

    private static String verificationStage(LiuhaoSignatureVerificationReason reason) {
        return switch (reason) {
            case SIGN_TYPE_MISSING, SIGN_TYPE_UNEXPECTED, SIGN_MISSING ->
                    "signature_metadata";
            case SIGN_BASE64_INVALID -> "signature_encoding";
            case CANONICAL_FIELDS_UNEXPECTED -> "canonicalization";
            case PLATFORM_SIGNATURE_MISMATCH, CRYPTO_VERIFIER_UNAVAILABLE ->
                    "rsa_verification";
            case VERIFIED -> "complete";
        };
    }

    private static String providerCode(Object value) {
        if (value instanceof Number number) {
            return number.longValue() == 0L ? "0" : "nonzero";
        }
        if (value instanceof String text) {
            return "0".equals(text) ? "0" : "nonzero";
        }
        return value == null ? "missing" : "unexpected";
    }

    // 业务码来自不可信上游；仅保留有限整数范围供诊断，避免高基数或原文进入日志。
    private static String providerCodeNumeric(Object value) {
        if (value == null) {
            return "missing";
        }
        try {
            long code;
            if (value instanceof Number number) {
                BigDecimal decimal = new BigDecimal(number.toString());
                code = decimal.longValueExact();
            } else if (value instanceof String text && text.matches("^-?[0-9]+$")) {
                code = Long.parseLong(text);
            } else {
                return "non_numeric";
            }
            return code >= 0L && code <= 999_999L
                    ? Long.toString(code)
                    : "out_of_range";
        } catch (ArithmeticException | NumberFormatException exception) {
            return "out_of_range";
        }
    }

    private record LiuhaoHttpResponse(
            int status,
            String contentTypeClass,
            String bodySizeBucket,
            byte[] body) {
    }

    /**
     * 该私有观测对象只保存固定布尔值与分类桶，确保 finally 能输出唯一诊断事件而不持有响应原文。
     */
    private static final class LiuhaoResponseObservation {

        private final String path;
        private String httpOutcome = "failed";
        private String httpStatusClass = "not_available";
        private String contentType = "missing";
        private String bodySizeBucket = "zero";
        private String jsonShape = "not_available";
        private boolean hasCode;
        private boolean hasMsg;
        private boolean hasTimestamp;
        private boolean hasSign;
        private boolean hasSignType;
        private boolean hasPid;
        private boolean hasTradeNo;
        private boolean hasOutTradeNo;
        private boolean hasStatus;
        private boolean hasTradeStatus;
        private boolean unexpectedFieldPresent;
        private String codeJsonType = "missing";
        private String msgCharacterClass = "missing";
        private String msgWhitespaceProfile = "missing";
        private String signTypeClass = "missing";
        private String verificationStage = "transport";
        private String verificationOutcome = "failed";
        private String reason = "TRANSPORT_FAILED";
        private String providerCode = "untrusted";
        private String providerCodeNumeric = "missing";
        private String providerCodeTrust = "untrusted";

        private LiuhaoResponseObservation(String path) {
            this.path = path;
        }

        private void observeHttp(LiuhaoHttpResponse response) {
            httpOutcome = response.status() >= 200 && response.status() < 300
                    ? "success"
                    : "failed";
            httpStatusClass = LiuhaoPaymentRestClientImpl.httpStatusClass(response.status());
            contentType = response.contentTypeClass();
            bodySizeBucket = response.bodySizeBucket();
            verificationStage = "response_size";
            reason = "RESPONSE_NOT_VERIFIED";
        }

        private void observeJson(Map<String, Object> body) {
            if (body == null || body.isEmpty()) {
                jsonShape = "empty_object";
                return;
            }
            jsonShape = containsNonScalarValue(body)
                    ? "non_scalar_object"
                    : "scalar_object";
            hasCode = body.containsKey("code");
            hasMsg = body.containsKey("msg");
            hasTimestamp = body.containsKey("timestamp");
            hasSign = body.containsKey("sign");
            hasSignType = body.containsKey("sign_type");
            hasPid = body.containsKey("pid");
            hasTradeNo = body.containsKey("trade_no");
            hasOutTradeNo = body.containsKey("out_trade_no");
            hasStatus = body.containsKey("status");
            hasTradeStatus = body.containsKey("trade_status");
            unexpectedFieldPresent = hasUnexpectedResponseField(path, body);
            codeJsonType = jsonType(body.get("code"));
            providerCodeNumeric = LiuhaoPaymentRestClientImpl.providerCodeNumeric(body.get("code"));
            msgCharacterClass = messageCharacterClass(body.get("msg"));
            msgWhitespaceProfile = messageWhitespaceProfile(body.get("msg"));
            signTypeClass = LiuhaoPaymentRestClientImpl.signTypeClass(body.get("sign_type"));
        }

        private void signatureFailed(LiuhaoSignatureVerificationReason failure) {
            verificationStage = LiuhaoPaymentRestClientImpl.verificationStage(failure);
            verificationOutcome = "failed";
            reason = failure.name();
            providerCode = "untrusted";
        }

        private void providerCode(String normalizedCode) {
            providerCode = normalizedCode;
        }

        private void providerCodeVerified() {
            providerCodeTrust = "verified";
        }

        private void failed(String stage, String failureReason) {
            verificationStage = stage;
            verificationOutcome = "failed";
            reason = failureReason;
            if (!"business_code".equals(stage)) {
                providerCode = "untrusted";
            }
        }

        private void verified(String normalizedCode) {
            verificationStage = "complete";
            verificationOutcome = "verified";
            reason = "VERIFIED";
            providerCode = normalizedCode;
        }

        private void log() {
            MembershipPaymentLifecycleDiagnostics.liuhaoResponseVerification(
                    operationName(path),
                    httpOutcome,
                    httpStatusClass,
                    contentType,
                    bodySizeBucket,
                    jsonShape,
                    hasCode,
                    hasMsg,
                    hasTimestamp,
                    hasSign,
                    hasSignType,
                    hasPid,
                    hasTradeNo,
                    hasOutTradeNo,
                    hasStatus,
                    hasTradeStatus,
                    unexpectedFieldPresent,
                    codeJsonType,
                    msgCharacterClass,
                    msgWhitespaceProfile,
                    signTypeClass,
                    verificationStage,
                    verificationOutcome,
                    reason,
                    providerCode,
                    providerCodeNumeric,
                    providerCodeTrust,
                    MembershipPaymentTraceContext.currentTraceId());
        }
    }

    private PaymentCheckoutCommand requireCheckout(PaymentCheckoutCommand command) {
        PaymentCheckoutCommand value = Objects.requireNonNull(command);
        requireOrderId(value.orderId());
        if (!("alipay".equals(value.payType()) || "wxpay".equals(value.payType()))) {
            throw new IllegalArgumentException("Liuhao payment type is invalid.");
        }
        requireSafe(value.orderName(), 64, "order name");
        requireAmount(value.amountYuan());
        return value;
    }

    private void requireSameOrder(
            String expectedOrderId,
            String expectedTradeNo,
            String actualOrderId,
            String actualTradeNo) {
        if (!Objects.equals(expectedOrderId, actualOrderId)
                || (expectedTradeNo != null && !Objects.equals(expectedTradeNo, actualTradeNo))) {
            throw conflict("Liuhao response belongs to another order.");
        }
    }

    private void validateOptionalIdentity(
            Map<String, Object> body,
            String expectedOrderId,
            String expectedTradeNo) {
        String orderId = optionalSafe(body, "out_trade_no", 128);
        String tradeNo = optionalSafe(body, "trade_no", 112);
        if ((orderId != null && !Objects.equals(expectedOrderId, orderId))
                || (expectedTradeNo != null && tradeNo != null
                        && !Objects.equals(expectedTradeNo, tradeNo))) {
            throw conflict("Liuhao response belongs to another order.");
        }
    }

    private void validateOptionalCreateEcho(
            Map<String, Object> body,
            PaymentCheckoutCommand command) {
        String returnedType = optionalSafe(body, "type", 16);
        if (returnedType != null && !command.payType().equals(returnedType)) {
            throw conflict("Liuhao create response uses another payment type.");
        }
        BigDecimal returnedAmount = optionalAmount(body, "money");
        if (returnedAmount != null
                && returnedAmount.compareTo(requireAmount(command.amountYuan())) != 0) {
            throw conflict("Liuhao create response amount belongs to another order.");
        }
    }

    private PaymentProviderStatus status(Map<String, Object> body) {
        String tradeStatus = scalar(body.get("trade_status"));
        if ("TRADE_SUCCESS".equals(tradeStatus)) {
            return PaymentProviderStatus.PAID;
        }
        if ("TRADE_CLOSED".equals(tradeStatus)) {
            return PaymentProviderStatus.CLOSED;
        }
        return switch (requiredText(body, "status", 16)) {
            case "0" -> PaymentProviderStatus.PENDING;
            case "1" -> PaymentProviderStatus.PAID;
            case "2" -> PaymentProviderStatus.REFUNDED;
            default -> PaymentProviderStatus.UNKNOWN;
        };
    }

    private void requireFreshTimestamp(String raw) {
        try {
            long epochSecond = Long.parseLong(raw);
            if (raw.length() != 10 || epochSecond <= 0
                    || Duration.between(Instant.ofEpochSecond(epochSecond), clock.instant())
                            .abs().compareTo(properties.timestampTolerance()) > 0) {
                throw invalid("Liuhao response timestamp is outside the accepted window.");
            }
        } catch (NumberFormatException exception) {
            throw invalid("Liuhao response timestamp is invalid.");
        }
    }

    private OffsetDateTime optionalTime(Map<String, Object> body) {
        for (String field : new String[] {"finished_at", "endtime", "end_time"}) {
            String raw = scalar(body.get(field));
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                if (raw.matches("^[0-9]{10}$")) {
                    return MembershipPaymentTime.fromInstant(Instant.ofEpochSecond(Long.parseLong(raw)));
                }
                try {
                    return OffsetDateTime.parse(raw);
                } catch (DateTimeParseException ignored) {
                    return LocalDateTime.parse(raw, LEGACY_TIME).atOffset(ZoneOffset.ofHours(8));
                }
            } catch (RuntimeException exception) {
                throw invalid("Liuhao completion time is invalid.");
            }
        }
        return null;
    }

    private URI approvedAction(String path) {
        URI action = properties.baseUrl().resolve(path);
        if (!"https".equalsIgnoreCase(action.getScheme())
                || !properties.baseUrl().getHost().equalsIgnoreCase(action.getHost())
                || action.getPort() != -1
                || action.getUserInfo() != null
                || action.getRawQuery() != null
                || action.getFragment() != null
                || !path.equals(action.getPath())) {
            throw invalid("Liuhao checkout action is outside the approved origin.");
        }
        return action;
    }

    private static String requireClientIp(String value) {
        try {
            String canonical = IpAddressIdentity.parse(value).canonicalText();
            if (!canonical.equals(value)) {
                throw new IllegalArgumentException("Liuhao client IP is not canonical.");
            }
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Liuhao client IP is invalid.");
        }
    }

    private static String requireRedirectUrl(String value) {
        if (value.length() > MAX_REDIRECT_URL_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Liuhao redirect URL is invalid.");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw invalid("Liuhao redirect URL is invalid.");
        }
        // 该地址已经通过六号平台公钥验签；这里只限制安全导航语义，不额外硬编码支付通道域名。
        if (!uri.isAbsolute()
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null) {
            throw invalid("Liuhao redirect URL is invalid.");
        }
        return value;
    }

    /**
     * 只使用已经验签并完成订单、金额校验的真实交易号生成六号二维码入口；该地址从不读取或改写未验签的 Location。
     */
    private URI requireWxpayQrcodeAction(String tradeNo) {
        if (tradeNo == null
                || tradeNo.isBlank()
                || ".".equals(tradeNo)
                || "..".equals(tradeNo)
                || !SAFE_REFERENCE.matcher(tradeNo).matches()) {
            throw checkoutUnavailableAfterCreation(
                    "Liuhao wxpay trade number is invalid.",
                    tradeNo);
        }
        String path = "/pay/qrcode/" + tradeNo + "/";
        URI baseUrl = properties.baseUrl();
        if (baseUrl == null) {
            throw checkoutUnavailableAfterCreation(
                    "Liuhao wxpay QR checkout origin is unavailable.",
                    tradeNo);
        }
        URI action;
        try {
            action = baseUrl.resolve(path);
        } catch (IllegalArgumentException exception) {
            throw checkoutUnavailableAfterCreation(
                    "Liuhao wxpay QR checkout URL is invalid.",
                    tradeNo);
        }
        if (!"https".equalsIgnoreCase(baseUrl.getScheme())
                || baseUrl.getHost() == null
                || !"https".equalsIgnoreCase(action.getScheme())
                || action.getHost() == null
                || !baseUrl.getHost().equalsIgnoreCase(action.getHost())
                || action.getPort() != -1
                || action.getUserInfo() != null
                || action.getRawQuery() != null
                || action.getFragment() != null
                || !path.equals(action.getRawPath())) {
            throw checkoutUnavailableAfterCreation(
                    "Liuhao wxpay QR checkout URL is outside the approved origin.",
                    tradeNo);
        }
        return action;
    }

    /**
     * 从六号第一跳 Location 中提取候选交易号；这里只确认导航结构，交易事实仍必须由后续签名查询证明。
     */
    private static WxpayRedirectCandidate requireWxpayRedirectCandidate(
            String value,
            URI approvedOrigin) {
        URI action = resolveWxpayLocation(value, approvedOrigin);
        if (!"https".equalsIgnoreCase(action.getScheme())
                || action.getHost() == null
                || !approvedOrigin.getHost().equalsIgnoreCase(action.getHost())
                || action.getPort() != -1
                || action.getUserInfo() != null
                || action.getRawQuery() != null
                || action.getFragment() != null
                || !action.normalize().equals(action)) {
            throw new IllegalArgumentException("Liuhao wxpay redirect is outside the approved origin.");
        }

        String rawPath = action.getRawPath();
        String routeKind;
        String prefix;
        if (rawPath != null && rawPath.startsWith("/pay/qrcode/")) {
            routeKind = "qrcode_page_url";
            prefix = "/pay/qrcode/";
        } else if (rawPath != null && rawPath.startsWith("/pay/jspay/")) {
            routeKind = "jspay_page_url";
            prefix = "/pay/jspay/";
        } else {
            throw new IllegalArgumentException("Liuhao wxpay redirect route is unsupported.");
        }

        String tradeNo = rawPath.substring(prefix.length());
        if (tradeNo.endsWith("/")) {
            tradeNo = tradeNo.substring(0, tradeNo.length() - 1);
        }
        if (tradeNo.isBlank()
                || tradeNo.contains("/")
                || ".".equals(tradeNo)
                || "..".equals(tradeNo)
                || !SAFE_REFERENCE.matcher(tradeNo).matches()) {
            throw new IllegalArgumentException("Liuhao wxpay redirect trade number is invalid.");
        }

        String expectedPath = prefix + tradeNo;
        if (!expectedPath.equals(rawPath) && !(expectedPath + "/").equals(rawPath)) {
            throw new IllegalArgumentException("Liuhao wxpay redirect path is invalid.");
        }
        return new WxpayRedirectCandidate(action, tradeNo, routeKind);
    }

    private static URI resolveWxpayLocation(String value, URI approvedOrigin) {
        if (value == null
                || value.isBlank()
                || value.length() > MAX_REDIRECT_URL_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Liuhao wxpay redirect is invalid.");
        }
        try {
            URI candidate = URI.create(value);
            // 只允许绝对地址或根相对 Location；禁止把无根相对路径拼到未知基路径上。
            if (!candidate.isAbsolute() && !value.startsWith("/")) {
                throw new IllegalArgumentException("Liuhao wxpay redirect must be root-relative.");
            }
            return candidate.isAbsolute() ? candidate : approvedOrigin.resolve(candidate);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Liuhao wxpay redirect is invalid.");
        }
    }

    private static String wxpayRedirectKind(String value, URI approvedOrigin) {
        if (value == null || value.isBlank()) {
            return "missing";
        }
        URI action;
        try {
            action = resolveWxpayLocation(value, approvedOrigin);
        } catch (IllegalArgumentException exception) {
            return "invalid_url";
        }
        if (isWechatOauthUrl(action.toString())) {
            return "wechat_oauth_url";
        }
        boolean approvedHost = action.getHost() != null
                && approvedOrigin.getHost().equalsIgnoreCase(action.getHost());
        if (approvedHost && action.getRawPath() != null
                && action.getRawPath().startsWith("/pay/qrcode/")) {
            return "qrcode_page_url";
        }
        if (approvedHost && action.getRawPath() != null
                && action.getRawPath().startsWith("/pay/jspay/")) {
            return "jspay_page_url";
        }
        return "https".equalsIgnoreCase(action.getScheme())
                ? "other_https_url"
                : "invalid_url";
    }

    private static BigDecimal requireAmount(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.scale() > 2
                || value.compareTo(new BigDecimal("100000.00")) > 0) {
            throw new IllegalArgumentException("Liuhao payment amount is invalid.");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal optionalAmount(Map<String, Object> body, String name) {
        String raw = scalar(body.get(name));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return requireAmount(new BigDecimal(raw));
        } catch (IllegalArgumentException exception) {
            throw invalid("Liuhao response amount is invalid.");
        }
    }

    private static String requireOrderId(String value) {
        if (value == null || !ORDER_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Liuhao merchant order number is invalid.");
        }
        return value;
    }

    private static String requiredSafe(Map<String, Object> body, String name, int maxLength) {
        return requireSafe(requiredText(body, name, maxLength), maxLength, name);
    }

    private static String optionalSafe(Map<String, Object> body, String name, int maxLength) {
        String value = scalar(body.get(name));
        return value == null ? null : requireSafe(value, maxLength, name);
    }

    /**
     * 只为诊断提取标量文本；不复用严格业务字段校验，避免日志分支再次改变下单裁决。
     */
    private static String payloadScalar(Map<String, Object> body, String name) {
        Object value = body.get(name);
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return String.valueOf(value);
        }
        return null;
    }

    private static boolean payloadPresent(Map<String, Object> body, String name) {
        String value = payloadScalar(body, name);
        return value != null && !value.isBlank();
    }

    private static String createPayTypeClass(Map<String, Object> body) {
        String value = payloadScalar(body, "pay_type");
        if (value == null || value.isBlank()) {
            return "missing";
        }
        if (value.length() > 32 || value.chars().anyMatch(Character::isISOControl)) {
            return "invalid";
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String createPayInfoKind(
            String payTypeClass,
            String payInfo,
            URI approvedOrigin) {
        if (payInfo == null || payInfo.isBlank()) {
            return "missing";
        }
        if (isLiuhaoRoute(payInfo, approvedOrigin, "/pay/qrcode/")) {
            return "qrcode_page_url";
        }
        if (isLiuhaoRoute(payInfo, approvedOrigin, "/pay/jspay/")) {
            return "jspay_page_url";
        }
        if (isWechatOauthUrl(payInfo)) {
            return "wechat_oauth_url";
        }
        if (isHttpsRedirectCandidate(payInfo)) {
            return "other_https_url";
        }
        if ("qrcode".equals(payTypeClass)) {
            return isQrcodePayloadCandidate(payInfo) ? "qr_payload" : "invalid_url";
        }
        return "invalid_url";
    }

    private static boolean isLiuhaoRoute(
            String value,
            URI approvedOrigin,
            String pathPrefix) {
        try {
            URI uri = URI.create(value);
            return uri.isAbsolute()
                    && "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && approvedOrigin.getHost().equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == -1
                    && uri.getUserInfo() == null
                    && uri.getRawPath() != null
                    && uri.getRawPath().startsWith(pathPrefix);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isWechatOauthUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.isAbsolute()
                    && "https".equalsIgnoreCase(uri.getScheme())
                    && "open.weixin.qq.com".equalsIgnoreCase(uri.getHost())
                    && "/connect/oauth2/authorize".equals(uri.getRawPath());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isBrowserRedirectCarrier(String payType) {
        return "jump".equals(payType) || "qrcode".equals(payType);
    }

    private static boolean isQrcodePayloadCandidate(String value) {
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute()) {
                return true;
            }
            String scheme = uri.getScheme();
            return "weixin".equalsIgnoreCase(scheme)
                    || "wxp".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isHttpsRedirectCandidate(String value) {
        if (value.length() > MAX_REDIRECT_URL_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return uri.isAbsolute()
                    && "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isBlank()
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean payloadTypeMatches(Map<String, Object> body, String expectedType) {
        return Objects.equals(expectedType, payloadScalar(body, "type"));
    }

    private static boolean payloadAmountMatches(Map<String, Object> body, BigDecimal expectedAmount) {
        String value = payloadScalar(body, "money");
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return new BigDecimal(value).compareTo(requireAmount(expectedAmount)) == 0;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String requireSafe(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl)
                || ((name.contains("trade") || name.contains("reference"))
                        && !SAFE_REFERENCE.matcher(value).matches())) {
            throw new IllegalArgumentException("Liuhao " + name + " is invalid.");
        }
        return value;
    }

    private static String requiredText(Map<String, Object> body, String name, int maxLength) {
        String value = scalar(body.get(name));
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw invalid("Liuhao response field is invalid.");
        }
        return value;
    }

    private static String scalar(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return String.valueOf(value);
        }
        throw invalid("Liuhao response contains a non-scalar field.");
    }

    private static boolean hasTimeoutCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static MembershipPaymentException invalid(String message) {
        return failure(MembershipPaymentErrorCode.LIUHAO_RESPONSE_INVALID, message);
    }

    private static MembershipPaymentException conflict(String message) {
        return failure(MembershipPaymentErrorCode.LIUHAO_ORDER_CONFLICT, message);
    }

    private static MembershipPaymentException failure(
            MembershipPaymentErrorCode code,
            String message) {
        return new MembershipPaymentException(code, message);
    }

    private static MembershipPaymentException failure(
            MembershipPaymentErrorCode code,
            String message,
            Throwable cause) {
        return new MembershipPaymentException(code, message, cause);
    }

    private static MembershipPaymentException failure(
            MembershipPaymentErrorCode code,
            String message,
            String providerTradeNo) {
        return new MembershipPaymentException(code, message, providerTradeNo);
    }

    private static MembershipPaymentException checkoutUnavailableAfterCreation(
            String message,
            String providerTradeNo) {
        return failure(
                MembershipPaymentErrorCode.LIUHAO_CHECKOUT_UNAVAILABLE,
                message,
                providerTradeNo);
    }

    private static MembershipPaymentException createOutcomeUnknown(String message) {
        return failure(MembershipPaymentErrorCode.LIUHAO_CREATE_OUTCOME_UNKNOWN, message);
    }

    /** 保存禁止自动跟随的六号页面提交首跳元数据，不包含响应正文。 */
    private record LiuhaoRedirectHttpResponse(
            int status,
            List<String> locations,
            String contentTypeClass) {
    }

    /** 保存尚待签名查询确认的微信导航候选；确认前禁止持久化其中的交易号。 */
    private record WxpayRedirectCandidate(URI action, String tradeNo, String routeKind) {
    }
}
