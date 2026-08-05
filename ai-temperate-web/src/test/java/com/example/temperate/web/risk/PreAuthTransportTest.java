package com.example.temperate.web.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.risk.domain.RiskScope;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证普通用户与管理员 PreAuth 使用会话 Cookie，并在显式终止时按原作用域清理。
 */
class PreAuthTransportTest {

    private final PreAuthTransport transport = new PreAuthTransport();

    @Test
    void writesUserPreAuthAsHostOnlySessionCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        transport.writeCookie(response, RiskScope.USER, "user-preauth");

        assertSessionCookie(response, PreAuthTransport.USER_COOKIE, "user-preauth");
    }

    @Test
    void writesAdministratorPreAuthAsHostOnlySessionCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        transport.writeCookie(response, RiskScope.ADMIN, "admin-preauth");

        assertSessionCookie(response, PreAuthTransport.ADMIN_COOKIE, "admin-preauth");
    }

    @Test
    void explicitlyClearsBothPreAuthScopesWithZeroMaxAge() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        transport.clearCookie(response, RiskScope.USER);
        transport.clearCookie(response, RiskScope.ADMIN);

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(value -> assertThat(value)
                        .startsWith(PreAuthTransport.USER_COOKIE + "=")
                        .contains("Path=/")
                        .contains("Max-Age=0")
                        .contains("Secure")
                        .contains("HttpOnly")
                        .contains("SameSite=Strict")
                        .doesNotContain("Domain="))
                .anySatisfy(value -> assertThat(value)
                        .startsWith(PreAuthTransport.ADMIN_COOKIE + "=")
                        .contains("Path=/")
                        .contains("Max-Age=0")
                        .contains("Secure")
                        .contains("HttpOnly")
                        .contains("SameSite=Strict")
                        .doesNotContain("Domain="));
    }

    private static void assertSessionCookie(
            MockHttpServletResponse response,
            String name,
            String value) {
        List<String> cookies = List.copyOf(response.getHeaders(HttpHeaders.SET_COOKIE));
        assertThat(cookies).singleElement().satisfies(cookie -> assertThat(cookie)
                .startsWith(name + "=" + value)
                .contains("Path=/")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Max-Age")
                .doesNotContain("Expires")
                .doesNotContain("Domain="));
    }
}
