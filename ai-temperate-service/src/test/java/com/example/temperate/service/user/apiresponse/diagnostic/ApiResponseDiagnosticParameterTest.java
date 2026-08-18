package com.example.temperate.service.user.apiresponse.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定 Responses 已知字段的低基数日志白名单，并确保客户端自定义字段不会原样进入日志。
 */
final class ApiResponseDiagnosticParameterTest {

    @Test
    void keepsKnownResponseParametersAndBoundedItemPaths() {
        assertThat(ApiResponseDiagnosticParameter.sanitize("max_output_tokens"))
                .isEqualTo("max_output_tokens");
        assertThat(ApiResponseDiagnosticParameter.sanitize("reasoning.effort"))
                .isEqualTo("reasoning.effort");
        assertThat(ApiResponseDiagnosticParameter.sanitize("input[12].summary[2].text"))
                .isEqualTo("input[12].summary[2].text");
        assertThat(ApiResponseDiagnosticParameter.sanitize("tools[3].description"))
                .isEqualTo("tools[3].description");
    }

    @Test
    void redactsClientControlledAndUnboundedParameters() {
        assertThat(ApiResponseDiagnosticParameter.sanitize("client-secret-canary"))
                .isEqualTo("unsupported");
        assertThat(ApiResponseDiagnosticParameter.sanitize("input[10000].content"))
                .isEqualTo("unsupported");
        assertThat(ApiResponseDiagnosticParameter.sanitize("tools[1].client-field"))
                .isEqualTo("unsupported");
    }
}
