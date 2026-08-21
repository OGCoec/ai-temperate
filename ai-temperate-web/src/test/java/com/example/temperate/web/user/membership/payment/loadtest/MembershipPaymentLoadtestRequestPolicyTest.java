package com.example.temperate.web.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 该测试是来锁定 AT-only 认证仅覆盖会员订单创建、查询、取消和支付发起的精确方法与路径。
 */
final class MembershipPaymentLoadtestRequestPolicyTest {

    private final MembershipPaymentLoadtestRequestPolicy policy =
            new MembershipPaymentLoadtestRequestPolicy(
                    new MembershipPaymentLoadtestProperties(
                            true, List.of(73014701344296960L)));

    @Test
    void acceptsOnlyTheFourApprovedRouteShapes() {
        assertThat(matches("POST", "/api/user/membership-orders")).isTrue();
        assertThat(matches("GET", "/api/user/membership-orders/AaAjECcaAQGqi_h2Rl1PiA"))
                .isTrue();
        assertThat(matches("POST", "/api/user/membership-orders/AaAjECcaAQGqi_h2Rl1PiA/cancel"))
                .isTrue();
        assertThat(matches("POST", "/api/user/membership-orders/AaAjECcaAQGqi_h2Rl1PiA/payment-attempts"))
                .isTrue();
    }

    @Test
    void rejectsWrongMethodsNestedPathsAndCallbackRoutes() {
        assertThat(matches("GET", "/api/user/membership-orders")).isFalse();
        assertThat(matches("PUT", "/api/user/membership-orders/AaAjECcaAQGqi_h2Rl1PiA"))
                .isFalse();
        assertThat(matches("POST", "/api/user/membership-orders/AaAjECcaAQGqi_h2Rl1PiA/extra"))
                .isFalse();
        assertThat(matches("POST", "/api/payment/callback/simulated-liuhao"))
                .isFalse();
    }

    @Test
    void disabledSwitchMatchesNothing() {
        MembershipPaymentLoadtestRequestPolicy disabled =
                new MembershipPaymentLoadtestRequestPolicy(
                        new MembershipPaymentLoadtestProperties(false, List.of()));

        assertThat(disabled.matches(request("POST", "/api/user/membership-orders")))
                .isFalse();
        assertThat(disabled.matchesTokenMint(request(
                "POST", MembershipPaymentLoadtestRequestPolicy.TOKEN_MINT_PATH)))
                .isFalse();
    }

    @Test
    void tokenMintRouteIsSeparateFromAccessTokenBusinessRoutes() {
        assertThat(policy.matchesTokenMint(request(
                "POST", MembershipPaymentLoadtestRequestPolicy.TOKEN_MINT_PATH)))
                .isTrue();
        assertThat(policy.matches(request(
                "POST", MembershipPaymentLoadtestRequestPolicy.TOKEN_MINT_PATH)))
                .isFalse();
        assertThat(policy.matchesTokenMint(request(
                "GET", MembershipPaymentLoadtestRequestPolicy.TOKEN_MINT_PATH)))
                .isFalse();
        assertThat(policy.matchesTokenMint(request(
                "POST", MembershipPaymentLoadtestRequestPolicy.TOKEN_MINT_PATH + "/extra")))
                .isFalse();
    }

    @Test
    void acceptsOnlyExactLoopbackControlRouteShapes() {
        String root = MembershipPaymentLoadtestRequestPolicy.CONTROL_ROOT;

        assertThat(policy.matchesTokenMint(request("GET", root + "/state"))).isTrue();
        assertThat(policy.matchesTokenMint(request("GET", root + "/queues"))).isTrue();
        assertThat(policy.matchesTokenMint(request("GET", root + "/faults"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/recover-callback"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/recover-order"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/flush"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/state-batch"))).isTrue();
        assertThat(policy.matchesTokenMint(request(
                "POST", root + "/arm-callback-complete-failure"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/rabbit-retry"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/rabbit-poison"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/state"))).isFalse();
        assertThat(policy.matchesTokenMint(request("GET", root + "/flush"))).isFalse();
        assertThat(policy.matchesTokenMint(request("POST", root + "/extra"))).isFalse();
    }

    private boolean matches(String method, String path) {
        return policy.matches(request(method, path));
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
