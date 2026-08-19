package com.example.temperate.web.aiinference;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * 该测试是来锁定公开模型接口按异常类型和响应提交状态识别客户端断开，避免依赖操作系统本地化错误消息。
 */
final class ApiInferenceClientDisconnectClassifierTest {

    @Test
    void classifiesCommittedSseIoWithoutInspectingLocalizedMessage() throws Exception {
        MockHttpServletResponse response = committedResponse("text/event-stream;charset=UTF-8");

        ApiInferenceClientDisconnectClassifier.Result result =
                ApiInferenceClientDisconnectClassifier.classify(
                        new IllegalStateException(
                                "outer-private-detail",
                                new IOException("你的主机中的软件中止了一个已建立的连接")),
                        response);

        assertThat(result).isEqualTo(
                ApiInferenceClientDisconnectClassifier.Result.COMMITTED_SSE_CLIENT_DISCONNECT);
    }

    @Test
    void classifiesSpringAsyncWriteFailureAsCommittedSseDisconnect() throws Exception {
        MockHttpServletResponse response = committedResponse("text/event-stream");

        assertThat(ApiInferenceClientDisconnectClassifier.classify(
                new AsyncRequestNotUsableException("private-message"), response))
                .isEqualTo(ApiInferenceClientDisconnectClassifier.Result
                        .COMMITTED_SSE_CLIENT_DISCONNECT);
    }

    @Test
    void distinguishesCommittedJsonAndUncommittedIoFailures() throws Exception {
        MockHttpServletResponse committedJson = committedResponse("application/json");
        MockHttpServletResponse uncommitted = new MockHttpServletResponse();

        assertThat(ApiInferenceClientDisconnectClassifier.classify(
                new IOException("private-message"), committedJson))
                .isEqualTo(ApiInferenceClientDisconnectClassifier.Result
                        .COMMITTED_RESPONSE_IO_FAILURE);
        assertThat(ApiInferenceClientDisconnectClassifier.classify(
                new IOException("private-message"), uncommitted))
                .isEqualTo(ApiInferenceClientDisconnectClassifier.Result
                        .UNCOMMITTED_IO_FAILURE);
        assertThat(ApiInferenceClientDisconnectClassifier.classify(
                new IllegalArgumentException("not-io"), uncommitted))
                .isEqualTo(ApiInferenceClientDisconnectClassifier.Result.NOT_IO_FAILURE);
    }

    @Test
    void diagnosticClaimIsStableAcrossRepeatedHandlersForOneRequest() {
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest();

        assertThat(ApiInferenceClientDisconnectClassifier.claimDiagnostic(request)).isTrue();
        assertThat(ApiInferenceClientDisconnectClassifier.claimDiagnostic(request)).isFalse();
    }

    private static MockHttpServletResponse committedResponse(String contentType)
            throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType(contentType);
        response.flushBuffer();
        return response;
    }
}
