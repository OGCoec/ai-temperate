package com.example.temperate.service.user.membership.payment.provider.bar.impl;

import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutMode;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutSubmission;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutSubmissionFields;
import com.example.temperate.service.user.membership.payment.provider.PaymentCreateCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCreateResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundResult;
import com.example.temperate.service.user.membership.payment.provider.bar.BarPaymentClient;
import com.example.temperate.service.user.membership.payment.provider.bar.BarPaymentSignatureService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 该实现是来通过固定 RestClient 发送 BAR 签名 JSON，并在构造领域结果前限制正文、校验字段类型和响应签名。
 *
 * <p>错误正文中的 msg 和签名均不会进入异常文本或日志；HTTP 409 只在关闭和退款流程内主动反查。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment.bar",
        name = "enabled",
        havingValue = "true")
public final class BarPaymentRestClientImpl implements BarPaymentClient {

    private static final String CREATE_PATH = "/api/pay/create";
    private static final String SUBMIT_PATH = "/api/pay/submit";
    private static final String QUERY_PATH = "/api/pay/query";
    private static final String CLOSE_PATH = "/api/pay/close";
    private static final String REFUND_PATH = "/api/pay/refund";
    private static final String SIGN_TYPE = "HMAC-SHA256";
    private static final Duration RESPONSE_TIMESTAMP_TOLERANCE = Duration.ofMinutes(5);
    private static final Duration SUBMIT_SIGNATURE_VALIDITY = Duration.ofMinutes(5);
    private static final Pattern TRADE_NUMBER = Pattern.compile("^[0-9]{1,20}$");
    private static final Pattern SAFE_CHANNEL_TRADE =
            Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Set<String> CREATE_FIELDS = Set.of(
            "code", "msg", "trade_no", "out_trade_no", "expires_at", "created",
            "timestamp", "key_version", "sign_type", "sign");
    private static final Set<String> QUERY_FIELDS = Set.of(
            "code", "msg", "pid", "trade_no", "out_trade_no", "status",
            "trade_status", "notify_status", "money", "created_at", "finished_at",
            "api_trade_no", "timestamp", "key_version", "sign_type", "sign");
    private static final Set<String> REFUND_FIELDS = Set.of(
            "code", "msg", "pid", "trade_no", "out_trade_no", "status",
            "trade_status", "notify_status", "money", "created_at", "finished_at",
            "api_trade_no", "refund_no", "refund_amount", "timestamp", "key_version",
            "sign_type", "sign");
    private static final Set<String> NOTIFY_STATUSES = Set.of(
            "NOT_SENT", "READY", "PROCESSING", "SUCCESS", "FAILED", "UNKNOWN");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final BarPaymentSignatureService signatures;
    private final MembershipPaymentProperties.Bar properties;
    private final Clock clock;

    @Autowired
    public BarPaymentRestClientImpl(
            @Qualifier("barPaymentRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            BarPaymentSignatureService signatures,
            MembershipPaymentProperties membershipPaymentProperties,
            Clock clock) {
        this(
                restClient,
                objectMapper,
                signatures,
                Objects.requireNonNull(membershipPaymentProperties).bar(),
                clock);
    }

    /** 该构造器只供同包客户端契约测试注入 BAR 配置，不参与 Spring Bean 选择。 */
    BarPaymentRestClientImpl(
            RestClient restClient,
            ObjectMapper objectMapper,
            BarPaymentSignatureService signatures,
            MembershipPaymentProperties.Bar bar,
            Clock clock) {
        this.restClient = Objects.requireNonNull(restClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.signatures = Objects.requireNonNull(signatures);
        this.properties = Objects.requireNonNull(bar);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public PaymentCheckoutResult createCheckout(PaymentCheckoutCommand command) {
        PaymentCheckoutCommand value = Objects.requireNonNull(command);
        // create 和 submit 必须复用同一组订单业务字段，否则 BAR 会把同一幂等键判定为关键参数冲突。
        Map<String, Object> checkoutFields = checkoutBusinessFields(value);
        Map<String, String> request = signedCheckoutRequest(checkoutFields, clock.instant());
        HttpJsonResponse response;
        try {
            response = post(CREATE_PATH, request, Set.of(200, 201), true);
        } catch (BarConflictException exception) {
            throw failure(
                    MembershipPaymentErrorCode.BAR_ORDER_CONFLICT,
                    "BAR rejected a conflicting idempotent create request.");
        }
        Map<String, Object> body = verified(response.body(), CREATE_FIELDS);
        boolean created = requiredBoolean(body, "created");
        if ((response.status() == HttpStatus.CREATED.value()) != created) {
            throw invalid("BAR create status does not match its created flag.");
        }
        String orderId = requiredString(body, "out_trade_no", 128, false);
        if (!Objects.equals(value.orderId(), orderId)) {
            throw conflict("BAR create response belongs to another order.");
        }
        String tradeNo = requiredTradeNo(body, "trade_no");
        OffsetDateTime expiresAt = requiredTime(body, "expires_at", false);
        if (!expiresAt.toInstant().isAfter(clock.instant())) {
            throw invalid("BAR create response checkout expiry is invalid.");
        }
        PaymentCheckoutSubmission submission = checkoutSubmission(checkoutFields, expiresAt);
        return new PaymentCheckoutResult(tradeNo, expiresAt, created, submission);
    }

    @Override
    public PaymentCreateResult createPayment(PaymentCreateCommand command) {
        PaymentCreateCommand value = Objects.requireNonNull(command);
        PaymentCheckoutResult checkout = createCheckout(new PaymentCheckoutCommand(
                value.orderId(), value.amountYuan(), value.payType(), value.orderName()));
        return new PaymentCreateResult(
                checkout.providerTradeNo(), value.payType(), null, checkout.created());
    }

    @Override
    public PaymentQueryResult queryPayment(PaymentQueryCommand command) {
        PaymentQueryCommand value = Objects.requireNonNull(command);
        Map<String, String> request = signedLocatorRequest(
                value.orderId(), value.providerTradeNo(), null);
        HttpJsonResponse response = post(QUERY_PATH, request, Set.of(200), false);
        return queryResult(value, verified(response.body(), QUERY_FIELDS));
    }

    @Override
    public PaymentCloseResult closePayment(PaymentCloseCommand command) {
        PaymentCloseCommand value = Objects.requireNonNull(command);
        Map<String, String> request = signedLocatorRequest(
                value.orderId(), value.providerTradeNo(), null);
        try {
            HttpJsonResponse response = post(CLOSE_PATH, request, Set.of(200), true);
            PaymentQueryResult closed = queryResult(
                    new PaymentQueryCommand(value.orderId(), value.providerTradeNo()),
                    verified(response.body(), QUERY_FIELDS));
            return new PaymentCloseResult(closed.status(), closed.providerTradeNo());
        } catch (BarConflictException exception) {
            PaymentQueryResult current = queryPayment(
                    new PaymentQueryCommand(value.orderId(), value.providerTradeNo()));
            return new PaymentCloseResult(current.status(), current.providerTradeNo());
        }
    }

    @Override
    public PaymentRefundResult refundPayment(PaymentRefundCommand command) {
        PaymentRefundCommand value = Objects.requireNonNull(command);
        BigDecimal amount = requireAmount(value.amountYuan());
        Map<String, String> request = signedLocatorRequest(
                value.orderId(), value.providerTradeNo(), amount.toPlainString());
        try {
            HttpJsonResponse response = post(REFUND_PATH, request, Set.of(200), true);
            Map<String, Object> body = verified(response.body(), REFUND_FIELDS);
            PaymentQueryResult refunded = queryResult(
                    new PaymentQueryCommand(value.orderId(), value.providerTradeNo()), body);
            BigDecimal refundAmount = requiredAmount(body, "refund_amount");
            String refundNo = requiredString(body, "refund_no", 128, false);
            if (refunded.status() != PaymentProviderStatus.REFUNDED
                    || amount.compareTo(refunded.amountYuan()) != 0
                    || amount.compareTo(refundAmount) != 0) {
                throw invalid("BAR refund response does not confirm the requested full refund.");
            }
            return new PaymentRefundResult(
                    refunded.status(), refunded.providerTradeNo(), refundNo, refundAmount);
        } catch (BarConflictException exception) {
            PaymentQueryResult current = queryPayment(
                    new PaymentQueryCommand(value.orderId(), value.providerTradeNo()));
            return new PaymentRefundResult(
                    current.status(), current.providerTradeNo(), null,
                    current.status() == PaymentProviderStatus.REFUNDED
                            ? current.amountYuan()
                            : null);
        }
    }

    private Map<String, Object> checkoutBusinessFields(PaymentCheckoutCommand command) {
        BigDecimal amount = requireAmount(command.amountYuan());
        if (command.orderId() == null
                || command.orderId().length() != 22
                || !("alipay".equals(command.payType()) || "wxpay".equals(command.payType()))
                || !"会员模拟支付订单".equals(command.orderName())) {
            throw new IllegalArgumentException("BAR checkout command is invalid.");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("out_trade_no", command.orderId());
        fields.put("type", command.payType());
        fields.put("name", command.orderName());
        fields.put("money", amount.toPlainString());
        fields.put("notify_url", properties.notifyUrl().toString());
        fields.put("return_url", properties.returnUrl().toString());
        return Map.copyOf(fields);
    }

    private Map<String, String> signedCheckoutRequest(
            Map<String, Object> checkoutFields,
            Instant signedAt) {
        Map<String, Object> fields = commonRequestFields(signedAt);
        fields.putAll(Objects.requireNonNull(checkoutFields));
        return signatures.sign(fields, properties.activeKeyVersion());
    }

    private PaymentCheckoutSubmission checkoutSubmission(
            Map<String, Object> checkoutFields,
            OffsetDateTime orderExpiresAt) {
        // submit 必须在 create 验签完成后重新取时间并重新签名，不能复用可能已接近五分钟边界的请求。
        Instant signedAt = clock.instant();
        Map<String, String> signed = signedCheckoutRequest(checkoutFields, signedAt);
        Instant submitExpiresAt = signedAt.plus(SUBMIT_SIGNATURE_VALIDITY);
        if (orderExpiresAt.toInstant().isBefore(submitExpiresAt)) {
            submitExpiresAt = orderExpiresAt.toInstant();
        }
        if (!submitExpiresAt.isAfter(signedAt)) {
            throw invalid("BAR checkout submission expiry is invalid.");
        }
        PaymentCheckoutSubmissionFields fields = new PaymentCheckoutSubmissionFields(
                signed.get("pid"),
                signed.get("out_trade_no"),
                signed.get("type"),
                signed.get("name"),
                signed.get("money"),
                signed.get("notify_url"),
                signed.get("return_url"),
                signed.get("timestamp"),
                signed.get("key_version"),
                signed.get("sign_type"),
                signed.get("sign"));
        // API Key 从未进入 signed Map；类型化描述只允许随当前 no-store 响应短暂传给浏览器。
        return new PaymentCheckoutSubmission(
                PaymentProviderType.BAR,
                PaymentCheckoutMode.FORM_POST,
                requireSubmitAction(),
                "POST",
                MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                MembershipPaymentTime.fromInstant(submitExpiresAt),
                fields);
    }

    private Map<String, String> signedLocatorRequest(
            String orderId,
            String providerTradeNo,
            String money) {
        Map<String, Object> fields = commonRequestFields(clock.instant());
        if (providerTradeNo != null && !providerTradeNo.isBlank()) {
            if (!TRADE_NUMBER.matcher(providerTradeNo).matches()) {
                throw new IllegalArgumentException("BAR trade number is invalid.");
            }
            fields.put("trade_no", providerTradeNo);
        } else if (orderId != null && orderId.length() == 22) {
            fields.put("out_trade_no", orderId);
        } else {
            throw new IllegalArgumentException("BAR order locator is invalid.");
        }
        if (money != null) {
            fields.put("money", money);
        }
        return signatures.sign(fields, properties.activeKeyVersion());
    }

    private Map<String, Object> commonRequestFields(Instant signedAt) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("pid", properties.pid());
        fields.put("timestamp", Long.toString(
                Objects.requireNonNull(signedAt).getEpochSecond()));
        fields.put("key_version", Integer.toString(properties.activeKeyVersion()));
        fields.put("sign_type", SIGN_TYPE);
        return fields;
    }

    private HttpJsonResponse post(
            String path,
            Map<String, String> request,
            Set<Integer> successStatuses,
            boolean exposeConflict) {
        try {
            return restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((outbound, upstream) -> {
                        int status = upstream.getStatusCode().value();
                        byte[] body = upstream.getBody().readNBytes(
                                properties.responseMaxBytes() + 1);
                        if (body.length == 0 || body.length > properties.responseMaxBytes()) {
                            throw invalid("BAR response size is invalid.");
                        }
                        if (status == HttpStatus.UNAUTHORIZED.value()) {
                            throw failure(
                                    MembershipPaymentErrorCode.BAR_AUTH_FAILED,
                                    "BAR rejected backend authentication.");
                        }
                        if (status == HttpStatus.CONFLICT.value() && exposeConflict) {
                            throw new BarConflictException();
                        }
                        if (status >= 500) {
                            throw failure(
                                    MembershipPaymentErrorCode.BAR_UNAVAILABLE,
                                    "BAR is temporarily unavailable.");
                        }
                        if (!successStatuses.contains(status)) {
                            throw invalid("BAR returned an unexpected HTTP status.");
                        }
                        return new HttpJsonResponse(status, readObject(body));
                    });
        } catch (BarConflictException | MembershipPaymentException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            if (hasTimeoutCause(exception)) {
                throw failure(
                        MembershipPaymentErrorCode.BAR_TIMEOUT,
                        "BAR request timed out.",
                        exception);
            }
            throw failure(
                    MembershipPaymentErrorCode.BAR_UNAVAILABLE,
                    "BAR cannot be reached.");
        } catch (RestClientException exception) {
            throw invalid("BAR returned an unreadable response.");
        }
    }

    private Map<String, Object> verified(
            Map<String, Object> body,
            Set<String> allowedFields) {
        if (!allowedFields.equals(body.keySet())) {
            throw invalid("BAR response fields do not match the protocol contract.");
        }
        // canonicalize 同时拒绝嵌套值；验签必须发生在任何业务字段被信任之前。
        try {
            signatures.canonicalize(body);
        } catch (IllegalArgumentException exception) {
            throw invalid("BAR response contains a non-scalar field.");
        }
        if (requiredInteger(body, "code") != 0
                || !"success".equals(requiredString(body, "msg", 32, false))
                || !SIGN_TYPE.equals(requiredString(body, "sign_type", 32, false))) {
            throw invalid("BAR success response metadata is invalid.");
        }
        int keyVersion = requiredInteger(body, "key_version");
        if (!properties.apiKeys().containsKey(keyVersion)) {
            throw failure(
                    MembershipPaymentErrorCode.BAR_AUTH_FAILED,
                    "BAR response uses an unavailable key version.");
        }
        requireFreshTimestamp(requiredString(body, "timestamp", 10, false));
        if (!signatures.verify(body, keyVersion)) {
            throw failure(
                    MembershipPaymentErrorCode.BAR_SIGNATURE_INVALID,
                    "BAR response signature is invalid.");
        }
        return body;
    }

    private PaymentQueryResult queryResult(
            PaymentQueryCommand command,
            Map<String, Object> body) {
        if (!Objects.equals(properties.pid(), scalar(body.get("pid")))) {
            throw invalid("BAR query response merchant is invalid.");
        }
        String orderId = requiredString(body, "out_trade_no", 128, false);
        String tradeNo = requiredTradeNo(body, "trade_no");
        if (!Objects.equals(command.orderId(), orderId)
                || (command.providerTradeNo() != null
                        && !Objects.equals(command.providerTradeNo(), tradeNo))) {
            throw conflict("BAR query response belongs to another order.");
        }
        PaymentProviderStatus status = mapStatus(
                requiredString(body, "trade_status", 32, false));
        int statusCode = requiredInteger(body, "status");
        if (!matchesStatusCode(status, statusCode)) {
            throw invalid("BAR query status code and name do not match.");
        }
        if (!NOTIFY_STATUSES.contains(requiredString(
                body, "notify_status", 32, false))) {
            throw invalid("BAR notify status is invalid.");
        }
        requiredTime(body, "created_at", false);
        BigDecimal amount = requiredAmount(body, "money");
        String channelTradeNo = optionalString(body, "api_trade_no", 128);
        if (channelTradeNo != null && !SAFE_CHANNEL_TRADE.matcher(channelTradeNo).matches()) {
            throw invalid("BAR channel trade number is invalid.");
        }
        OffsetDateTime finishedAt = requiredTime(body, "finished_at", true);
        if ((status == PaymentProviderStatus.PAID
                        || status == PaymentProviderStatus.REFUNDED)
                && (finishedAt == null || channelTradeNo == null)) {
            throw invalid("BAR paid result lacks a trusted completion fact.");
        }
        return new PaymentQueryResult(
                orderId, tradeNo, channelTradeNo, status, amount, finishedAt, null);
    }

    private Map<String, Object> readObject(byte[] body) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    body, new TypeReference<LinkedHashMap<String, Object>>() { });
            if (parsed == null || parsed.isEmpty()) {
                throw invalid("BAR returned an empty JSON object.");
            }
            return parsed;
        } catch (IOException exception) {
            throw invalid("BAR returned malformed JSON.");
        }
    }

    private URI requireSubmitAction() {
        URI uri = properties.baseUrl().resolve(SUBMIT_PATH);
        URI base = properties.baseUrl();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !base.getHost().equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getFragment() != null
                || !SUBMIT_PATH.equals(uri.getPath())) {
            throw invalid("BAR submit action is outside the approved origin.");
        }
        return uri;
    }

    private void requireFreshTimestamp(String raw) {
        try {
            long seconds = Long.parseLong(raw);
            if (raw.length() != 10 || !Long.toString(seconds).equals(raw)) {
                throw invalid("BAR response timestamp is not canonical.");
            }
            Duration skew = Duration.between(
                    Instant.ofEpochSecond(seconds), clock.instant()).abs();
            if (skew.compareTo(RESPONSE_TIMESTAMP_TOLERANCE) > 0) {
                throw invalid("BAR response timestamp is outside the accepted window.");
            }
        } catch (NumberFormatException | DateTimeException exception) {
            throw invalid("BAR response timestamp is invalid.");
        }
    }

    private static PaymentProviderStatus mapStatus(String status) {
        try {
            return PaymentProviderStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            return PaymentProviderStatus.UNKNOWN;
        }
    }

    private static boolean matchesStatusCode(
            PaymentProviderStatus status,
            int statusCode) {
        return switch (status) {
            case PENDING -> statusCode == 0;
            case PAID -> statusCode == 1;
            case REFUNDED -> statusCode == 2;
            case FAILED -> statusCode == -1;
            case CLOSED -> statusCode == -2;
            case EXPIRED -> statusCode == -3;
            case UNKNOWN -> true;
        };
    }

    private static String requiredTradeNo(Map<String, Object> body, String name) {
        String value = requiredString(body, name, 20, false);
        if (!TRADE_NUMBER.matcher(value).matches()) {
            throw invalid("BAR trade number is invalid.");
        }
        return value;
    }

    private static String requiredString(
            Map<String, Object> body,
            String name,
            int maximumLength,
            boolean allowEmpty) {
        Object raw = body.get(name);
        if (!(raw instanceof String value)
                || value.length() > maximumLength
                || !value.equals(value.trim())
                || (!allowEmpty && value.isEmpty())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid("BAR response text field is invalid.");
        }
        return value;
    }

    private static String optionalString(
            Map<String, Object> body,
            String name,
            int maximumLength) {
        String value = requiredString(body, name, maximumLength, true);
        return value.isEmpty() ? null : value;
    }

    private static int requiredInteger(Map<String, Object> body, String name) {
        Object raw = body.get(name);
        try {
            if (raw instanceof Integer value) {
                return value;
            }
            if (raw instanceof String value && value.matches("^-?[0-9]+$")) {
                int parsed = Integer.parseInt(value);
                if (Integer.toString(parsed).equals(value)) {
                    return parsed;
                }
            }
        } catch (NumberFormatException ignored) {
            // 统一在方法末尾映射为非法响应，避免暴露具体字段值。
        }
        throw invalid("BAR response integer field is invalid.");
    }

    private static boolean requiredBoolean(Map<String, Object> body, String name) {
        Object raw = body.get(name);
        if (raw instanceof Boolean value) {
            return value;
        }
        throw invalid("BAR response boolean field is invalid.");
    }

    private static BigDecimal requiredAmount(Map<String, Object> body, String name) {
        String raw = requiredString(body, name, 32, false);
        try {
            BigDecimal amount = requireAmount(new BigDecimal(raw));
            if (!amount.toPlainString().equals(raw)) {
                throw invalid("BAR amount text is not canonical.");
            }
            return amount;
        } catch (NumberFormatException exception) {
            throw invalid("BAR amount is invalid.");
        }
    }

    private static BigDecimal requireAmount(BigDecimal amount) {
        try {
            if (amount == null || amount.signum() <= 0) {
                throw invalid("BAR amount is invalid.");
            }
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw invalid("BAR amount is invalid.");
        }
    }

    private static OffsetDateTime requiredTime(
            Map<String, Object> body,
            String name,
            boolean allowEmpty) {
        String raw = requiredString(body, name, 64, allowEmpty);
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException exception) {
            throw invalid("BAR response time is invalid.");
        }
    }

    private static String scalar(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean hasTimeoutCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current.getClass().getSimpleName().endsWith("TimeoutException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static MembershipPaymentException invalid(String message) {
        return failure(MembershipPaymentErrorCode.BAR_RESPONSE_INVALID, message);
    }

    private static MembershipPaymentException conflict(String message) {
        return failure(MembershipPaymentErrorCode.BAR_ORDER_CONFLICT, message);
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

    private record HttpJsonResponse(int status, Map<String, Object> body) {
    }

    private static final class BarConflictException extends RuntimeException {
    }
}
