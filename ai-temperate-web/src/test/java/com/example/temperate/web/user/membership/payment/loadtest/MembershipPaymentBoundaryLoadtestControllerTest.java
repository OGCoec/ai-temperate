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
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBoundaryTokenService;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestToken;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * 该测试是来锁定四万用户夹具、重置和分页 Token 接口仅供回环 Runner 使用且响应禁止缓存。
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
        MembershipPaymentBoundaryFixtureState state = state(true, 40_000, 0);
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
        MembershipPaymentBoundaryFixtureState state = state(true, 40_000, 0);
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
    void resetManifestAcceptsExactlyFortyThousandOrdersButNoMore() {
        assertThat(new MembershipPaymentBoundaryLoadtestController.ResetRequest(
                java.util.Collections.nCopies(40_000, "order-id")).orderIds())
                .hasSize(40_000);
        assertThatThrownBy(() -> new MembershipPaymentBoundaryLoadtestController.ResetRequest(
                java.util.Collections.nCopies(40_001, "order-id")))
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
