package com.example.temperate.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelChunk;
import com.example.temperate.service.user.aiconversation.response.AiConversationStreamBatcher;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 使用隔离的嵌入式 Tomcat 和假模型验证 MVC 会在流完成前逐段写出 SSE，而不是缓存完整回答后一次返回。
 */
@SpringBootTest(
        classes = MvcAsyncSseStreamingIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=mvc-stream-test",
            "server.ssl.enabled=false",
            "app.web.mvc-async.core-pool-size=2",
            "app.web.mvc-async.max-pool-size=8",
            "app.web.mvc-async.queue-capacity=0",
            "app.web.mvc-async.keep-alive=1s",
            "app.web.mvc-async.timeout=2s"
        })
final class MvcAsyncSseStreamingIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void sendsDeltasAtSeparateArrivalTimesBeforeCompletion() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/test/sse"))
                .timeout(Duration.ofSeconds(3))
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(value -> assertThat(value)
                        .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));

        List<String> received = new ArrayList<>();
        List<Long> arrivalNanos = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && received.size() < 3) {
                if (line.startsWith("data:")) {
                    received.add(line.substring("data:".length()));
                    arrivalNanos.add(System.nanoTime());
                }
            }
        }

        assertThat(received).containsExactly("one", "two", "three");
        // 首帧可能与响应头共同刷新而产生调度抖动；首尾跨度仍能可靠区分逐段写出与完成后一次性返回。
        assertThat(Duration.ofNanos(arrivalNanos.get(2) - arrivalNanos.get(0)))
                .isGreaterThanOrEqualTo(Duration.ofMillis(80));
    }

    /**
     * 只装配 MVC、专用异步执行器和本地测试 Controller，排除数据库与认证自动配置以保证测试隔离。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class,
        ManagementWebSecurityAutoConfiguration.class
    })
    @Import({MvcAsyncStreamingConfiguration.class, TestSseController.class})
    static class TestApplication {

        @Bean
        AiInferenceProperties aiInferenceProperties() {
            return new AiInferenceProperties(
                    false,
                    "https://cli-proxy.example.test/v1",
                    "",
                    Duration.ofSeconds(1));
        }
    }

    /**
     * 生成三个有明确时间间隔的本地片段，用于观测真实 Servlet 网络写出边界。
     */
    @RestController
    static class TestSseController {

        @GetMapping(value = "/test/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        Flux<ServerSentEvent<String>> stream() {
            Flux<AiConversationModelChunk> fakeModel = Flux.just(
                            new AiConversationModelChunk("one", null, null, null),
                            new AiConversationModelChunk("two", null, null, null),
                            new AiConversationModelChunk("three", null, null, null))
                    .delayElements(Duration.ofMillis(120));
            return AiConversationStreamBatcher.forwardWhileBatching(
                            fakeModel,
                            4096,
                            Duration.ofSeconds(1),
                            ignored -> {
                            })
                    .map(chunk -> ServerSentEvent.builder(chunk.text())
                            .event("delta")
                            .build());
        }
    }
}
