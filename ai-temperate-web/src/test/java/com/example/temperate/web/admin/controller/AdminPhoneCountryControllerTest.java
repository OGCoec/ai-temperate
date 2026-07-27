package com.example.temperate.web.admin.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.temperate.service.auth.phonecountry.service.PhoneCountryResolutionService;
import com.example.temperate.service.auth.phonecountry.service.exception.PhoneCountryTimeoutException;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.web.auth.api.GlobalExceptionHandler;
import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Mono;

/**
 * 验证管理员手机号国家建议接口使用已验签公网 IP，并保持登录前可用的最小无缓存响应。
 */
class AdminPhoneCountryControllerTest {

    private static final String VERIFIED_CLIENT_IP = "130.131.4.13";
    private static final String WORKER_SUBREQUEST_IP = "2a06:98c0:3600::103";

    private PhoneCountryResolutionService service;
    private RiskRequestContextResolver resolver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(PhoneCountryResolutionService.class);
        resolver = mock(RiskRequestContextResolver.class);
        GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler(
                Clock.systemUTC(),
                mock(AuthCookieWriter.class),
                mock(AuthFlowCookieWriter.class));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminPhoneCountryController(service, resolver))
                .setControllerAdvice(exceptionHandler)
                .build();
    }

    @Test
    void resolvesCountryFromVerifiedClientIpWithoutAdminSession() throws Exception {
        when(resolver.resolve(any())).thenReturn(Optional.of(observation()));
        when(service.resolveCountryIso2(VERIFIED_CLIENT_IP))
                .thenReturn(Mono.just(Optional.of("US")));

        MvcResult pending = mockMvc.perform(get("/api/admin/auth/phone-country")
                        .header("Origin", "https://admin.niko000o.site")
                        .header("CF-Connecting-IP", WORKER_SUBREQUEST_IP))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(jsonPath("$.resolved").value(true))
                .andExpect(jsonPath("$.countryIso2").value("US"))
                .andExpect(jsonPath("$.ip").doesNotExist())
                .andExpect(jsonPath("$.city").doesNotExist())
                .andExpect(jsonPath("$.latitude").doesNotExist());

        verify(service).resolveCountryIso2(VERIFIED_CLIENT_IP);
    }

    @Test
    void returnsUnresolvedWithoutLookupWhenTrustedObservationIsMissing() throws Exception {
        when(resolver.resolve(any())).thenReturn(Optional.empty());

        MvcResult pending = mockMvc.perform(get("/api/admin/auth/phone-country")
                        .header("Origin", "https://admin.niko000o.site")
                        .header("CF-Connecting-IP", WORKER_SUBREQUEST_IP))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(false))
                .andExpect(jsonPath("$.countryIso2").value((Object) null));

        verifyNoInteractions(service);
    }

    @Test
    void returnsStableTimeoutResponseForVerifiedClientIp() throws Exception {
        when(resolver.resolve(any())).thenReturn(Optional.of(observation()));
        when(service.resolveCountryIso2(VERIFIED_CLIENT_IP))
                .thenReturn(Mono.error(new PhoneCountryTimeoutException()));

        MvcResult pending = mockMvc.perform(get("/api/admin/auth/phone-country")
                        .header("Origin", "https://admin.niko000o.site")
                        .header("CF-Connecting-IP", WORKER_SUBREQUEST_IP))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("PHONE_COUNTRY_TIMEOUT"))
                .andExpect(jsonPath("$.message").value("国家或地区识别超时，请手动选择。"));
    }

    private static TrustedNetworkObservation observation() {
        return new TrustedNetworkObservation(
                VERIFIED_CLIENT_IP,
                "US",
                null,
                null,
                null,
                Instant.parse("2026-07-26T00:00:00Z"));
    }
}
