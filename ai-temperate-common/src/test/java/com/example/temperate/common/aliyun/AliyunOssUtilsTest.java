package com.example.temperate.common.aliyun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.models.PresignResult;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.models.PutObjectResult;
import com.aliyun.sdk.service.oss2.progress.ProgressListener;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 验证 OSS 上传请求能够固定安全元数据，并正确处理 SDK 的内容长度边界。
 */
class AliyunOssUtilsTest {

    @Test
    void uploadsPublicImageWithRevalidationAndExplicitOverwritePolicy() {
        OSSClient client = mock(OSSClient.class);
        when(client.putObject(any(PutObjectRequest.class)))
                .thenReturn(PutObjectResult.newBuilder().build());
        AliyunOssUtils utils = new AliyunOssUtils(
                (region, endpoint, connectTimeout, readWriteTimeout) -> client);

        utils.putObjectBytes(
                "ihaveaplan",
                "us-west-1",
                "https://oss-us-west-1.aliyuncs.com",
                "ai-temperate/models/icons/openai.png",
                new byte[] {1, 2, 3},
                "image/png",
                "public, max-age=0, must-revalidate",
                true);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture());
        assertThat(request.getValue().objectAcl()).isEqualTo("public-read");
        assertThat(request.getValue().forbidOverwrite()).isTrue();
        assertThat(request.getValue().contentType()).isEqualTo("image/png");
        assertThat(request.getValue().cacheControl())
                .isEqualTo("public, max-age=0, must-revalidate");
        assertThat(request.getValue().body().toBytes()).containsExactly(1, 2, 3);
    }

    @Test
    void bindsSuppliedProgressListenerToTheActualPutRequest() {
        OSSClient client = mock(OSSClient.class);
        when(client.putObject(any(PutObjectRequest.class)))
                .thenReturn(PutObjectResult.newBuilder().build());
        AliyunOssUtils utils = new AliyunOssUtils(
                (region, endpoint, connectTimeout, readWriteTimeout) -> client);
        ProgressListener listener = mock(ProgressListener.class);

        utils.putObjectBytes(
                "ihaveaplan",
                "us-west-1",
                "https://oss-us-west-1.aliyuncs.com",
                "ai-temperate/messages/AAAAAAAAJxE/generated.webp",
                new byte[] {1, 2, 3},
                "image/webp",
                "public, max-age=31536000, immutable",
                true,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                listener);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture());
        assertThat(request.getValue().progressListener()).isSameAs(listener);
    }

    @Test
    void signsPrivateNonOverwritingPutWithExpectedContentType() {
        OSSClient client = mock(OSSClient.class);
        when(client.presign(any(PutObjectRequest.class), any()))
                .thenReturn(PresignResult.newBuilder()
                        .url("https://signed.example/upload")
                        .method("PUT")
                        .expiration(Instant.parse("2026-07-26T12:10:00Z"))
                        .signedHeaders(Map.of(
                                "Content-Type", "image/webp",
                                "x-oss-object-acl", "private",
                                "x-oss-forbid-overwrite", "true"))
                        .build());
        AliyunOssUtils utils = new AliyunOssUtils(
                (region, endpoint, connectTimeout, readWriteTimeout) -> client);

        var result = utils.generatePresignedPutUrl(
                "ihaveaplan",
                "us-west-1",
                "https://oss-us-west-1.aliyuncs.com",
                "ai-temperate/user/temp/AAAAAAAAJxE/image.webp",
                "image/webp",
                12L,
                Duration.ofMinutes(10));

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).presign(request.capture(), any());
        assertThat(request.getValue().objectAcl()).isEqualTo("private");
        assertThat(request.getValue().forbidOverwrite()).isTrue();
        assertThat(request.getValue().contentType()).isEqualTo("image/webp");
        assertThat(request.getValue().contentLength()).isEqualTo(12);
        assertThat(result.method()).isEqualTo("PUT");
    }

    @Test
    void rejectsContentLengthBeyondSdkIntegerLimit() {
        OSSClient client = mock(OSSClient.class);
        AliyunOssUtils utils = new AliyunOssUtils(
                (region, endpoint, connectTimeout, readWriteTimeout) -> client);

        assertThatThrownBy(() -> utils.generatePresignedPutUrl(
                        "ihaveaplan",
                        "us-west-1",
                        "https://oss-us-west-1.aliyuncs.com",
                        "ai-temperate/user/temp/AAAAAAAAJxE/image.webp",
                        "image/webp",
                        (long) Integer.MAX_VALUE + 1L,
                        Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("contentLength must be between 1 and Integer.MAX_VALUE");
    }
}
