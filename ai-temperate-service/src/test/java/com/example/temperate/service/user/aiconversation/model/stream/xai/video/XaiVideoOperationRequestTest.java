package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.video.AiConversationVideoAspectRatio;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationOptions;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoResolution;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证五种内部模式只能生成 xAI 官方声明的路径和 JSON 字段，禁止携带 storage_options 或 FPS。
 */
final class XaiVideoOperationRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsTextToVideoRequest() {
        XaiVideoStartRequest request = new XaiTextToVideoOperationStrategy(
                objectMapper).buildRequest(context(
                        AiConversationVideoMode.TEXT_TO_VIDEO,
                        8,
                        AiConversationVideoResolution.P1080,
                        AiConversationVideoAspectRatio.RATIO_16_9,
                        List.of(),
                        List.of()));

        assertThat(request.path()).isEqualTo("/v1/videos/generations");
        assertThat(request.body().path("duration").asInt()).isEqualTo(8);
        assertThat(request.body().path("resolution").asText()).isEqualTo("1080p");
        assertThat(request.body().path("aspect_ratio").asText()).isEqualTo("16:9");
        assertThat(request.body().has("fps")).isFalse();
        assertThat(request.body().has("storage_options")).isFalse();
    }

    @Test
    void buildsImageAndReferenceRequestsWithUrlsOnly() {
        XaiVideoStartRequest image = new XaiImageToVideoOperationStrategy(
                objectMapper).buildRequest(context(
                        AiConversationVideoMode.IMAGE_TO_VIDEO,
                        6,
                        AiConversationVideoResolution.P720,
                        AiConversationVideoAspectRatio.RATIO_9_16,
                        List.of("image-one"),
                        List.of("https://oss.example/image-one.png")));
        XaiVideoStartRequest references =
                new XaiReferenceToVideoOperationStrategy(objectMapper)
                        .buildRequest(context(
                                AiConversationVideoMode.REFERENCE_TO_VIDEO,
                                5,
                                AiConversationVideoResolution.P720,
                                AiConversationVideoAspectRatio.RATIO_1_1,
                                List.of("one", "two"),
                                List.of(
                                        "https://oss.example/one.png",
                                        "https://oss.example/two.png")));

        assertThat(image.body().path("image").path("url").asText())
                .isEqualTo("https://oss.example/image-one.png");
        assertThat(image.body().path("image").has("data")).isFalse();
        assertThat(references.body().path("reference_images").size()).isEqualTo(2);
        assertThat(references.body().path("reference_images").path(0)
                .path("url").asText()).isEqualTo("https://oss.example/one.png");
        assertThat(references.body().has("reference_audios")).isFalse();
    }

    @Test
    void editAndExtendDoNotSendInheritedShapeFields() {
        XaiVideoOperationContext editContext = context(
                AiConversationVideoMode.VIDEO_EDIT,
                0,
                AiConversationVideoResolution.P720,
                null,
                List.of("video"),
                List.of("https://oss.example/source.mp4"));
        XaiVideoOperationContext extendContext = context(
                AiConversationVideoMode.VIDEO_EXTEND,
                4,
                AiConversationVideoResolution.P720,
                null,
                List.of("video"),
                List.of("https://oss.example/source.mp4"));

        XaiVideoStartRequest edit = new XaiVideoEditOperationStrategy(
                objectMapper).buildRequest(editContext);
        XaiVideoStartRequest extend = new XaiVideoExtendOperationStrategy(
                objectMapper).buildRequest(extendContext);

        assertThat(edit.path()).isEqualTo("/v1/videos/edits");
        assertThat(edit.body().has("duration")).isFalse();
        assertThat(edit.body().has("resolution")).isFalse();
        assertThat(edit.body().has("aspect_ratio")).isFalse();
        assertThat(extend.path()).isEqualTo("/v1/videos/extensions");
        assertThat(extend.body().path("duration").asInt()).isEqualTo(4);
        assertThat(extend.body().has("resolution")).isFalse();
        assertThat(extend.body().has("aspect_ratio")).isFalse();
    }

    private static XaiVideoOperationContext context(
            AiConversationVideoMode mode,
            int duration,
            AiConversationVideoResolution resolution,
            AiConversationVideoAspectRatio aspectRatio,
            List<String> attachmentIds,
            List<String> urls) {
        return new XaiVideoOperationContext(
                "grok-imagine-video-1.5",
                "让镜头缓慢向前移动",
                new AiConversationVideoGenerationOptions(
                        mode,
                        duration,
                        resolution,
                        aspectRatio,
                        attachmentIds,
                        mode == AiConversationVideoMode.VIDEO_EDIT
                                || mode == AiConversationVideoMode.VIDEO_EXTEND
                                ? 5_000L : 0L,
                        1280,
                        720,
                        mode == AiConversationVideoMode.VIDEO_EDIT
                                || mode == AiConversationVideoMode.VIDEO_EXTEND
                                ? "h264" : null),
                urls);
    }
}
