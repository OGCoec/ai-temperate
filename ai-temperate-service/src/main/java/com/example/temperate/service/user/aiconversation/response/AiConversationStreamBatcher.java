package com.example.temperate.service.user.aiconversation.response;

import com.example.temperate.service.user.aiconversation.model.AiConversationModelChunk;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxOperator;
import reactor.core.publisher.Operators;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

/**
 * 在保留 Reactor 原生背压的前提下，按 UTF-8 字节阈值或最长等待时间批量交付 Redis 持久化片段。
 *
 * <p>每个片段先向下游发送，再进入持久化缓冲；Redis 慢写会自然阻止后续上游请求，而不会通过无界
 * 缓冲或独立订阅绕过 SSE 消费速度。持久化消费者必须自行将 Redis 故障降级，不能抛出后覆盖模型主流。</p>
 */
public final class AiConversationStreamBatcher {

    private static final int MAX_CHUNKS_PER_BATCH = 1024;

    private AiConversationStreamBatcher() {
    }

    public static Flux<AiConversationModelChunk> forwardWhileBatching(
            Flux<AiConversationModelChunk> source,
            int maximumBytes,
            Duration maximumWait,
            Consumer<List<AiConversationModelChunk>> persistenceConsumer) {
        return forwardWhileBatching(
                source,
                maximumBytes,
                maximumWait,
                (batch, ignoredReason) -> persistenceConsumer.accept(batch),
                Schedulers.boundedElastic());
    }

    /**
     * 在不改变下游 chunk 转发的前提下，向持久化回调提供批次触发原因。
     * 该元数据只用于诊断和批量统计，不会进入 SSE 正文。
     */
    public static Flux<AiConversationModelChunk> forwardWhileBatching(
            Flux<AiConversationModelChunk> source,
            int maximumBytes,
            Duration maximumWait,
            BiConsumer<List<AiConversationModelChunk>, AiConversationStreamFlushReason>
                    persistenceConsumer) {
        return forwardWhileBatching(
                source,
                maximumBytes,
                maximumWait,
                persistenceConsumer,
                Schedulers.boundedElastic());
    }

    static Flux<AiConversationModelChunk> forwardWhileBatching(
            Flux<AiConversationModelChunk> source,
            int maximumBytes,
            Duration maximumWait,
            Consumer<List<AiConversationModelChunk>> persistenceConsumer,
            Scheduler scheduler) {
        return forwardWhileBatching(
                source,
                maximumBytes,
                maximumWait,
                (batch, ignoredReason) -> persistenceConsumer.accept(batch),
                scheduler);
    }

    static Flux<AiConversationModelChunk> forwardWhileBatching(
            Flux<AiConversationModelChunk> source,
            int maximumBytes,
            Duration maximumWait,
            BiConsumer<List<AiConversationModelChunk>, AiConversationStreamFlushReason>
                    persistenceConsumer,
            Scheduler scheduler) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(maximumWait);
        Objects.requireNonNull(persistenceConsumer);
        Objects.requireNonNull(scheduler);
        if (maximumBytes <= 0 || maximumWait.isZero() || maximumWait.isNegative()) {
            throw new IllegalArgumentException(
                    "AI conversation stream batching limits are invalid.");
        }
        return new ForwardWhileBatchingFlux(
                source,
                maximumBytes,
                maximumWait,
                persistenceConsumer,
                scheduler);
    }

    private static void persist(
            BiConsumer<List<AiConversationModelChunk>, AiConversationStreamFlushReason>
                    persistenceConsumer,
            DrainedBatch batch) {
        if (!batch.chunks().isEmpty()) {
            persistenceConsumer.accept(batch.chunks(), batch.reason());
        }
    }

    /**
     * 将批处理副作用实现为一对一 Reactor 操作符，避免独立订阅切断浏览器到模型上游的背压链路。
     *
     * <p>该操作符不预取、不排队，也不创建第二个订阅；它只在实际下游消费完当前 Chunk 后执行 Redis
     * 批次累计，并把 request 与 cancel 原样转发给同一个上游订阅。
     */
    private static final class ForwardWhileBatchingFlux
            extends FluxOperator<AiConversationModelChunk, AiConversationModelChunk> {
        private final int maximumBytes;
        private final Duration maximumWait;
        private final BiConsumer<List<AiConversationModelChunk>, AiConversationStreamFlushReason>
                persistenceConsumer;
        private final Scheduler scheduler;

        private ForwardWhileBatchingFlux(
                Flux<? extends AiConversationModelChunk> source,
                int maximumBytes,
                Duration maximumWait,
                BiConsumer<List<AiConversationModelChunk>, AiConversationStreamFlushReason>
                        persistenceConsumer,
                Scheduler scheduler) {
            super(source);
            this.maximumBytes = maximumBytes;
            this.maximumWait = maximumWait;
            this.persistenceConsumer = persistenceConsumer;
            this.scheduler = scheduler;
        }

        @Override
        public void subscribe(
                CoreSubscriber<? super AiConversationModelChunk> actual) {
            ForwardingState state = new ForwardingState(
                    maximumBytes, persistenceConsumer, scheduler.createWorker());
            try {
                state.start(maximumWait);
            } catch (RuntimeException schedulingFailure) {
                Operators.error(actual, schedulingFailure);
                return;
            }
            // 这里是标准一对一操作符订阅，不是脱离下游需求的内部订阅；实际 Subscription 由包装器透传。
            source.subscribe(new ForwardingSubscriber(actual, state));
        }
    }

    /**
     * 负责在同一 Reactive Streams 订阅中透传需求、取消和终止信号，并在下游收到片段后追加临时批次。
     */
    private static final class ForwardingSubscriber
            implements CoreSubscriber<AiConversationModelChunk>, Subscription {
        private final CoreSubscriber<? super AiConversationModelChunk> actual;
        private final ForwardingState state;
        private final Object lifecycleLock = new Object();
        private Subscription upstream;
        private boolean terminated;
        private boolean delivering;
        private boolean cancellationPending;

        private ForwardingSubscriber(
                CoreSubscriber<? super AiConversationModelChunk> actual,
                ForwardingState state) {
            this.actual = actual;
            this.state = state;
        }

        @Override
        public Context currentContext() {
            return actual.currentContext();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            if (!Operators.validate(upstream, subscription)) {
                return;
            }
            upstream = subscription;
            actual.onSubscribe(this);
        }

        @Override
        public void request(long n) {
            // 不修改需求数量，保证慢速 Servlet 写出能够直接限制模型上游生产速度。
            upstream.request(n);
        }

        @Override
        public void cancel() {
            boolean terminateImmediately;
            synchronized (lifecycleLock) {
                if (terminated || cancellationPending) {
                    return;
                }
                cancellationPending = true;
                // 下游可能在 actual.onNext 内同步取消；此时必须等待当前片段完成后置批处理再清尾批次。
                terminateImmediately = !delivering;
                if (terminateImmediately) {
                    terminated = true;
                }
            }
            upstream.cancel();
            if (terminateImmediately) {
                state.terminate();
            }
        }

        @Override
        public void onNext(AiConversationModelChunk chunk) {
            synchronized (lifecycleLock) {
                if (terminated || cancellationPending) {
                    Operators.onNextDropped(chunk, currentContext());
                    return;
                }
                delivering = true;
            }
            boolean terminateAfterDelivery = false;
            try {
                // 必须先交给 SSE 下游；只有下游 onNext 返回后，当前片段才进入 Redis 临时批次。
                actual.onNext(chunk);
                state.afterNext(chunk);
            } finally {
                synchronized (lifecycleLock) {
                    delivering = false;
                    if (cancellationPending && !terminated) {
                        terminated = true;
                        terminateAfterDelivery = true;
                    }
                }
                if (terminateAfterDelivery) {
                    state.terminate();
                }
            }
        }

        @Override
        public void onError(Throwable failure) {
            synchronized (lifecycleLock) {
                if (terminated || cancellationPending) {
                    Operators.onErrorDropped(failure, currentContext());
                    return;
                }
                terminated = true;
            }
            // 先刷新临时上下文，再让后续终态链执行失败结算和预扣退款。
            state.terminate();
            actual.onError(failure);
        }

        @Override
        public void onComplete() {
            synchronized (lifecycleLock) {
                if (terminated || cancellationPending) {
                    return;
                }
                terminated = true;
            }
            // 完成事件到达正式消息落库前，先交付最后一批 Redis 临时片段。
            state.terminate();
            actual.onComplete();
        }
    }

    /**
     * 保存单次订阅的批处理状态，并串行化模型线程、定时线程和终止线程对 Redis 批次的访问。
     */
    private static final class ForwardingState {
        private final BiConsumer<List<AiConversationModelChunk>, AiConversationStreamFlushReason>
                persistenceConsumer;
        private final Accumulator accumulator;
        private final Scheduler.Worker worker;
        private final Object persistenceLock = new Object();
        private final AtomicBoolean terminated = new AtomicBoolean();

        private ForwardingState(
                int maximumBytes,
                BiConsumer<List<AiConversationModelChunk>, AiConversationStreamFlushReason>
                        persistenceConsumer,
                Scheduler.Worker worker) {
            this.persistenceConsumer = persistenceConsumer;
            this.accumulator = new Accumulator(maximumBytes);
            this.worker = worker;
        }

        private void start(Duration maximumWait) {
            long intervalNanos = maximumWait.toNanos();
            try {
                worker.schedulePeriodically(
                        this::flushWhileActive,
                        intervalNanos,
                        intervalNanos,
                        TimeUnit.NANOSECONDS);
            } catch (RuntimeException schedulingFailure) {
                worker.dispose();
                throw schedulingFailure;
            }
        }

        private void afterNext(AiConversationModelChunk chunk) {
            synchronized (persistenceLock) {
                if (terminated.get()) {
                    return;
                }
                persist(persistenceConsumer, accumulator.append(chunk));
            }
        }

        private void flushWhileActive() {
            synchronized (persistenceLock) {
                if (!terminated.get()) {
                    persist(
                            persistenceConsumer,
                            accumulator.drain(AiConversationStreamFlushReason.TIME_THRESHOLD));
                }
            }
        }

        private void terminate() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            // 先禁止新的定时刷新，再在同一把锁下交付尾批次，避免完成与计时器重复写入。
            worker.dispose();
            synchronized (persistenceLock) {
                persist(
                        persistenceConsumer,
                        accumulator.drain(AiConversationStreamFlushReason.TERMINAL));
            }
        }
    }

    private static final class Accumulator {
        private final int maximumBytes;
        private final List<AiConversationModelChunk> chunks = new ArrayList<>();
        private int bytes;

        private Accumulator(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        private synchronized DrainedBatch append(
                AiConversationModelChunk chunk) {
            chunks.add(Objects.requireNonNull(chunk));
            bytes = Math.addExact(
                    bytes,
                    chunk.text().getBytes(StandardCharsets.UTF_8).length);
            // 媒体、数量和字节阈值按稳定优先级记录，避免同一批次出现多个触发原因。
            if (!chunk.generatedMedia().isEmpty() || chunk.generatedMediaTruncated()) {
                return drainLocked(AiConversationStreamFlushReason.MEDIA);
            }
            if (chunks.size() >= MAX_CHUNKS_PER_BATCH) {
                return drainLocked(AiConversationStreamFlushReason.MAX_CHUNKS);
            }
            return bytes >= maximumBytes
                    ? drainLocked(AiConversationStreamFlushReason.SIZE_THRESHOLD)
                    : DrainedBatch.empty();
        }

        private synchronized DrainedBatch drain(AiConversationStreamFlushReason reason) {
            return drainLocked(reason);
        }

        private DrainedBatch drainLocked(AiConversationStreamFlushReason reason) {
            if (chunks.isEmpty()) {
                return DrainedBatch.empty();
            }
            List<AiConversationModelChunk> drained = List.copyOf(chunks);
            chunks.clear();
            bytes = 0;
            return new DrainedBatch(drained, reason);
        }
    }

    private record DrainedBatch(
            List<AiConversationModelChunk> chunks,
            AiConversationStreamFlushReason reason) {

        private static DrainedBatch empty() {
            return new DrainedBatch(List.of(), null);
        }
    }
}
