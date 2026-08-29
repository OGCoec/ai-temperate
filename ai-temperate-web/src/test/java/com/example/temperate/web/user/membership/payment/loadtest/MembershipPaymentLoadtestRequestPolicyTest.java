package com.example.temperate.web.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.membership.payment.config.MembershipPaymentBoundaryLoadtestProperties;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 该测试是来锁定 AT-only 认证仅覆盖会员报价读取、订单创建、查询、取消和支付发起的精确方法与路径。
 */
final class MembershipPaymentLoadtestRequestPolicyTest {

    private final MembershipPaymentLoadtestRequestPolicy policy =
            new MembershipPaymentLoadtestRequestPolicy(
                    new MembershipPaymentLoadtestProperties(
                            true, List.of(73014701344296960L)));

    @Test
    void acceptsOnlyTheFiveApprovedRouteShapes() {
        assertThat(matches("GET", "/api/user/membership-plan-offers")).isTrue();
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
        assertThat(matches("POST", "/api/user/membership-plan-offers")).isFalse();
        assertThat(matches("GET", "/api/user/membership-plan-offers/extra")).isFalse();
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
    void acceptsOnlyExactLoopbackBoundaryFixtureAndTokenRoutes() {
        MembershipPaymentLoadtestRequestPolicy boundaryPolicy =
                new MembershipPaymentLoadtestRequestPolicy(
                        new MembershipPaymentLoadtestProperties(
                                true, List.of(73014701344296960L)),
                        new MembershipPaymentLoadtestInferenceStubProperties(false, ""),
                        new MembershipPaymentBoundaryLoadtestProperties(true));
        String root = MembershipPaymentLoadtestRequestPolicy.BOUNDARY_ROOT;

        assertThat(matchesToken(boundaryPolicy, "POST", root + "/prepare", "127.0.0.1"))
                .isTrue();
        assertThat(matchesToken(boundaryPolicy, "GET", root + "/state", "::1"))
                .isTrue();
        assertThat(matchesToken(boundaryPolicy, "POST", root + "/reset", "127.0.0.1"))
                .isTrue();
        assertThat(matchesToken(
                boundaryPolicy, "POST", root + "/failed-run-reset", "127.0.0.1"))
                .isTrue();
        assertThat(matchesToken(
                boundaryPolicy, "POST", root + "/segment-warmup-reset", "127.0.0.1"))
                .isTrue();
        assertThat(matchesToken(boundaryPolicy, "POST", root + "/tokens/0", "127.0.0.1"))
                .isTrue();
        assertThat(matchesToken(boundaryPolicy, "POST", root + "/tokens/79", "127.0.0.1"))
                .isTrue();
        assertThat(matchesToken(boundaryPolicy, "POST", root + "/tokens/80", "127.0.0.1"))
                .isTrue();
        assertThat(matchesToken(boundaryPolicy, "POST", root + "/tokens/159", "127.0.0.1"))
                .isTrue();
        assertThat(matchesToken(boundaryPolicy, "POST", root + "/tokens/160", "127.0.0.1"))
                .isFalse();
        assertThat(matchesToken(boundaryPolicy, "POST", root + "/tokens/080", "127.0.0.1"))
                .isFalse();
        assertThat(matchesToken(boundaryPolicy, "GET", root + "/tokens/0", "127.0.0.1"))
                .isFalse();
        assertThat(matchesToken(boundaryPolicy, "POST", root + "/prepare/extra", "127.0.0.1"))
                .isFalse();
        assertThat(matchesToken(
                boundaryPolicy, "POST", root + "/failed-run-reset/extra", "127.0.0.1"))
                .isFalse();
        assertThat(matchesToken(
                boundaryPolicy, "POST", root + "/segment-warmup-reset/extra", "127.0.0.1"))
                .isFalse();
        assertThat(matchesToken(boundaryPolicy, "POST", root + "/prepare", "198.51.100.9"))
                .isFalse();
    }

    @Test
    void disabledIndependentBoundaryGateMatchesNoBoundaryRoute() {
        String root = MembershipPaymentLoadtestRequestPolicy.BOUNDARY_ROOT;

        assertThat(matchesToken(policy, "POST", root + "/prepare", "127.0.0.1"))
                .isFalse();
    }

    @Test
    void acceptsOnlyExactLoopbackControlRouteShapes() {
        String root = MembershipPaymentLoadtestRequestPolicy.CONTROL_ROOT;

        assertThat(policy.matchesTokenMint(request("GET", root + "/state"))).isTrue();
        assertThat(policy.matchesTokenMint(request("GET", root + "/queues"))).isTrue();
        assertThat(policy.matchesTokenMint(request("GET", root + "/faults"))).isTrue();
        assertThat(policy.matchesTokenMint(request("GET", root + "/callback-hold"))).isTrue();
        assertThat(policy.matchesTokenMint(request("GET", root + "/workers"))).isTrue();
        assertThat(policy.matchesTokenMint(request(
                "GET", root + "/restricted-fixtures"))).isTrue();
        assertThat(policy.matchesTokenMint(request(
                "GET", root + "/baseline-fixtures"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/recover-callback"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/recover-order"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/flush"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/state-batch"))).isTrue();
        assertThat(policy.matchesTokenMint(request(
                "POST", root + "/arm-callback-complete-failure"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/rabbit-retry"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/rabbit-poison"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/callback-hold/arm"))).isTrue();
        assertThat(policy.matchesTokenMint(request(
                "POST", root + "/callback-hold/release"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/workers/pause"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/workers/resume"))).isTrue();
        assertThat(policy.matchesTokenMint(request(
                "POST", root + "/restricted-fixtures/prepare"))).isTrue();
        assertThat(policy.matchesTokenMint(request(
                "POST", root + "/restricted-fixtures/restore"))).isTrue();
        assertThat(policy.matchesTokenMint(request(
                "POST", root + "/baseline-fixtures/prepare"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/state"))).isFalse();
        assertThat(policy.matchesTokenMint(request("GET", root + "/flush"))).isFalse();
        assertThat(policy.matchesTokenMint(request("POST", root + "/extra"))).isFalse();
    }

    @Test
    void acceptsOnlyReadOnlyInspectionRoutesForSharedBarVerification() {
        String root = "/internal/test/membership-payments/loadtest-inspection";

        assertThat(policy.matchesTokenMint(request("GET", root + "/queues"))).isTrue();
        assertThat(policy.matchesTokenMint(request("GET", root + "/runtime"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/state-batch"))).isTrue();
        assertThat(policy.matchesTokenMint(request("POST", root + "/queues"))).isFalse();
        assertThat(policy.matchesTokenMint(request("POST", root + "/runtime"))).isFalse();
        assertThat(policy.matchesTokenMint(request("GET", root + "/runtime/extra"))).isFalse();
        assertThat(policy.matchesTokenMint(request("GET", root + "/state-batch"))).isFalse();
        assertThat(policy.matchesTokenMint(request("POST", root + "/state-batch/extra")))
                .isFalse();
    }

    @Test
    void acceptsOnlyExactInferenceStubProtocolRoutes() {
        String root = "/internal/test/membership-payments/inference-stub";
        MembershipPaymentLoadtestRequestPolicy inferencePolicy =
                inferencePolicy();

        assertThat(inferencePolicy.matchesTokenMint(request(
                "POST", root + "/v1/chat/completions"))).isTrue();
        assertThat(inferencePolicy.matchesTokenMint(request(
                "POST", root + "/v1/images/generations"))).isTrue();
        assertThat(inferencePolicy.matchesTokenMint(request(
                "POST", root + "/v1/images/edits"))).isTrue();
        assertThat(inferencePolicy.matchesTokenMint(request(
                "POST", root + "/v1/videos/generations"))).isTrue();
        assertThat(inferencePolicy.matchesTokenMint(request(
                "POST", root + "/v1/videos/edits"))).isTrue();
        assertThat(inferencePolicy.matchesTokenMint(request(
                "POST", root + "/v1/videos/extensions"))).isTrue();
        assertThat(inferencePolicy.matchesTokenMint(request(
                "GET", root + "/v1/videos/loadtest-video-request"))).isTrue();
        assertThat(inferencePolicy.matchesTokenMint(request(
                "POST", root + "/controls/quota-rollback/arm"))).isTrue();
        assertThat(inferencePolicy.matchesTokenMint(request(
                "GET", root + "/controls/quota-rollback"))).isTrue();
        assertThat(inferencePolicy.matchesTokenMint(request(
                "GET", root + "/v1/chat/completions"))).isFalse();
        assertThat(inferencePolicy.matchesTokenMint(request(
                "POST", root + "/v1/videos/loadtest-video-request"))).isFalse();
        assertThat(inferencePolicy.matchesTokenMint(request(
                "GET", root + "/v1/videos/invalid/request"))).isFalse();
        assertThat(policy.matchesTokenMint(request(
                "POST", root + "/v1/chat/completions"))).isFalse();
    }

    @Test
    void quotaFirstUseRoutesRequireEnabledStubAndLoopback() {
        MembershipPaymentLoadtestRequestPolicy enabled = inferencePolicy();

        assertThat(enabled.matches(request("GET", "/api/ai-models"))).isTrue();
        assertThat(enabled.matches(request(
                "POST", "/api/ai/conversations/responses"))).isTrue();
        assertThat(enabled.matches(request(
                "GET", "/api/ai/conversations/generations/AaAjECcaAQGqi_h2Rl1PiA")))
                .isTrue();
        assertThat(enabled.matches(request(
                "POST", "/api/users/me/api-keys"))).isTrue();
        assertThat(enabled.matches(request(
                "DELETE", "/api/users/me/api-keys/01KC938NKR041061050R3GG28A")))
                .isTrue();
        assertThat(enabled.matchesInferenceClient(request(
                "POST", "/v1/chat/completions"))).isTrue();
        assertThat(enabled.matchesInferenceClient(request(
                "GET", "/v1/models"))).isTrue();

        MockHttpServletRequest remote = request("POST", "/api/ai/conversations/responses");
        remote.setRemoteAddr("198.51.100.20");
        assertThat(enabled.matches(remote)).isFalse();
        assertThat(policy.matches(request("GET", "/api/ai-models"))).isFalse();
        assertThat(enabled.matchesInferenceClient(request(
                "GET", "/v1/chat/completions"))).isFalse();
    }

    private boolean matches(String method, String path) {
        return policy.matches(request(method, path));
    }

    private static boolean matchesToken(
            MembershipPaymentLoadtestRequestPolicy policy,
            String method,
            String path,
            String remoteAddress) {
        MockHttpServletRequest request = request(method, path);
        request.setRemoteAddr(remoteAddress);
        return policy.matchesTokenMint(request);
    }

    private static MembershipPaymentLoadtestRequestPolicy inferencePolicy() {
        return new MembershipPaymentLoadtestRequestPolicy(
                new MembershipPaymentLoadtestProperties(
                        true, List.of(73014701344296960L)),
                new MembershipPaymentLoadtestInferenceStubProperties(
                        true,
                        "https://sandbox.example.test/video.mp4"));
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
