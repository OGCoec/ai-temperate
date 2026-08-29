package com.example.temperate.web.user.membership.payment.loadtest;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.codec.id.HybridUlidCodec;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentBoundaryLoadtestProperties;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * 该策略是来把会员支付 AT-only 压测认证限制到报价读取、订单路由和本机测试入口，防止测试开关扩大到回调或其他用户接口。
 *
 * <p>共享 BAR 环境只额外开放回环只读检查路径；仅 W16 第二回环实例可开启精确的 Controller、推理替身和一次性事务回滚路径。
 * 暂停 Worker、通用故障注入等控制路径仍属于本地隔离环境，不能借共享检查入口暴露。</p>
 */
@Component
@EnableConfigurationProperties(MembershipPaymentLoadtestInferenceStubProperties.class)
public final class MembershipPaymentLoadtestRequestPolicy {

    private static final String ROOT = "/api/user/membership-orders";
    private static final String OFFERS = "/api/user/membership-plan-offers";
    public static final String TOKEN_MINT_PATH =
            "/internal/test/membership-payments/loadtest-tokens";
    public static final String CONTROL_ROOT =
            "/internal/test/membership-payments/loadtest-control";
    public static final String INSPECTION_ROOT =
            "/internal/test/membership-payments/loadtest-inspection";
    public static final String INFERENCE_STUB_ROOT =
            "/internal/test/membership-payments/inference-stub";
    public static final String BOUNDARY_ROOT =
            "/internal/test/membership-payments/millisecond-boundary";
    private static final String ORDER_ID = "[A-Za-z0-9_-]{"
            + HybridBase64UrlCodec.ENCODED_LENGTH
            + "}";
    private static final Pattern ORDER = Pattern.compile("^" + ROOT + "/" + ORDER_ID + "$");
    private static final Pattern CANCEL =
            Pattern.compile("^" + ROOT + "/" + ORDER_ID + "/cancel$");
    private static final Pattern PAYMENT_ATTEMPTS =
            Pattern.compile("^" + ROOT + "/" + ORDER_ID + "/payment-attempts$");
    private static final Pattern INFERENCE_VIDEO_POLL = Pattern.compile(
            "^" + INFERENCE_STUB_ROOT + "/v1/videos/[A-Za-z0-9._:-]{1,128}$");
    private static final Pattern QUOTA_GENERATION = Pattern.compile(
            "^/api/ai/conversations/generations/[A-Za-z0-9_-]{"
                    + HybridBase64UrlCodec.ENCODED_LENGTH
                    + "}$");
    private static final Pattern QUOTA_API_KEY = Pattern.compile(
            "^/api/users/me/api-keys/[A-Za-z0-9_-]{"
                    + HybridUlidCodec.ENCODED_LENGTH
                    + "}$");
    private static final String QUOTA_ROLLBACK_CONTROL =
            INFERENCE_STUB_ROOT + "/controls/quota-rollback";
    // 80K 固定样本按每页五百个用户签发，共一百六十页；仅放行无前导零的 0～159。
    private static final Pattern BOUNDARY_TOKEN_PAGE =
            Pattern.compile(
                    "^" + BOUNDARY_ROOT
                            + "/tokens/(?:[0-9]|[1-9][0-9]|1[0-5][0-9])$");
    private static final Set<String> INFERENCE_STUB_POST_PATHS = Set.of(
            INFERENCE_STUB_ROOT + "/v1/chat/completions",
            INFERENCE_STUB_ROOT + "/v1/images/generations",
            INFERENCE_STUB_ROOT + "/v1/images/edits",
            INFERENCE_STUB_ROOT + "/v1/videos/generations",
            INFERENCE_STUB_ROOT + "/v1/videos/edits",
            INFERENCE_STUB_ROOT + "/v1/videos/extensions");

    private final MembershipPaymentLoadtestProperties properties;
    private final MembershipPaymentLoadtestInferenceStubProperties
            inferenceStubProperties;
    private final MembershipPaymentBoundaryLoadtestProperties boundaryProperties;

    @Autowired
    public MembershipPaymentLoadtestRequestPolicy(
            MembershipPaymentLoadtestProperties properties,
            MembershipPaymentLoadtestInferenceStubProperties
                    inferenceStubProperties,
            MembershipPaymentBoundaryLoadtestProperties boundaryProperties) {
        this.properties = Objects.requireNonNull(properties);
        this.inferenceStubProperties = Objects.requireNonNull(
                inferenceStubProperties);
        this.boundaryProperties = Objects.requireNonNull(boundaryProperties);
    }

    public MembershipPaymentLoadtestRequestPolicy(
            MembershipPaymentLoadtestProperties properties,
            MembershipPaymentLoadtestInferenceStubProperties
                    inferenceStubProperties) {
        this(
                properties,
                inferenceStubProperties,
                new MembershipPaymentBoundaryLoadtestProperties(false));
    }

    /**
     * 保留既有独立安全测试的关闭替身构造；Spring 运行时始终注入完整推理替身配置。
     */
    public MembershipPaymentLoadtestRequestPolicy(
            MembershipPaymentLoadtestProperties properties) {
        this(
                properties,
                new MembershipPaymentLoadtestInferenceStubProperties(
                        false, ""),
                new MembershipPaymentBoundaryLoadtestProperties(false));
    }

    public boolean matches(HttpServletRequest request) {
        if (!properties.enabled() || request == null) {
            return false;
        }
        String path = applicationPath(request);
        String method = request.getMethod();
        if (matchesQuotaFirstUseSession(request, path, method)) {
            return true;
        }
        // Runner 必须先读取服务端实时报价再创建订单；只开放这个精确只读路径，不能把同一 Token 扩大到用户资料或其他 API。
        if (HttpMethod.GET.matches(method) && OFFERS.equals(path)) {
            return true;
        }
        if (HttpMethod.POST.matches(method) && ROOT.equals(path)) {
            return true;
        }
        if (HttpMethod.GET.matches(method) && ORDER.matcher(path).matches()) {
            return true;
        }
        return HttpMethod.POST.matches(method)
                && (CANCEL.matcher(path).matches()
                        || PAYMENT_ATTEMPTS.matcher(path).matches());
    }

    /**
     * 判断是否为本机 Token 签发或精确压测控制入口；这些路径不并入 AT-only 业务路径，
     * 避免拦截器把无认证的 Runner 请求当作 Bearer 请求解析，同时拒绝控制根路径之外的任意子路径。
     */
    public boolean matchesTokenMint(HttpServletRequest request) {
        if (!properties.enabled() || request == null) {
            return false;
        }
        String path = applicationPath(request);
        if (matchesBoundaryControl(request, path)) {
            return true;
        }
        if (HttpMethod.POST.matches(request.getMethod())
                && TOKEN_MINT_PATH.equals(path)) {
            return true;
        }
        if (HttpMethod.GET.matches(request.getMethod())) {
            return (inferenceStubRequest(request)
                            && (INFERENCE_VIDEO_POLL.matcher(path).matches()
                                    || QUOTA_ROLLBACK_CONTROL.equals(path)))
                    || (INSPECTION_ROOT + "/queues").equals(path)
                    || (INSPECTION_ROOT + "/runtime").equals(path)
                    || (CONTROL_ROOT + "/state").equals(path)
                    || (CONTROL_ROOT + "/queues").equals(path)
                    || (CONTROL_ROOT + "/faults").equals(path)
                    || (CONTROL_ROOT + "/callback-hold").equals(path)
                    || (CONTROL_ROOT + "/workers").equals(path)
                    || (CONTROL_ROOT + "/restricted-fixtures").equals(path)
                    || (CONTROL_ROOT + "/baseline-fixtures").equals(path);
        }
        return HttpMethod.POST.matches(request.getMethod())
                && ((inferenceStubRequest(request)
                            && (INFERENCE_STUB_POST_PATHS.contains(path)
                                    || (QUOTA_ROLLBACK_CONTROL + "/arm").equals(path)))
                        || (INSPECTION_ROOT + "/state-batch").equals(path)
                        || (CONTROL_ROOT + "/recover-callback").equals(path)
                        || (CONTROL_ROOT + "/recover-order").equals(path)
                        || (CONTROL_ROOT + "/flush").equals(path)
                        || (CONTROL_ROOT + "/state-batch").equals(path)
                        || (CONTROL_ROOT + "/arm-callback-complete-failure").equals(path)
                        || (CONTROL_ROOT + "/rabbit-retry").equals(path)
                        || (CONTROL_ROOT + "/rabbit-poison").equals(path)
                        || (CONTROL_ROOT + "/callback-hold/arm").equals(path)
                        || (CONTROL_ROOT + "/callback-hold/release").equals(path)
                        || (CONTROL_ROOT + "/workers/pause").equals(path)
                        || (CONTROL_ROOT + "/workers/resume").equals(path)
                        || (CONTROL_ROOT + "/restricted-fixtures/prepare").equals(path)
                        || (CONTROL_ROOT + "/restricted-fixtures/restore").equals(path)
                        || (CONTROL_ROOT + "/baseline-fixtures/prepare").equals(path));
    }

    /**
     * API Key 调用必须继续经过正式 Key 认证，只跳过第二回环实例无法伪造的边缘签名与公网 IP 风险门禁。
     */
    public boolean matchesInferenceClient(HttpServletRequest request) {
        return properties.enabled()
                && inferenceStubRequest(request)
                && ((HttpMethod.POST.matches(request.getMethod())
                            && "/v1/chat/completions".equals(
                                    applicationPath(request)))
                        || (HttpMethod.GET.matches(request.getMethod())
                            && "/v1/models".equals(applicationPath(request))));
    }

    /**
     * 测试兼容构造器使用关闭策略，确保既有直接实例化拦截器不会意外开启安全旁路。
     */
    public static MembershipPaymentLoadtestRequestPolicy disabled() {
        return new MembershipPaymentLoadtestRequestPolicy(
                new MembershipPaymentLoadtestProperties(false, java.util.List.of()),
                new MembershipPaymentLoadtestInferenceStubProperties(false, ""),
                new MembershipPaymentBoundaryLoadtestProperties(false));
    }

    private boolean matchesBoundaryControl(
            HttpServletRequest request,
            String path) {
        if (!boundaryProperties.enabled() || !isLoopback(request)) {
            return false;
        }
        String method = request.getMethod();
        if (HttpMethod.GET.matches(method)) {
            return (BOUNDARY_ROOT + "/state").equals(path);
        }
        return HttpMethod.POST.matches(method)
                && ((BOUNDARY_ROOT + "/prepare").equals(path)
                        || (BOUNDARY_ROOT + "/reset").equals(path)
                        || (BOUNDARY_ROOT + "/failed-run-reset").equals(path)
                        || (BOUNDARY_ROOT + "/segment-warmup-reset").equals(path)
                        || BOUNDARY_TOKEN_PAGE.matcher(path).matches());
    }

    private boolean matchesQuotaFirstUseSession(
            HttpServletRequest request,
            String path,
            String method) {
        if (!inferenceStubRequest(request)) {
            return false;
        }
        if (HttpMethod.GET.matches(method)) {
            return "/api/ai-models".equals(path)
                    || QUOTA_GENERATION.matcher(path).matches();
        }
        if (HttpMethod.POST.matches(method)) {
            return "/api/ai/conversations/responses".equals(path)
                    || "/api/users/me/api-keys".equals(path);
        }
        return HttpMethod.DELETE.matches(method)
                && QUOTA_API_KEY.matcher(path).matches();
    }

    private boolean inferenceStubRequest(HttpServletRequest request) {
        return inferenceStubProperties.enabled() && isLoopback(request);
    }

    private static boolean isLoopback(HttpServletRequest request) {
        String address = request == null ? null : request.getRemoteAddr();
        return "127.0.0.1".equals(address)
                || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
    }

    private static String applicationPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri == null) {
            return "";
        }
        if (contextPath != null
                && !contextPath.isEmpty()
                && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
