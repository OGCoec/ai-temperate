package com.example.temperate.web.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBoundaryFixtureService;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBoundaryFixtureState;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBoundaryLoadtestPolicy;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBoundaryTokenService;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestToken;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentSegmentWarmupResetState;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * 该测试是来锁定八万用户夹具、重置和分页 Token 接口仅供回环 Runner 使用且响应禁止缓存。
 */
final class MembershipPaymentBoundaryLoadtestControllerTest {

    private MembershipPaymentBoundaryFixtureService fixtureService;
    private MembershipPaymentBoundaryTokenService tokenService;
    private HybridBase64UrlCodec orderIdCodec;
    private MembershipPaymentBoundaryLoadtestController controller;

    @BeforeEach
    void setUp() {
        fixtureService = mock(MembershipPaymentBoundaryFixtureService.class);
        tokenService = mock(MembershipPaymentBoundaryTokenService.class);
        orderIdCodec = mock(HybridBase64UrlCodec.class);
        controller = new MembershipPaymentBoundaryLoadtestController(
                fixtureService, tokenService, orderIdCodec);
    }

    @Test
    void loopbackPrepareAndStateReturnOnlyCountsWithNoStore() {
        MembershipPaymentBoundaryFixtureState state = state(true, 80_000, 0);
        when(fixtureService.prepare()).thenReturn(state);
        when(fixtureService.state()).thenReturn(state);

        var prepared = controller.prepare(request("POST", "127.0.0.1"));
        var current = controller.state(request("GET", "::1"));

        assertThat(prepared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prepared.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(prepared.getBody()).isEqualTo(state);
        assertThat(current.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(current.getBody()).isEqualTo(state);
    }

    @Test
    void resetDecodesOnlySubmittedCanonicalOrderIdsAndReturnsCounts() {
        byte[] first = new byte[16];
        first[15] = 1;
        byte[] second = new byte[16];
        second[15] = 2;
        when(orderIdCodec.decode("order-one")).thenReturn(first);
        when(orderIdCodec.decode("order-two")).thenReturn(second);
        MembershipPaymentBoundaryFixtureState state = state(true, 80_000, 0);
        when(fixtureService.reset(List.of(first, second))).thenReturn(state);

        var response = controller.reset(
                request("POST", "127.0.0.1"),
                new MembershipPaymentBoundaryLoadtestController.ResetRequest(
                        List.of("order-one", "order-two")));

        assertThat(response.getBody()).isEqualTo(state);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(fixtureService).reset(List.of(first, second));
    }

    @Test
    void failedRunResetUsesItsDedicatedServiceContractAndKeepsNoStore() {
        byte[] orderId = new byte[16];
        orderId[15] = 3;
        when(orderIdCodec.decode("failed-order")).thenReturn(orderId);
        MembershipPaymentBoundaryFixtureState state = state(true, 80_000, 0);
        when(fixtureService.resetFailedRun(List.of(orderId))).thenReturn(state);

        var response = controller.resetFailedRun(
                request("POST", "127.0.0.1"),
                new MembershipPaymentBoundaryLoadtestController.ResetRequest(
                        List.of("failed-order")));

        assertThat(response.getBody()).isEqualTo(state);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(fixtureService).resetFailedRun(List.of(orderId));
    }

    @Test
    void segmentWarmupResetPassesOnlyFixedScaleGroupRunAndDecodedManifest() {
        byte[] orderId = new byte[16];
        orderId[15] = 4;
        when(orderIdCodec.decode("warmup-order")).thenReturn(orderId);
        MembershipPaymentSegmentWarmupResetState state =
                new MembershipPaymentSegmentWarmupResetState(
                        "PERFORMANCE_40K",
                        "E-P1",
                        "warmup-e-p1-attempt-1",
                        5_000,
                        5_000,
                        5_000,
                        0,
                        0,
                        0,
                        0);
        when(fixtureService.resetSegmentWarmup(
                MembershipPaymentBoundaryLoadtestPolicy.RunScale.PERFORMANCE_40K,
                "E-P1",
                "warmup-e-p1-attempt-1",
                List.of(orderId))).thenReturn(state);

        var response = controller.resetSegmentWarmup(
                request("POST", "127.0.0.1"),
                new MembershipPaymentBoundaryLoadtestController.SegmentWarmupResetRequest(
                        MembershipPaymentBoundaryLoadtestPolicy.RunScale.PERFORMANCE_40K,
                        "E-P1",
                        "warmup-e-p1-attempt-1",
                        List.of("warmup-order")));

        assertThat(response.getBody()).isEqualTo(state);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(fixtureService).resetSegmentWarmup(
                MembershipPaymentBoundaryLoadtestPolicy.RunScale.PERFORMANCE_40K,
                "E-P1",
                "warmup-e-p1-attempt-1",
                List.of(orderId));
    }

    @Test
    void tokenEndpointReturnsExactlyOneSelectedPageWithNoStore() {
        when(tokenService.issuePage(7)).thenReturn(List.of(
                new MembershipPaymentLoadtestToken(70_000_000_000_003_500L, "secret-token")));

        var response = controller.tokens(request("POST", "127.0.0.1"), 7);

        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).singleElement()
                .extracting(MembershipPaymentLoadtestToken::userId)
                .isEqualTo(70_000_000_000_003_500L);
    }

    @Test
    void resetManifestAcceptsExactlyEightyThousandOrdersButNoMore() {
        assertThat(new MembershipPaymentBoundaryLoadtestController.ResetRequest(
                java.util.Collections.nCopies(80_000, "order-id")).orderIds())
                .hasSize(80_000);
        assertThatThrownBy(() -> new MembershipPaymentBoundaryLoadtestController.ResetRequest(
                java.util.Collections.nCopies(80_001, "order-id")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void segmentWarmupManifestNeverAcceptsMoreThanTenThousandOrders() {
        assertThat(new MembershipPaymentBoundaryLoadtestController.SegmentWarmupResetRequest(
                MembershipPaymentBoundaryLoadtestPolicy.RunScale.CAPACITY_80K,
                "H-AR",
                "warmup-h-ar-attempt-1",
                java.util.Collections.nCopies(10_000, "order-id")).orderIds())
                .hasSize(10_000);
        assertThatThrownBy(() ->
                new MembershipPaymentBoundaryLoadtestController.SegmentWarmupResetRequest(
                        MembershipPaymentBoundaryLoadtestPolicy.RunScale.CAPACITY_80K,
                        "H-AR",
                        "warmup-h-ar-attempt-1",
                        java.util.Collections.nCopies(10_001, "order-id")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void remoteRequestIsRejectedBeforeAnyServiceOrCodecCall() {
        MockHttpServletRequest remote = request("POST", "198.51.100.7");

        assertThatThrownBy(() -> controller.prepare(remote))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> controller.tokens(remote, 0))
                .isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(fixtureService, tokenService, orderIdCodec);
    }

    private static MembershipPaymentBoundaryFixtureState state(
            boolean prepared, int identities, int orders) {
        return new MembershipPaymentBoundaryFixtureState(
                prepared, identities, identities, identities, orders, 0);
    }

    private static MockHttpServletRequest request(String method, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "unused");
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
