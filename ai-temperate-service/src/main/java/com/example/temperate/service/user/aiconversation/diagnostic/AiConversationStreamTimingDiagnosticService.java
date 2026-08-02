package com.example.temperate.service.user.aiconversation.diagnostic;

import java.util.function.ToIntFunction;
import reactor.core.publisher.Flux;

/**
 * 为一次模型订阅建立安全时序上下文，并在不改变背压、顺序或取消语义的前提下观察各流边界。
 */
public interface AiConversationStreamTimingDiagnosticService {

    <T> Flux<T> withSession(
            Flux<T> source,
            AiConversationStreamTimingContext context);

    <T> Flux<T> observeLifecycle(Flux<T> source);

    <T> Flux<T> observeBoundary(
            Flux<T> source,
            AiConversationStreamTimingBoundary boundary,
            ToIntFunction<T> textCharacters);
}
