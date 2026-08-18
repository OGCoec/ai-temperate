package com.example.temperate.web.apiresponse;

import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apiresponse.ApiResponseCreation;
import com.example.temperate.service.user.apiresponse.ApiResponseRequest;
import com.example.temperate.service.user.apiresponse.ApiResponseService;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticBoundary;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticSession;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticStage;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseFailureStage;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseFrameClass;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseStreamDiagnostic;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseStreamDiagnosticService;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseSseFrame;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.Subscription;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.SignalType;

/**
 * 该 Controller 是来提供精确的 Codex 核心 Responses 子集入口，只编排 API Key 主体、动态 JSON/SSE 返回类型和安全缓存头。
 */
@RestController
@RequestMapping("/v1")
@Tag(
        name = "开放接口-Responses",
        description = "供 Codex、OpenAI SDK 与服务端应用调用的无状态 Responses 核心子集。"
                + "仅接受 Worker 验签和 Bearer API Key，固定 store=false；支持文本、推理回放和函数工具，"
                + "不负责保存 Response、后台任务、托管工具、多模态或结构化输出。")
public class ApiResponsesController {

    private final ApiResponseService responseService;
    private final ApiResponseStreamDiagnosticService diagnostics;

    public ApiResponsesController(
            ApiResponseService responseService,
            ApiResponseStreamDiagnosticService diagnostics) {
        this.responseService = Objects.requireNonNull(responseService);
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    @PostMapping(
            path = "/responses",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "创建无状态 Response",
            description = "Authorization 使用脱敏 Bearer sk-***。stream 缺省或 false 返回 JSON，"
                    + "stream=true 返回保留 Responses event 名称的 SSE；服务端强制 store=false，"
                    + "不会转发客户端 API Key 到 8317。",
            security = @SecurityRequirement(name = "apiKeyBearer"))
    @ApiResponseStreamDiagnostic(stage = ApiResponseDiagnosticStage.HTTP_CONTROLLER)
    public Mono<ResponseEntity<?>> create(
            @AuthenticationPrincipal(errorOnInvalidType = true) ApiKeyPrincipal principal,
            @RequestBody ApiResponseRequest request) {
        ApiResponseDiagnosticSession diagnosticSession = diagnostics.currentSession();
        ApiResponseCreation creation = responseService.create(principal, request);
        if (creation instanceof ApiResponseCreation.Stream stream) {
            return prepareSseResponse(stream.body(), diagnosticSession);
        }
        ApiResponseCreation.Json json = (ApiResponseCreation.Json) creation;
        // Mono 完成前不会建立 HTTP 200，Service 因而可以先完成权威 Usage 结算。
        return json.body().map(body -> {
            ResponseEntity<?> response = ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .cacheControl(CacheControl.noStore().cachePrivate().noTransform())
                    .header("CDN-Cache-Control", "no-store")
                    .body(body);
            return response;
        });
    }

    private static Mono<ResponseEntity<?>> prepareSseResponse(
            Flux<ApiResponseSseFrame> frames,
            ApiResponseDiagnosticSession diagnosticSession) {
        return Mono.create(responseSink -> {
            SseResponseGate gate = new SseResponseGate(
                    responseSink, diagnosticSession);
            // 在首帧前取消 HTTP 请求必须取消唯一的上游订阅，避免预扣请求失去客户端后继续产生输出。
            responseSink.onCancel(() -> gate.cancelFrom(
                    CancellationSource.CONTROLLER_MONO_CANCEL));
            frames.subscribe(gate);
        });
    }

    /**
     * 该门控订阅器是来仅请求第一帧后暂停上游，先安全确定 SSE 200，再把同一订阅交给 HTTP body，避免双订阅或首帧丢失。
     */
    private static final class SseResponseGate
            extends BaseSubscriber<ApiResponseSseFrame> {

        private final MonoSink<ResponseEntity<?>> responseSink;
        private final ApiResponseDiagnosticSession diagnostics;
        private final AtomicReference<FluxSink<ServerSentEvent<String>>> bodySink =
                new AtomicReference<>();
        private final AtomicReference<ApiResponseSseFrame> firstFrame =
                new AtomicReference<>();
        private final AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
        private final AtomicReference<CancellationSource> cancellationSource =
                new AtomicReference<>();
        private final AtomicLong downstreamDemand = new AtomicLong();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean firstFrameEmitted = new AtomicBoolean();
        private final AtomicBoolean upstreamRequestInFlight = new AtomicBoolean();
        private final AtomicBoolean terminalFrameSeen = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean bodyTerminated = new AtomicBoolean();
        private final AtomicInteger drainWip = new AtomicInteger();

        private SseResponseGate(
                MonoSink<ResponseEntity<?>> responseSink,
                ApiResponseDiagnosticSession diagnostics) {
            this.responseSink = Objects.requireNonNull(responseSink);
            this.diagnostics = Objects.requireNonNull(diagnostics);
        }

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            // 首帧是 HTTP 成功语义与后续 SSE 语义之间的边界；在客户端 body 就绪前绝不预取第二帧。
            upstreamRequestInFlight.set(true);
            safeDiagnostic(() -> diagnostics.recordUpstreamRequest(1L));
            request(1);
        }

        @Override
        protected void hookOnNext(ApiResponseSseFrame frame) {
            upstreamRequestInFlight.set(false);
            recordBoundary(
                    ApiResponseDiagnosticBoundary.CONTROLLER_GATE_RECEIVED,
                    frame);
            if (firstFrame.compareAndSet(null, frame)) {
                Flux<ServerSentEvent<String>> body = Flux
                        .<ServerSentEvent<String>>create(
                                this::attachBody,
                                FluxSink.OverflowStrategy.ERROR)
                        // Body 自身失败时必须反向取消 8317；受控上游错误会先标记 bodyTerminated，因此不会被误判为 MVC 故障。
                        .doOnError(failure -> {
                            if (!bodyTerminated.get()) {
                                recordFailure(ApiResponseFailureStage.MVC_BODY, failure);
                                cancelFrom(CancellationSource.BODY_WRITE_FAILURE);
                                summarize(SignalType.ON_ERROR, true);
                            }
                        });
                safeDiagnostic(diagnostics::recordResponsePrepared);
                responseSink.success(sseResponse(body));
                return;
            }
            FluxSink<ServerSentEvent<String>> sink = bodySink.get();
            if (sink == null) {
                failGate(new IllegalStateException(
                        "Responses SSE received a frame before the body subscriber was attached."));
                return;
            }
            if (!consumeDemand()) {
                failGate(new IllegalStateException(
                        "Responses SSE received a frame without downstream demand."));
                return;
            }
            emit(sink, frame);
            if (frame.terminalKind() != ApiResponseSseFrame.TerminalKind.NONE) {
                terminalFrameSeen.set(true);
            }
            drain();
        }

        @Override
        protected void hookOnComplete() {
            upstreamRequestInFlight.set(false);
            safeDiagnostic(() -> diagnostics.recordTerminalSignal(
                    "UPSTREAM_COMPLETE"));
            completed.set(true);
            if (firstFrame.get() == null) {
                ApiChatException failure = new ApiChatException(
                        ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR,
                        "The model upstream ended without a Responses event.",
                        null);
                recordFailure(ApiResponseFailureStage.CONTROLLER_GATE, failure);
                responseSink.error(failure);
                summarize(SignalType.ON_ERROR, false);
                return;
            }
            drain();
        }

        @Override
        protected void hookOnError(Throwable throwable) {
            upstreamRequestInFlight.set(false);
            recordFailure(ApiResponseFailureStage.CONTROLLER_GATE, throwable);
            safeDiagnostic(() -> diagnostics.recordTerminalSignal(
                    "UPSTREAM_ERROR"));
            terminalFailure.compareAndSet(null, throwable);
            if (firstFrame.get() == null) {
                responseSink.error(throwable);
                summarize(SignalType.ON_ERROR, false);
                return;
            }
            drain();
        }

        @Override
        protected void hookOnCancel() {
            CancellationSource source = cancellationSource.get();
            safeDiagnostic(() -> diagnostics.recordTerminalSignal(
                    "UPSTREAM_CANCEL_" + (source == null ? "UNKNOWN" : source.name())));
            // 本地 Gate/Body 故障由原失败路径使用 ON_ERROR 总结；只有真实下游取消才使用 CANCEL。
            if (source == null || source.clientInitiated()) {
                summarize(SignalType.CANCEL, firstFrame.get() != null);
            }
        }

        @Override
        protected void hookFinally(SignalType type) {
            safeDiagnostic(() -> diagnostics.recordTerminalSignal(
                    "UPSTREAM_" + type.name()));
        }

        private void attachBody(FluxSink<ServerSentEvent<String>> sink) {
            if (!bodySink.compareAndSet(null, sink)) {
                sink.error(new IllegalStateException(
                        "Responses SSE body must have exactly one subscriber."));
                return;
            }
            safeDiagnostic(diagnostics::recordBodySubscribed);
            sink.onRequest(requested -> onBodyRequest(sink, requested));
            sink.onCancel(() -> cancelFrom(CancellationSource.CLIENT_CANCEL));
            drain();
        }

        /**
         * MVC 的 demand 是唯一允许继续读取 8317 的凭据；累计值使用饱和加法，Long.MAX_VALUE 始终保持无界语义。
         */
        private void onBodyRequest(
                FluxSink<ServerSentEvent<String>> sink,
                long requested) {
            safeDiagnostic(() -> diagnostics.recordDownstreamRequest(requested));
            if (requested <= 0L) {
                failGate(new IllegalArgumentException(
                        "Responses SSE downstream demand must be positive."));
                return;
            }
            addDemand(requested);
            drain();
        }

        /**
         * 该 drain 是首帧交接与逐帧 request(1) 的串行化边界；WIP 允许同步 Flux 在 request(1) 内重入而不递归发送。
         */
        private void drain() {
            if (drainWip.getAndIncrement() != 0) {
                return;
            }
            int missed = 1;
            do {
                if (!cancelled.get() && !bodyTerminated.get()) {
                    FluxSink<ServerSentEvent<String>> sink = bodySink.get();
                    ApiResponseSseFrame first = firstFrame.get();
                    if (sink != null
                            && first != null
                            && !firstFrameEmitted.get()
                            && hasDemand()
                            && firstFrameEmitted.compareAndSet(false, true)) {
                        if (!consumeDemand()) {
                            failGate(new IllegalStateException(
                                    "Responses SSE lost first-frame downstream demand."));
                        } else {
                            emit(sink, first);
                            if (first.terminalKind()
                                    != ApiResponseSseFrame.TerminalKind.NONE) {
                                terminalFrameSeen.set(true);
                            }
                        }
                    }
                    if (!cancelled.get()
                            && !bodyTerminated.get()
                            && sink != null
                            && firstFrameEmitted.get()) {
                        Throwable failure = terminalFailure.get();
                        if (failure != null) {
                            terminateBodyWithError(sink, failure);
                        } else if (completed.get()) {
                            terminateBodyComplete(sink);
                        } else if (!terminalFrameSeen.get()
                                && hasDemand()
                                && upstreamRequestInFlight.compareAndSet(false, true)) {
                            safeDiagnostic(() -> diagnostics.recordUpstreamRequest(1L));
                            request(1L);
                        }
                    }
                }
                missed = drainWip.addAndGet(-missed);
            } while (missed != 0);
        }

        private void terminateBodyComplete(
                FluxSink<ServerSentEvent<String>> sink) {
            if (bodyTerminated.compareAndSet(false, true)) {
                sink.complete();
                summarize(SignalType.ON_COMPLETE, true);
            }
        }

        private void terminateBodyWithError(
                FluxSink<ServerSentEvent<String>> sink,
                Throwable failure) {
            if (bodyTerminated.compareAndSet(false, true)) {
                sink.error(failure);
                summarize(SignalType.ON_ERROR, true);
            }
        }

        private void failGate(RuntimeException failure) {
            recordFailure(ApiResponseFailureStage.CONTROLLER_GATE, failure);
            cancelFrom(CancellationSource.LOCAL_GATE_FAILURE);
            summarize(SignalType.ON_ERROR, firstFrame.get() != null);
            FluxSink<ServerSentEvent<String>> sink = bodySink.get();
            if (sink != null) {
                terminateBodyWithError(sink, failure);
            } else {
                responseSink.error(failure);
            }
        }

        private void cancelFrom(CancellationSource source) {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            cancellationSource.compareAndSet(null, source);
            if (source.clientInitiated()) {
                safeDiagnostic(diagnostics::recordClientCancelled);
            }
            safeDiagnostic(diagnostics::recordUpstreamCancelled);
            safeDiagnostic(() -> diagnostics.recordTerminalSignal(source.name()));
            cancel();
        }

        private void addDemand(long requested) {
            while (true) {
                long current = downstreamDemand.get();
                long updated = current == Long.MAX_VALUE
                        || requested == Long.MAX_VALUE
                        || Long.MAX_VALUE - current < requested
                        ? Long.MAX_VALUE
                        : current + requested;
                if (downstreamDemand.compareAndSet(current, updated)) {
                    return;
                }
            }
        }

        private boolean hasDemand() {
            return downstreamDemand.get() > 0L;
        }

        private boolean consumeDemand() {
            while (true) {
                long current = downstreamDemand.get();
                if (current <= 0L) {
                    return false;
                }
                if (current == Long.MAX_VALUE) {
                    return true;
                }
                if (downstreamDemand.compareAndSet(current, current - 1L)) {
                    return true;
                }
            }
        }

        /**
         * sink.next 是当前 MVC 背压故障的精确边界；记录前后 demand 但必须让同一个异常继续传播。
         */
        private void emit(
                FluxSink<ServerSentEvent<String>> sink,
                ApiResponseSseFrame frame) {
            long downstreamDemand = Math.max(0L, sink.requestedFromDownstream());
            safeDiagnostic(() -> diagnostics.recordEmitAttempt(
                    downstreamDemand, frame.sequenceNumber()));
            try {
                sink.next(toEvent(frame));
                if (downstreamDemand > 0L) {
                    safeDiagnostic(() -> diagnostics.recordEmitSucceeded(
                            frame.outputUtf8Bytes(),
                            frameClass(frame),
                            frame.sequenceNumber(),
                            frame.terminalKind(),
                            frame.usage() != null));
                }
            } catch (RuntimeException failure) {
                recordFailure(ApiResponseFailureStage.MVC_BODY, failure);
                cancelFrom(CancellationSource.BODY_WRITE_FAILURE);
                summarize(SignalType.ON_ERROR, true);
                throw failure;
            }
        }

        private void recordBoundary(
                ApiResponseDiagnosticBoundary boundary,
                ApiResponseSseFrame frame) {
            safeDiagnostic(() -> diagnostics.recordBoundary(
                    boundary,
                    frame.outputUtf8Bytes(),
                    frameClass(frame),
                    frame.sequenceNumber(),
                    frame.terminalKind(),
                    frame.usage() != null));
        }

        private void recordFailure(
                ApiResponseFailureStage stage,
                Throwable failure) {
            safeDiagnostic(() -> diagnostics.recordFailure(stage, failure));
        }

        private void summarize(SignalType signal, boolean responseCommitted) {
            safeDiagnostic(() -> diagnostics.summarize(
                    signal, responseCommitted));
        }

        private static ApiResponseFrameClass frameClass(
                ApiResponseSseFrame frame) {
            return ApiResponseFrameClass.classify(
                    frame.eventName(), frame.terminalKind());
        }

        private static ResponseEntity<?> sseResponse(
                Flux<ServerSentEvent<String>> body) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .cacheControl(CacheControl.noStore().cachePrivate().noTransform())
                    .header("CDN-Cache-Control", "no-store")
                    .header("X-Accel-Buffering", "no")
                    .body(body);
        }

        private static ServerSentEvent<String> toEvent(ApiResponseSseFrame frame) {
            return ServerSentEvent.<String>builder(frame.data())
                    .event(frame.eventName())
                    .build();
        }

        private static void safeDiagnostic(Runnable action) {
            try {
                action.run();
            } catch (RuntimeException ignored) {
                // 诊断自身异常不得改变现有 request(n)、FluxSink 溢出行为或 SSE 数据。
            }
        }
    }

    /** 该枚举是来区分真实客户端取消与本地 Body/Gate 故障，避免诊断把服务器错误误记为客户端主动离开。 */
    private enum CancellationSource {
        CLIENT_CANCEL(true),
        CONTROLLER_MONO_CANCEL(true),
        BODY_WRITE_FAILURE(false),
        LOCAL_GATE_FAILURE(false);

        private final boolean clientInitiated;

        CancellationSource(boolean clientInitiated) {
            this.clientInitiated = clientInitiated;
        }

        private boolean clientInitiated() {
            return clientInitiated;
        }
    }
}
