package com.example.temperate.web.auth.phonecountry.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.temperate.service.auth.phonecountry.service.PhoneCountryResolutionService;
import com.example.temperate.web.auth.phonecountry.component.TrustedClientIpResolver;
import com.example.temperate.web.auth.phonecountry.config.properties.PhoneCountryProperties;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 验证电话国家建议接口的最小响应、无结果降级及无缓存策略的测试。
 */
class PhoneCountryControllerTest {

    private PhoneCountryResolutionService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(PhoneCountryResolutionService.class);
        TrustedClientIpResolver resolver = new TrustedClientIpResolver(
                new PhoneCountryProperties(true, "unused.bin", "127.0.0.1/32"));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PhoneCountryController(service, resolver))
                .build();
    }

    @Test
    void returnsTheResolvedCountryWithoutExposingLocationDetails() throws Exception {
        when(service.resolveCountryIso2("8.8.8.8")).thenReturn(Optional.of("US"));

        mockMvc.perform(get("/api/auth/phone-country")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        })
                        .header("CF-Connecting-IP", "8.8.8.8"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(jsonPath("$.resolved").value(true))
                .andExpect(jsonPath("$.countryIso2").value("US"))
                .andExpect(jsonPath("$.ip").doesNotExist())
                .andExpect(jsonPath("$.city").doesNotExist())
                .andExpect(jsonPath("$.latitude").doesNotExist());

        verify(service).resolveCountryIso2("8.8.8.8");
    }

    @Test
    void returnsUnresolvedWithoutLookupWhenTrustedProxyHeaderIsMissing() throws Exception {
        mockMvc.perform(get("/api/auth/phone-country")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(false))
                .andExpect(jsonPath("$.countryIso2").value((Object) null));

        verifyNoInteractions(service);
    }

    @Test
    void returnsUnresolvedWithoutLookupWhenCloudflareHeaderIsNotPublic() throws Exception {
        mockMvc.perform(get("/api/auth/phone-country")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        })
                        .header("CF-Connecting-IP", "198.18.0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(false))
                .andExpect(jsonPath("$.countryIso2").value((Object) null))
                .andExpect(jsonPath("$.ip").doesNotExist());

        verifyNoInteractions(service);
    }
}
