package com.example.temperate.web.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.service.admin.aimodel.discovery.dto.CliProxyModelDiscoveryResult;
import com.example.temperate.service.admin.aimodel.discovery.service.CliProxyModelDiscoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 验证 CLIProxyAPI 模型发现只暴露管理员 GET 路由、无请求体且响应禁止缓存。
 */
final class AdminCliProxyModelDiscoveryControllerContractTest {

    @Test
    void exposesFixedAdminGetRouteWithoutRequestBody() {
        RequestMapping mapping = AdminCliProxyModelDiscoveryController.class
                .getAnnotation(RequestMapping.class);
        assertThat(mapping.value())
                .containsExactly("/api/admin/ai-model-sources/cli-proxy");

        Method discover = Arrays.stream(
                        AdminCliProxyModelDiscoveryController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .findFirst()
                .orElseThrow();
        assertThat(discover.getAnnotation(GetMapping.class).value())
                .containsExactly("/models");
        assertThat(Arrays.stream(discover.getParameters())
                .noneMatch(parameter -> parameter.isAnnotationPresent(RequestBody.class)))
                .isTrue();
        assertThat(AdminCliProxyModelDiscoveryController.class.getAnnotation(Tag.class))
                .isNotNull();
        assertThat(discover.getAnnotation(Operation.class)).isNotNull();
    }

    @Test
    void delegatesToServiceAndReturnsPrivateNoStore() {
        CliProxyModelDiscoveryService service =
                mock(CliProxyModelDiscoveryService.class);
        CliProxyModelDiscoveryResult result = new CliProxyModelDiscoveryResult(
                "CLI_PROXY",
                Instant.parse("2026-07-29T15:30:00Z"),
                0,
                List.of());
        when(service.discoverModels()).thenReturn(result);

        ResponseEntity<CliProxyModelDiscoveryResult> response =
                new AdminCliProxyModelDiscoveryController(service).models();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl())
                .contains("private")
                .contains("no-store");
        assertThat(response.getBody()).isSameAs(result);
    }
}
