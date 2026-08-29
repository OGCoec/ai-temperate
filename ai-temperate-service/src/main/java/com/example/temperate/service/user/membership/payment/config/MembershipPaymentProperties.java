package com.example.temperate.service.user.membership.payment.config;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 该配置是来绑定会员支付 Provider、BAR HTTPS 安全边界、状态时间窗和既有异步批处理参数，并在启动期拒绝不安全组合。
 */
@Validated
@ConfigurationProperties(prefix = "app.membership-payment")
public record MembershipPaymentProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("true") boolean checkoutEnabled,
        @DefaultValue("LOCAL_SIMULATOR") PaymentProviderType defaultProvider,
        @DefaultValue("PT5M") Duration pendingDuration,
        @DefaultValue("PT5M") Duration closingDuration,
        @DefaultValue Simulator simulator,
        @DefaultValue Bar bar,
        @DefaultValue Callback callback,
        @DefaultValue OrderPersist orderPersist,
        @DefaultValue Rabbit rabbit) {

    private static final Duration REQUIRED_STATE_WINDOW = Duration.ofMinutes(5);
    private static final Duration FAST_PENDING_WINDOW = Duration.ofSeconds(45);
    private static final Duration FAST_CLOSING_WINDOW = Duration.ofSeconds(40);
    private static final List<Long> FAST_PAYMENT_DELAYS =
            List.of(10_000L, 10_000L, 10_000L, 15_000L);
    private static final List<Long> FAST_CLOSING_DELAYS =
            List.of(10_000L, 15_000L, 15_000L);
    private static final Duration REQUIRED_CALLBACK_DEDUPE_TTL = Duration.ofSeconds(30);
    private static final Duration REQUIRED_CALLBACK_MARKER_TTL = Duration.ofMinutes(10);
    private static final Duration REQUIRED_CALLBACK_DATA_TTL = Duration.ofHours(6);
    private static final Pattern SIMULATOR_PID =
            Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Pattern BAR_PID = Pattern.compile("^[0-9]{1,18}$");
    private static final Pattern BAR_API_KEY =
            Pattern.compile("^bar_sk_[A-Za-z0-9_-]{43}$");
    private static final String BAR_CALLBACK_PATH = "/api/payment/bar/notify";
    private static final String BAR_MERCHANT_HOST = "niko000o.site";
    private static final String BAR_PROVIDER_HOST = "ihaveagoddamnplan.com";

    @ConstructorBinding
    public MembershipPaymentProperties {
        requirePositive("pending duration", pendingDuration);
        requirePositive("closing duration", closingDuration);
        if (defaultProvider == null
                || simulator == null
                || bar == null
                || callback == null
                || orderPersist == null
                || rabbit == null) {
            throw new IllegalArgumentException("Membership payment configuration groups are required.");
        }
        if (simulator.enabled() && !enabled) {
            throw new IllegalArgumentException(
                    "Membership payment must be enabled before the simulator can be enabled.");
        }
        if (simulator.enabled() && bar.enabled()) {
            throw new IllegalArgumentException(
                    "The local simulator and BAR must not be enabled in the same environment.");
        }
        validateSimulator(simulator);
        validateBar(bar);
        validateCallback(callback);
        validateOrderPersist(orderPersist);
        validateRabbit(rabbit);
        validateTimingContract(pendingDuration, closingDuration, rabbit);
        if (enabled && checkoutEnabled) {
            if (defaultProvider == PaymentProviderType.LOCAL_SIMULATOR
                    && !simulator.enabled()) {
                throw new IllegalArgumentException(
                        "The local simulator must be enabled when it is the default provider.");
            }
            if (defaultProvider == PaymentProviderType.BAR && !bar.enabled()) {
                throw new IllegalArgumentException(
                        "BAR must be enabled when it is the default provider.");
            }
        }
    }

    /**
     * 该兼容构造器是来让既有本地模拟器测试继续显式传入原配置组；未启用模拟器时默认暂停 checkout。
     */
    public MembershipPaymentProperties(
            boolean enabled,
            Duration pendingDuration,
            Duration closingDuration,
            Simulator simulator,
            Callback callback,
            OrderPersist orderPersist,
            Rabbit rabbit) {
        this(
                enabled,
                simulator != null && simulator.enabled(),
                PaymentProviderType.LOCAL_SIMULATOR,
                pendingDuration,
                closingDuration,
                simulator,
                new Bar(
                        false,
                        URI.create("https://ihaveagoddamnplan.com"),
                        "",
                        0,
                        Map.of(),
                        null,
                        null,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        65_536),
                callback,
                orderPersist,
                rabbit);
    }

    /**
     * 判断当前是否为唯一允许缩短的压测时间合同，供启动期 Profile 守卫阻止普通环境误用。
     */
    public boolean usesFastTimingContract() {
        return FAST_PENDING_WINDOW.equals(pendingDuration)
                && FAST_CLOSING_WINDOW.equals(closingDuration)
                && FAST_PAYMENT_DELAYS.equals(rabbit.paymentCheckDelaysMillis())
                && FAST_CLOSING_DELAYS.equals(rabbit.closingCheckDelaysMillis());
    }

    /** 该配置组是来限制仅供测试的双协议回调密钥、商户号、时间窗和请求体大小。 */
    public record Simulator(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String pid,
            @DefaultValue("") String callbackKey,
            @DefaultValue("PT5M") Duration timestampTolerance,
            @DefaultValue("16384") int requestMaxBytes,
            @DefaultValue("false") boolean signatureValidationEnabled) {

    }

    /** 该配置组是来固定 BAR 商户 Origin、版本化密钥、回调地址和同步 RestClient 安全边界。 */
    public record Bar(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("https://ihaveagoddamnplan.com") URI baseUrl,
            @DefaultValue("") String pid,
            @DefaultValue("0") int activeKeyVersion,
            Map<Integer, String> apiKeys,
            URI notifyUrl,
            URI returnUrl,
            @DefaultValue("PT2S") Duration connectTimeout,
            @DefaultValue("PT5S") Duration readTimeout,
            @DefaultValue("65536") int responseMaxBytes) {

        public Bar {
            apiKeys = apiKeys == null ? Map.of() : Map.copyOf(apiKeys);
        }
    }

    /** 该配置组是来约束回调 ZSet 五秒刷盘的批次、轮次、租约和固定 TTL。 */
    public record Callback(
            @DefaultValue("5000") long flushIntervalMillis,
            @DefaultValue("100") int batchSize,
            @DefaultValue("50") int maxBatchesPerRun,
            @DefaultValue("PT60S") Duration processingTimeout,
            @DefaultValue("PT30S") Duration dedupeTtl,
            @DefaultValue("PT10M") Duration markerTtl,
            @DefaultValue("PT6H") Duration dataTtl) {
    }

    /** 该配置组是来约束订单脏版本刷盘批次、恢复租约和 Redisson 看门狗锁等待时间。 */
    public record OrderPersist(
            @DefaultValue("5000") long flushIntervalMillis,
            @DefaultValue("100") int batchSize,
            @DefaultValue("50") int maxBatchesPerRun,
            @DefaultValue("PT60S") Duration processingTimeout,
            @DefaultValue("PT0.1S") Duration lockWait) {
    }

    /** 该配置组是来固定 PENDING/CLOSING 分段检查总时长和最终 UNKNOWN 有限重试边界。 */
    public record Rabbit(
            @DefaultValue({"10000", "10000", "10000", "15000", "15000", "30000", "30000", "60000", "120000"})
            List<Long> paymentCheckDelaysMillis,
            @DefaultValue({"30000", "30000", "60000", "60000", "120000"})
            List<Long> closingCheckDelaysMillis,
            @DefaultValue("PT30S") Duration terminalQueryRetryDelay,
            @DefaultValue("3") int terminalQueryMaxRetries) {

        public Rabbit {
            paymentCheckDelaysMillis = paymentCheckDelaysMillis == null
                    ? null
                    : List.copyOf(paymentCheckDelaysMillis);
            closingCheckDelaysMillis = closingCheckDelaysMillis == null
                    ? null
                    : List.copyOf(closingCheckDelaysMillis);
        }
    }

    private static void validateSimulator(Simulator value) {
        requirePositive("simulator timestamp tolerance", value.timestampTolerance());
        if (value.requestMaxBytes() < 1024 || value.requestMaxBytes() > 65_536) {
            throw new IllegalArgumentException(
                    "Membership payment simulator request limit must be between 1 and 64 KiB.");
        }
        if (!value.enabled()) {
            return;
        }
        if (value.pid() == null || !SIMULATOR_PID.matcher(value.pid()).matches()) {
            throw new IllegalArgumentException(
                    "Membership payment simulator PID is required when enabled.");
        }
        if (value.callbackKey() == null
                || value.callbackKey().isBlank()
                || !value.callbackKey().equals(value.callbackKey().trim())
                || value.callbackKey().chars().anyMatch(Character::isISOControl)
                || value.callbackKey().getBytes(StandardCharsets.UTF_8).length < 32
                || value.callbackKey().getBytes(StandardCharsets.UTF_8).length > 512) {
            throw new IllegalArgumentException(
                    "Membership payment simulator callback key must contain 32 to 512 safe UTF-8 bytes.");
        }
    }

    private static void validateBar(Bar value) {
        requirePositive("BAR connect timeout", value.connectTimeout());
        requirePositive("BAR read timeout", value.readTimeout());
        if (value.responseMaxBytes() < 1024 || value.responseMaxBytes() > 1_048_576) {
            throw new IllegalArgumentException(
                    "BAR response limit must be between 1 KiB and 1 MiB.");
        }
        requireHttpsOrigin("BAR base URL", value.baseUrl(), BAR_PROVIDER_HOST, true);
        if (!value.enabled()) {
            return;
        }
        if (value.pid() == null || !BAR_PID.matcher(value.pid()).matches()) {
            throw new IllegalArgumentException("BAR PID is required when enabled.");
        }
        if (value.activeKeyVersion() <= 0
                || !value.apiKeys().containsKey(value.activeKeyVersion())) {
            throw new IllegalArgumentException(
                    "BAR active key version must reference a configured API key.");
        }
        for (Map.Entry<Integer, String> entry : value.apiKeys().entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey() <= 0
                    || entry.getValue() == null
                    || !BAR_API_KEY.matcher(entry.getValue()).matches()) {
                throw new IllegalArgumentException("BAR API key configuration is invalid.");
            }
        }
        requireHttpsOrigin("BAR notify URL", value.notifyUrl(), BAR_MERCHANT_HOST, false);
        requireHttpsOrigin("BAR return URL", value.returnUrl(), BAR_MERCHANT_HOST, false);
        if (!BAR_CALLBACK_PATH.equals(value.notifyUrl().getPath())) {
            throw new IllegalArgumentException(
                    "BAR notify URL must use the fixed payment callback path.");
        }
    }

    private static void requireHttpsOrigin(
            String name,
            URI uri,
            String requiredHost,
            boolean originOnly) {
        if (uri == null
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getPort() != -1
                || uri.getRawQuery() != null
                || (originOnly && ((uri.getPath() != null
                        && !uri.getPath().isBlank()
                        && !"/".equals(uri.getPath()))))
                || (requiredHost != null && !requiredHost.equalsIgnoreCase(uri.getHost()))) {
            throw new IllegalArgumentException(name + " must use the approved HTTPS origin.");
        }
    }

    private static void validateCallback(Callback value) {
        requireInterval(value.flushIntervalMillis(), "callback flush interval");
        requireBatch(value.batchSize(), "callback batch size");
        requireRuns(value.maxBatchesPerRun(), "callback max batches per run");
        requirePositive("callback processing timeout", value.processingTimeout());
        requirePositive("callback dedupe TTL", value.dedupeTtl());
        requirePositive("callback marker TTL", value.markerTtl());
        requirePositive("callback data TTL", value.dataTtl());
        requireExact(
                "callback dedupe TTL",
                value.dedupeTtl(),
                REQUIRED_CALLBACK_DEDUPE_TTL);
        requireExact(
                "callback marker TTL",
                value.markerTtl(),
                REQUIRED_CALLBACK_MARKER_TTL);
        requireExact(
                "callback data TTL",
                value.dataTtl(),
                REQUIRED_CALLBACK_DATA_TTL);
    }

    private static void validateOrderPersist(OrderPersist value) {
        requireInterval(value.flushIntervalMillis(), "order persist flush interval");
        requireOrderPersistBatch(value.batchSize());
        requireRuns(value.maxBatchesPerRun(), "order persist max batches per run");
        requirePositive("order persist processing timeout", value.processingTimeout());
        requirePositive("order persist lock wait", value.lockWait());
        if (value.lockWait().compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException(
                    "Membership payment order persist lock wait must not exceed five seconds.");
        }
    }

    private static void validateRabbit(Rabbit value) {
        requireDelayPlan("payment check", value.paymentCheckDelaysMillis());
        requireDelayPlan("closing check", value.closingCheckDelaysMillis());
        requirePositive("terminal query retry delay", value.terminalQueryRetryDelay());
        if (value.terminalQueryMaxRetries() < 0 || value.terminalQueryMaxRetries() > 10) {
            throw new IllegalArgumentException(
                    "Membership payment terminal query retries must be between 0 and 10.");
        }
    }

    private static void requireDelayPlan(String name, List<Long> values) {
        if (values == null || values.isEmpty() || values.size() > 32
                || values.stream().anyMatch(value -> value == null || value <= 0L)) {
            throw new IllegalArgumentException(name + " delay plan is invalid.");
        }
        try {
            values.stream().mapToLong(Long::longValue).reduce(0L, Math::addExact);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " delay plan is too large.", exception);
        }
    }

    private static void validateTimingContract(
            Duration pendingDuration,
            Duration closingDuration,
            Rabbit rabbit) {
        boolean realtime = REQUIRED_STATE_WINDOW.equals(pendingDuration)
                && REQUIRED_STATE_WINDOW.equals(closingDuration)
                && delayTotal(rabbit.paymentCheckDelaysMillis())
                        == REQUIRED_STATE_WINDOW.toMillis()
                && delayTotal(rabbit.closingCheckDelaysMillis())
                        == REQUIRED_STATE_WINDOW.toMillis();
        boolean fast = FAST_PENDING_WINDOW.equals(pendingDuration)
                && FAST_CLOSING_WINDOW.equals(closingDuration)
                && FAST_PAYMENT_DELAYS.equals(rabbit.paymentCheckDelaysMillis())
                && FAST_CLOSING_DELAYS.equals(rabbit.closingCheckDelaysMillis());
        if (!realtime && !fast) {
            throw new IllegalArgumentException(
                    "Membership payment timing contract must use five minutes per stage or the fixed loadtest-fast timing contract.");
        }
    }

    private static long delayTotal(List<Long> values) {
        try {
            return values.stream().mapToLong(Long::longValue).reduce(0L, Math::addExact);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Membership payment delay plan is too large.", exception);
        }
    }

    private static void requireInterval(long value, String name) {
        if (value < 100L || value > Duration.ofMinutes(5).toMillis()) {
            throw new IllegalArgumentException(name + " is outside its safe range.");
        }
    }

    private static void requireBatch(int value, String name) {
        if (value < 1 || value > 500) {
            throw new IllegalArgumentException(name + " must be between 1 and 500.");
        }
    }

    private static void requireOrderPersistBatch(int value) {
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException(
                    "Membership payment order persist batch size must be between 1 and 100.");
        }
    }

    private static void requireRuns(int value, String name) {
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException(name + " must be between 1 and 100.");
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
    }

    private static void requireExact(
            String name,
            Duration actual,
            Duration expected) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(name + " must keep its fixed safety value.");
        }
    }
}
