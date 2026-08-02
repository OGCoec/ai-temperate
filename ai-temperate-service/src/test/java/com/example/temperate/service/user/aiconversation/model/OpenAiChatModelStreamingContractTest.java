package com.example.temperate.service.user.aiconversation.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.temperate.service.user.aiconversation.model.impl.SpringAiCliProxyConversationModelClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.MimeTypeUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * 使用可控本地 SSE 上游验证普通 OpenAI 模型在最终 Usage 到达前已经向业务边界交付文本片段。
 */
final class OpenAiChatModelStreamingContractTest {

    private static final String MODEL = "ait-stream-contract";
    private static final Duration EARLY_CHUNK_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration COMPLETION_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CountDownLatch requestReceived = new CountDownLatch(1);
    private final CountDownLatch releaseTerminalUsage = new CountDownLatch(1);
    private final CountDownLatch streamCompleted = new CountDownLatch(1);
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<Throwable> streamFailure = new AtomicReference<>();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final List<ChatResponse> responses = new CopyOnWriteArrayList<>();

    private HttpServer server;
    private ExecutorService serverExecutor;
    private Disposable subscription;

    @AfterEach
    void stopServer() {
        releaseTerminalUsage.countDown();
        if (subscription != null) {
            subscription.dispose();
        }
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void emitsTextBeforeTerminalUsageAndPreservesRequestAndUsage()
            throws Exception {
        startFakeUpstream();
        OpenAiChatModel model = openAiChatModel();
        Prompt prompt = textPrompt();
        CountDownLatch firstTextReceived = new CountDownLatch(1);

        subscription = ChatClient.create(model)
                .prompt(prompt)
                .stream()
                .chatResponse()
                .subscribe(response -> {
                    responses.add(response);
                    if (response.getResult() != null
                            && response.getResult().getOutput() != null
                            && !response.getResult().getOutput().getText().isEmpty()) {
                        firstTextReceived.countDown();
                    }
                }, failure -> {
                    streamFailure.set(failure);
                    streamCompleted.countDown();
                }, streamCompleted::countDown);

        assertThat(requestReceived.await(
                COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(requestCount.get()).isEqualTo(1);
        // 假上游已 flush 两个文本片但尚未发送终态；普通模型只允许一个片段前视，不能等待整个流结束。
        assertThat(firstTextReceived.await(
                EARLY_CHUNK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(streamCompleted.getCount()).isEqualTo(1L);
        assertRequestContract();

        releaseTerminalUsage.countDown();
        assertThat(streamCompleted.await(
                COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(streamFailure.get()).isNull();
        assertThat(serverFailure.get()).isNull();
        assertFinalResponseContract();
    }

    @Test
    void propagatesFailureBeforeFirstChunk() throws Exception {
        startFakeUpstream(exchange -> {
            try (exchange) {
                captureRequest(exchange);
                exchange.sendResponseHeaders(503, -1);
            }
        });

        assertThatThrownBy(() -> stream(openAiChatModel(), textPrompt())
                .collectList()
                .block(COMPLETION_TIMEOUT))
                .isNotNull();
        assertThat(requestReceived.await(
                COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void propagatesDisconnectAfterDeliveringPartialText() throws Exception {
        startFakeUpstream(this::handlePartialThenDisconnect);
        CountDownLatch firstTextReceived = new CountDownLatch(1);
        CountDownLatch terminated = new CountDownLatch(1);

        subscription = stream(openAiChatModel(), textPrompt())
                .subscribe(response -> {
                    responses.add(response);
                    if (response.getResult() != null
                            && response.getResult().getOutput() != null
                            && !response.getResult().getOutput().getText().isEmpty()) {
                        firstTextReceived.countDown();
                    }
                }, failure -> {
                    streamFailure.set(failure);
                    terminated.countDown();
                }, terminated::countDown);

        assertThat(firstTextReceived.await(
                EARLY_CHUNK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(terminated.await(
                COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(streamFailure.get()).isNotNull();
    }

    @Test
    void completesWithoutInventingUsageWhenTerminalUsageIsMissing()
            throws Exception {
        startFakeUpstream(this::handleStreamWithoutUsage);

        List<ChatResponse> withoutUsage = stream(
                openAiChatModel(), textPrompt())
                .collectList()
                .block(COMPLETION_TIMEOUT);

        assertThat(withoutUsage).isNotNull().isNotEmpty();
        assertThat(withoutUsage).allMatch(response -> {
            Usage usage = response.getMetadata() == null
                    ? null
                    : response.getMetadata().getUsage();
            return usage == null
                    || (usage.getPromptTokens() == 0
                    && usage.getCompletionTokens() == 0);
        });
        ChatResponse finishResponse = withoutUsage.stream()
                .filter(response -> response.getResult() != null)
                .filter(response -> "STOP".equals(
                        response.getResult().getMetadata().getFinishReason()))
                .findFirst()
                .orElseThrow();
        assertThat(extractConversationUsage(finishResponse)).isEmpty();
    }

    @Test
    void serializesSupportedImageAsOpenAiImageUrl() throws Exception {
        startFakeUpstream(this::handleImmediateCompleteStream);
        UserMessage message = UserMessage.builder()
                .text("请描述图片")
                .media(List.of(new Media(
                        MimeTypeUtils.IMAGE_PNG,
                        URI.create("https://media.example.test/image.png"))))
                .build();
        Prompt prompt = new Prompt(
                List.of(message),
                requestOptions());

        List<ChatResponse> imageResponses = stream(openAiChatModel(), prompt)
                .collectList()
                .block(COMPLETION_TIMEOUT);

        assertThat(imageResponses).isNotNull().isNotEmpty();
        JsonNode content = objectMapper.readTree(requestBody.get())
                .path("messages")
                .path(0)
                .path("content");
        assertThat(content.isArray()).isTrue();
        JsonNode imagePart = null;
        for (JsonNode part : content) {
            if ("image_url".equals(part.path("type").asText())) {
                imagePart = part;
                break;
            }
        }
        assertThat(imagePart).isNotNull();
        assertThat(imagePart.path("image_url").path("url").asText())
                .isEqualTo("https://media.example.test/image.png");
    }

    private void startFakeUpstream() throws IOException {
        startFakeUpstream(this::handleChatStream);
    }

    private void startFakeUpstream(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable, "openai-stream-contract-upstream");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverExecutor);
        server.createContext("/v1/chat/completions", handler);
        server.start();
    }

    private OpenAiChatModel openAiChatModel() {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("test-api-key")
                .completionsPath("/v1/chat/completions")
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                // 本类隔离验证模型协议；生产配置的单次尝试绑定由自动配置契约测试独立证明。
                .retryTemplate(RetryTemplate.builder()
                        .maxAttempts(1)
                        .noBackoff()
                        .build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(MODEL)
                        .build())
                .build();
    }

    private Flux<ChatResponse> stream(OpenAiChatModel model, Prompt prompt) {
        return ChatClient.create(model)
                .prompt(prompt)
                .stream()
                .chatResponse();
    }

    private static Prompt textPrompt() {
        return new Prompt("请验证流式边界", requestOptions());
    }

    private static OpenAiChatOptions requestOptions() {
        return OpenAiChatOptions.builder()
                .model(MODEL)
                .maxCompletionTokens(64)
                .reasoningEffort("low")
                .N(1)
                .store(false)
                .streamUsage(true)
                .build();
    }

    private void handleChatStream(HttpExchange exchange) throws IOException {
        try (exchange) {
            captureRequest(exchange);
            configureSseHeaders(exchange);
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                writeEvent(output, textChunk("第一段", true));
                writeEvent(output, textChunk("第二段", false));
                // 终态闩锁让测试线程有机会证明：前两个 flush 已跨过模型边界，而请求仍未完成。
                if (!releaseTerminalUsage.await(
                        COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("Timed out waiting to release terminal usage");
                }
                writeEvent(output, finishChunk());
                writeEvent(output, usageChunk());
                writeEvent(output, "[DONE]");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            serverFailure.set(exception);
        } catch (Throwable failure) {
            serverFailure.set(failure);
        }
    }

    private void handlePartialThenDisconnect(HttpExchange exchange)
            throws IOException {
        try (exchange) {
            captureRequest(exchange);
            configureSseHeaders(exchange);
            byte[] partial = (eventFrame(textChunk("第一段", true))
                    + eventFrame(textChunk("第二段", false)))
                    .getBytes(StandardCharsets.UTF_8);
            // 声明比实际发送更长的响应体，使关闭连接成为可观察的协议中断而不是正常 EOF。
            exchange.sendResponseHeaders(200, partial.length + 1024L);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(partial);
                output.flush();
            }
        }
    }

    private void handleStreamWithoutUsage(HttpExchange exchange)
            throws IOException {
        try (exchange) {
            captureRequest(exchange);
            configureSseHeaders(exchange);
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                writeEvent(output, textChunk("第一段", true));
                writeEvent(output, textChunk("第二段", false));
                writeEvent(output, finishChunk());
                writeEvent(output, "[DONE]");
            }
        }
    }

    private void handleImmediateCompleteStream(HttpExchange exchange)
            throws IOException {
        try (exchange) {
            captureRequest(exchange);
            configureSseHeaders(exchange);
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                writeEvent(output, textChunk("第一段", true));
                writeEvent(output, textChunk("第二段", false));
                writeEvent(output, finishChunk());
                writeEvent(output, usageChunk());
                writeEvent(output, "[DONE]");
            }
        }
    }

    private void captureRequest(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        requestBody.set(new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        requestReceived.countDown();
    }

    private static void configureSseHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set(
                "Content-Type", "text/event-stream; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
    }

    private void assertRequestContract() throws IOException {
        JsonNode request = objectMapper.readTree(requestBody.get());
        assertThat(request.path("model").asText()).isEqualTo(MODEL);
        assertThat(request.path("stream").asBoolean()).isTrue();
        assertThat(request.path("stream_options").path("include_usage").asBoolean())
                .isTrue();
        assertThat(request.path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(request.path("max_completion_tokens").asInt()).isEqualTo(64);
        assertThat(request.path("n").asInt()).isEqualTo(1);
        assertThat(request.path("store").asBoolean()).isFalse();
    }

    private void assertFinalResponseContract() throws ReflectiveOperationException {
        assertThat(responses)
                .extracting(response -> response.getResult() == null
                        ? ""
                        : response.getResult().getOutput().getText())
                .contains("第一段", "第二段");
        ChatResponse usageResponse = responses.stream()
                .filter(response -> response.getMetadata() != null)
                .filter(response -> response.getMetadata().getUsage() != null)
                .filter(response -> response.getMetadata()
                        .getUsage()
                        .getCompletionTokens() == 7)
                .findFirst()
                .orElseThrow();
        Usage usage = usageResponse.getMetadata().getUsage();
        assertThat(usage.getPromptTokens()).isEqualTo(11);
        assertThat(usage.getCompletionTokens()).isEqualTo(7);
        assertThat(usage.getNativeUsage()).isInstanceOf(OpenAiApi.Usage.class);
        OpenAiApi.Usage nativeUsage = (OpenAiApi.Usage) usage.getNativeUsage();
        assertThat(nativeUsage.promptTokensDetails().cachedTokens()).isEqualTo(3);
        assertThat(nativeUsage.completionTokenDetails().reasoningTokens()).isEqualTo(5);
        assertThat(usageResponse.getMetadata().getId())
                .isEqualTo("chatcmpl-stream-contract");
        assertThat(usageResponse.getResult().getMetadata().getFinishReason())
                .isEqualTo("STOP");

        AiConversationUsage conversationUsage =
                extractConversationUsage(usageResponse).orElseThrow();
        assertThat(conversationUsage.promptTokens()).isEqualTo(11L);
        assertThat(conversationUsage.cachedPromptTokens()).isEqualTo(3L);
        assertThat(conversationUsage.completionTokens()).isEqualTo(7L);
        assertThat(conversationUsage.reasoningTokens()).isEqualTo(5L);
    }

    private static void writeEvent(OutputStream output, String data)
            throws IOException {
        output.write(eventFrame(data)
                .getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static String eventFrame(String data) {
        return "data: " + data + "\n\n";
    }

    @SuppressWarnings("unchecked")
    private static Optional<AiConversationUsage> extractConversationUsage(
            ChatResponse response) throws ReflectiveOperationException {
        Method extractUsage = SpringAiCliProxyConversationModelClient.class
                .getDeclaredMethod("extractUsage", ChatResponse.class);
        extractUsage.setAccessible(true);
        return (Optional<AiConversationUsage>) extractUsage.invoke(
                null, response);
    }

    private static String textChunk(String text, boolean includeRole) {
        String role = includeRole ? "\"role\":\"assistant\"," : "";
        return "{\"id\":\"chatcmpl-stream-contract\","
                + "\"object\":\"chat.completion.chunk\","
                + "\"created\":1710000000,\"model\":\"" + MODEL + "\","
                + "\"choices\":[{\"index\":0,\"delta\":{" + role
                + "\"content\":\"" + text + "\"},\"finish_reason\":null}]}";
    }

    private static String finishChunk() {
        return "{\"id\":\"chatcmpl-stream-contract\","
                + "\"object\":\"chat.completion.chunk\","
                + "\"created\":1710000001,\"model\":\"" + MODEL + "\","
                + "\"choices\":[{\"index\":0,\"delta\":{},"
                + "\"finish_reason\":\"stop\"}]}";
    }

    private static String usageChunk() {
        return "{\"id\":\"chatcmpl-stream-contract\","
                + "\"object\":\"chat.completion.chunk\","
                + "\"created\":1710000002,\"model\":\"" + MODEL + "\","
                + "\"choices\":[],\"usage\":{\"prompt_tokens\":11,"
                + "\"completion_tokens\":7,\"total_tokens\":18,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":3},"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":5}}}";
    }
}
