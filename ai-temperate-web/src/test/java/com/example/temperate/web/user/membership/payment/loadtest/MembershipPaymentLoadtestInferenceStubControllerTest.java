package com.example.temperate.web.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * 该测试是来锁定 W16 回环推理替身只返回最小 OpenAI/xAI 协议响应，并拒绝远程来源和错误代理凭据。
 */
final class MembershipPaymentLoadtestInferenceStubControllerTest {

    private static final String KEY = "loadtest-inference-key";
    private static final String VIDEO_URL =
            "https://ihaveaplan.oss-us-west-1.aliyuncs.com/loadtest/video.mp4";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsDeterministicChatImageAndVideoProtocolResponses() {
        MembershipPaymentLoadtestInferenceStubController controller = controller();
        MockHttpServletRequest request = loopbackRequest();
        ObjectNode jsonChat = objectMapper.createObjectNode().put("stream", false);

        var chat = controller.chat(jsonChat, "Bearer " + KEY, request);
        var image = controller.image(
                objectMapper.createObjectNode(), "Bearer " + KEY, request);
        MockHttpServletRequest xaiRequest = loopbackRequest();
        xaiRequest.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        var xaiImage = controller.image(
                objectMapper.createObjectNode(), "Bearer " + KEY, xaiRequest);
        var videoStart = controller.startVideo(
                objectMapper.createObjectNode(), "Bearer " + KEY, request);
        var videoPoll = controller.pollVideo(
                "loadtest-video-request", "Bearer " + KEY, request);

        assertThat(chat.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(chat.getBody()).isInstanceOf(ObjectNode.class);
        assertThat(((ObjectNode) chat.getBody()).path("usage")
                .path("total_tokens").asInt()).isEqualTo(2);
        assertThat(image.getHeaders().getContentType())
                .isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(xaiImage.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(((ObjectNode) xaiImage.getBody()).path("data").size())
                .isEqualTo(1);
        assertThat(videoStart.getBody().path("request_id").asText())
                .isEqualTo("loadtest-video-request");
        assertThat(videoPoll.getBody().path("status").asText()).isEqualTo("done");
        assertThat(videoPoll.getBody().path("video").path("url").asText())
                .isEqualTo(VIDEO_URL);
    }

    @Test
    void rejectsRemoteRequestsAndInvalidProxyCredentials() {
        MembershipPaymentLoadtestInferenceStubController controller = controller();
        MockHttpServletRequest remote = new MockHttpServletRequest();
        remote.setRemoteAddr("198.51.100.20");

        assertThatThrownBy(() -> controller.chat(
                objectMapper.createObjectNode(), "Bearer " + KEY, remote))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        assertThatThrownBy(() -> controller.chat(
                objectMapper.createObjectNode(), "Bearer wrong", loopbackRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
    }

    @Test
    void isRegisteredOnlyForSharedBarLoadtestProfile() {
        Profile profile = MembershipPaymentLoadtestInferenceStubController.class
                .getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("loadtest-bar");
    }

    private MembershipPaymentLoadtestInferenceStubController controller() {
        return new MembershipPaymentLoadtestInferenceStubController(
                new MembershipPaymentLoadtestInferenceStubProperties(true, VIDEO_URL),
                new AiInferenceProperties(
                        true,
                        "http://127.0.0.1:18080/internal/test/membership-payments/inference-stub",
                        KEY,
                        Duration.ofMinutes(1)),
                objectMapper);
    }

    private static MockHttpServletRequest loopbackRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
