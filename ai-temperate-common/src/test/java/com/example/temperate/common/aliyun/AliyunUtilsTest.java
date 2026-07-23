package com.example.temperate.common.aliyun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.sdk.service.dypnsapi20170525.AsyncClient;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody;
import com.aliyun.sdk.service.oss2.OSSAsyncClient;
import com.example.temperate.common.proxy.OutboundRouteResolver;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 验证阿里云公共工具能够构造受控短信请求，并且只向调用方返回经过筛选的供应商结果字段。
 */
class AliyunUtilsTest {

    @Test
    void productionConstructorIsExplicitlySelectedForSpringInjection()
            throws NoSuchMethodException {
        assertThat(AliyunUtils.class.getConstructor(
                        OutboundRouteResolver.class,
                        boolean.class,
                        String.class,
                        int.class,
                        String.class,
                        int.class,
                        String.class,
                        String.class,
                        String.class)
                .isAnnotationPresent(Autowired.class))
                .isTrue();
    }

    @Test
    void smsRequestContainsCodeMinutesRemainingSecondsAndDisabledProviderRetry() throws Exception {
        AsyncClient client = mock(AsyncClient.class);
        when(client.sendSmsVerifyCode(any()))
                .thenReturn(CompletableFuture.completedFuture(response(200, true, "OK", "raw-message")));
        AliyunUtils aliyunUtils = utility(client);

        AliyunUtils.SmsSendResult result = aliyunUtils.sendSmsVerifyCode(
                "+8613800138000",
                "100001",
                "012345",
                Duration.ofSeconds(271));

        ArgumentCaptor<SendSmsVerifyCodeRequest> request =
                ArgumentCaptor.forClass(SendSmsVerifyCodeRequest.class);
        verify(client).sendSmsVerifyCode(request.capture());
        verify(client).close();
        assertThat(request.getValue().getCountryCode()).isEqualTo("86");
        assertThat(request.getValue().getPhoneNumber()).isEqualTo("13800138000");
        assertThat(request.getValue().getSignName()).isEqualTo("测试签名");
        assertThat(request.getValue().getTemplateCode()).isEqualTo("100001");
        assertThat(request.getValue().getTemplateParam())
                .isEqualTo("{\"code\":\"012345\",\"min\":\"5\"}");
        assertThat(request.getValue().getValidTime()).isEqualTo(271L);
        assertThat(request.getValue().getAutoRetry()).isZero();
        assertThat(request.getValue().getReturnVerifyCode()).isFalse();
        assertThat(result.accepted()).isTrue();
    }

    @Test
    void businessFailureResultDoesNotExposeProviderMessageOrRequestPayload() throws Exception {
        AsyncClient client = mock(AsyncClient.class);
        when(client.sendSmsVerifyCode(any()))
                .thenReturn(CompletableFuture.completedFuture(response(
                        200,
                        false,
                        "isv.INVALID_PARAMETERS",
                        "raw-message +8613800138000 012345")));
        AliyunUtils aliyunUtils = utility(client);

        AliyunUtils.SmsSendResult result = aliyunUtils.sendSmsVerifyCode(
                "13800138000", "100001", "012345", "5");

        assertThat(result.accepted()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.providerCode()).isEqualTo("isv.INVALID_PARAMETERS");
        assertThat(result.providerSuccess()).isFalse();
        assertThat(result.requestId()).isEqualTo("request-id");
        assertThat(result.toString())
                .doesNotContain("raw-message")
                .doesNotContain("13800138000")
                .doesNotContain("012345");
    }

    @Test
    void ossUrlBuilderKeepsMigratedHongKongEndpointContract() {
        AliyunUtils aliyunUtils = utility(mock(AsyncClient.class));

        String url = aliyunUtils.buildFileUrl(
                "shopping6655",
                AliyunUtils.HONG_KONG_OSS_ENDPOINT,
                "product/test.png");

        assertThat(url)
                .isEqualTo("https://shopping6655.oss-cn-hongkong.aliyuncs.com/product/test.png");
    }

    @Test
    void migratedOssSurfaceKeepsAllPublicOperations() {
        assertThat(AliyunUtils.class.getMethods())
                .extracting(java.lang.reflect.Method::getName)
                .contains(
                        "uploadFile",
                        "uploadFileToBucket",
                        "uploadContent",
                        "deleteFile",
                        "deleteFileFromBucket",
                        "copyFile",
                        "buildFileUrl");
    }

    @Test
    void ossClientClosesWhenAsynchronousUploadFails() throws Exception {
        AsyncClient smsClient = mock(AsyncClient.class);
        OSSAsyncClient ossClient = mock(OSSAsyncClient.class);
        when(ossClient.putObjectAsync(any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("simulated failure")));
        AliyunUtils aliyunUtils = utility(smsClient, ossClient);

        assertThatThrownBy(() -> aliyunUtils
                        .uploadFile("private/object-key", new byte[] {1})
                        .join())
                .hasCauseInstanceOf(IllegalStateException.class);
        verify(ossClient).close();
    }

    private static AliyunUtils utility(AsyncClient client) {
        return utility(client, mock(OSSAsyncClient.class));
    }

    private static AliyunUtils utility(AsyncClient client, OSSAsyncClient ossClient) {
        OutboundRouteResolver routeResolver = mock(OutboundRouteResolver.class);
        when(routeResolver.selectRoute(any(), any(), anyInt(), any(), anyInt(),
                        any(), anyInt(), any()))
                .thenReturn(new OutboundRouteResolver.RouteSelection(
                        OutboundRouteResolver.RouteTarget.directRoute(), true, "ok", 1L));
        return new AliyunUtils(
                routeResolver,
                true,
                "127.0.0.1",
                7897,
                "auto",
                1500,
                "test-access-key",
                "test-access-secret",
                "测试签名",
                (provider, httpClient) -> client,
                (provider, region, endpoint) -> ossClient);
    }

    private static SendSmsVerifyCodeResponse response(
            int status, boolean success, String code, String message) {
        return SendSmsVerifyCodeResponse.create().toBuilder()
                .headers(Map.of())
                .statusCode(status)
                .body(SendSmsVerifyCodeResponseBody.builder()
                        .success(success)
                        .code(code)
                        .message(message)
                        .requestId("request-id")
                        .build())
                .build();
    }
}
