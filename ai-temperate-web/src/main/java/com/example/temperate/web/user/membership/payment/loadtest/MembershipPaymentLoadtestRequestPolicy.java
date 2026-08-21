package com.example.temperate.web.user.membership.payment.loadtest;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * 该策略是来把会员支付 AT-only 压测认证限制到四种精确 HTTP 路由形状，防止开关扩大到回调或其他用户接口。
 */
@Component
public final class MembershipPaymentLoadtestRequestPolicy {

    private static final String ROOT = "/api/user/membership-orders";
    public static final String TOKEN_MINT_PATH =
            "/internal/test/membership-payments/loadtest-tokens";
    public static final String CONTROL_ROOT =
            "/internal/test/membership-payments/loadtest-control";
    private static final String ORDER_ID = "[A-Za-z0-9_-]{"
            + HybridBase64UrlCodec.ENCODED_LENGTH
            + "}";
    private static final Pattern ORDER = Pattern.compile("^" + ROOT + "/" + ORDER_ID + "$");
    private static final Pattern CANCEL =
            Pattern.compile("^" + ROOT + "/" + ORDER_ID + "/cancel$");
    private static final Pattern PAYMENT_ATTEMPTS =
            Pattern.compile("^" + ROOT + "/" + ORDER_ID + "/payment-attempts$");

    private final MembershipPaymentLoadtestProperties properties;

    public MembershipPaymentLoadtestRequestPolicy(
            MembershipPaymentLoadtestProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    public boolean matches(HttpServletRequest request) {
        if (!properties.enabled() || request == null) {
            return false;
        }
        String path = applicationPath(request);
        String method = request.getMethod();
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
        if (HttpMethod.POST.matches(request.getMethod())
                && TOKEN_MINT_PATH.equals(path)) {
            return true;
        }
        if (HttpMethod.GET.matches(request.getMethod())) {
            return (CONTROL_ROOT + "/state").equals(path)
                    || (CONTROL_ROOT + "/queues").equals(path)
                    || (CONTROL_ROOT + "/faults").equals(path);
        }
        return HttpMethod.POST.matches(request.getMethod())
                && ((CONTROL_ROOT + "/recover-callback").equals(path)
                        || (CONTROL_ROOT + "/recover-order").equals(path)
                        || (CONTROL_ROOT + "/flush").equals(path)
                        || (CONTROL_ROOT + "/state-batch").equals(path)
                        || (CONTROL_ROOT + "/arm-callback-complete-failure").equals(path)
                        || (CONTROL_ROOT + "/rabbit-retry").equals(path)
                        || (CONTROL_ROOT + "/rabbit-poison").equals(path));
    }

    /**
     * 测试兼容构造器使用关闭策略，确保既有直接实例化拦截器不会意外开启安全旁路。
     */
    public static MembershipPaymentLoadtestRequestPolicy disabled() {
        return new MembershipPaymentLoadtestRequestPolicy(
                new MembershipPaymentLoadtestProperties(false, java.util.List.of()));
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
