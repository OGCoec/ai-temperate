package com.example.temperate.web.admin.mailinspection;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.web.admin.api.AdminExceptionLogger;
import com.example.temperate.web.admin.api.AdminWebExceptionHandler;
import com.example.temperate.web.admin.mailinspection.sse.MailInspectionSseService;
import com.example.temperate.web.admin.security.AdminClientPlatformResolver;
import com.example.temperate.web.admin.security.AdminSessionAuthenticationInterceptor;
import com.example.temperate.web.admin.transport.AdminCookieWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 验证邮箱检查 SSE 建连前发现权威任务缺失时，会协商为稳定的 404 JSON，而不是再次触发媒体类型异常。
 */
final class AdminMailInspectionSseErrorResponseTest {

    private static final String JOB_ID = "AZ9nEjRWeJCrze8SNFZ4kA";

    @Test
    void returnsJsonNotFoundWhenClientAcceptsStreamAndJson() throws Exception {
        MailInspectionSseService service = mock(MailInspectionSseService.class);
        when(service.connect(anyString(), isNull(), anyString()))
                .thenThrow(new AdminException(
                        AdminErrorCode.ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND,
                        "mail inspection job not found"));
        AdminWebExceptionHandler exceptionHandler =
                new AdminWebExceptionHandler(
                        Clock.fixed(
                                Instant.parse("2026-07-29T12:00:00Z"),
                                ZoneOffset.UTC),
                        mock(AdminCookieWriter.class),
                        new AdminClientPlatformResolver(),
                        mock(AdminExceptionLogger.class));
        DefaultFormattingConversionService conversionService =
                new DefaultFormattingConversionService();
        conversionService.addConverter(
                String.class,
                MailInspectionJobPublicId.class,
                MailInspectionJobPublicId::new);
        // standalone MockMvc 不加载 Boot 的时间序列化默认值，此处显式复现真实应用的 ISO-8601 HTTP 契约。
        MappingJackson2HttpMessageConverter jsonConverter =
                new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(
                                        SerializationFeature
                                                .WRITE_DATES_AS_TIMESTAMPS)
                                .build());
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminMailInspectionSseController(service))
                .setControllerAdvice(exceptionHandler)
                .setConversionService(conversionService)
                .setMessageConverters(jsonConverter)
                .build();

        mockMvc.perform(get(
                        "/api/admin/mail-inspection/jobs/{jobId}/events",
                        JOB_ID)
                        .accept(
                                MediaType.TEXT_EVENT_STREAM,
                                MediaType.APPLICATION_JSON)
                        .requestAttr(
                                AdminSessionAuthenticationInterceptor
                                        .RAW_TOKEN_ATTRIBUTE,
                                "test-admin-session"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        containsString("no-store")))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        containsString("private")))
                .andExpect(jsonPath("$.code").value(
                        "ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(
                        "原检查任务已过期或不存在，请重新创建检查任务。"))
                .andExpect(jsonPath("$.timestamp").value(
                        "2026-07-29T12:00:00Z"));
    }
}
