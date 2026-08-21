package com.example.temperate.web.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestToken;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestTokenService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 该测试是来锁定本机 Token 签发入口只接受回环地址，并把应用签发的短期令牌交给 Runner 使用。
 */
final class MembershipPaymentLoadtestTokenControllerTest {

    private MembershipPaymentLoadtestTokenService tokenService;
    private MembershipPaymentLoadtestTokenController controller;

    @BeforeEach
    void setUp() {
        tokenService = mock(MembershipPaymentLoadtestTokenService.class);
        controller = new MembershipPaymentLoadtestTokenController(tokenService);
    }

    @Test
    void loopbackRequestReturnsSignedTokensWithoutLoggingOrPersistingThem() {
        when(tokenService.issueForAllowlistedUsers()).thenReturn(List.of(
                new MembershipPaymentLoadtestToken(73014701344296960L, "signed-token")));
        when(tokenService.issueExpiredToken()).thenReturn("expired-signed-token");
        when(tokenService.issueNonAllowlistedToken()).thenReturn(
                new MembershipPaymentLoadtestToken(Long.MAX_VALUE, "non-allowlisted-token"));
        MockHttpServletRequest request = request("127.0.0.1");

        ResponseEntity<MembershipPaymentLoadtestTokenController.Response> response =
                controller.issueTokens(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().users()).singleElement()
                .extracting(
                        MembershipPaymentLoadtestTokenController.UserToken::userId,
                        MembershipPaymentLoadtestTokenController.UserToken::accessToken)
                .containsExactly(73014701344296960L, "signed-token");
        assertThat(response.getBody().expiredAccessToken()).isEqualTo("expired-signed-token");
        assertThat(response.getBody().nonAllowlistedUser().accessToken())
                .isEqualTo("non-allowlisted-token");
    }

    @Test
    void nonLoopbackRequestIsForbiddenBeforeTokenIssuance() {
        MockHttpServletRequest request = request("192.0.2.10");

        assertThatThrownBy(() -> controller.issueTokens(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                });
        verifyNoInteractions(tokenService);
    }

    private static MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "unused");
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
