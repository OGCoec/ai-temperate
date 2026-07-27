package com.example.temperate.common.aliyun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.models.PresignResult;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.models.PutObjectResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 验证头像预签名上传固定签入私有 ACL、禁止覆盖和 Content-Type。
 */
class AliyunOssUtilsTest {

    @Test
    void uploadsPublicImageWithRevalidationAndExplicitOverwritePolicy() {
        OSSClient client = mock(OSSClient.class);
        when(client.putObject(any(PutObjectRequest.class)))
                .thenReturn(PutObjectResult.newBuilder().build());
        AliyunOssUtils utils = new AliyunOssUtils((region, endpoint) -> client);

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
        AliyunOssUtils utils = new AliyunOssUtils((region, endpoint) -> client);

        var result = utils.generatePresignedPutUrl(
                "ihaveaplan",
                "us-west-1",
                "https://oss-us-west-1.aliyuncs.com",
                "ai-temperate/user/temp/AAAAAAAAJxE/image.webp",
                "image/webp",
                Duration.ofMinutes(10));

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).presign(request.capture(), any());
        assertThat(request.getValue().objectAcl()).isEqualTo("private");
        assertThat(request.getValue().forbidOverwrite()).isTrue();
        assertThat(request.getValue().contentType()).isEqualTo("image/webp");
        assertThat(result.method()).isEqualTo("PUT");
    }
}
