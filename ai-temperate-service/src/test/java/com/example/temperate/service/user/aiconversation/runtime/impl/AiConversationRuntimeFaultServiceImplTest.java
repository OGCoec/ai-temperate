package com.example.temperate.service.user.aiconversation.runtime.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.StaticApplicationContext;

/**
 * 验证本地运行时链接故障会形成受控系统异常，并把当前实例切换为拒绝新流量状态。
 */
final class AiConversationRuntimeFaultServiceImplTest {

    @Test
    void linkageFailureReturnsControlledExceptionAndRefusesTraffic() {
        StaticApplicationContext context = new StaticApplicationContext();
        AtomicReference<AvailabilityChangeEvent<?>> availability = new AtomicReference<>();
        context.addApplicationListener(event -> {
            if (event instanceof AvailabilityChangeEvent<?> change) {
                availability.set(change);
            }
        });
        context.refresh();
        try {
            AiConversationRuntimeFaultServiceImpl service =
                    new AiConversationRuntimeFaultServiceImpl(context);
            NoClassDefFoundError cause = new NoClassDefFoundError(
                    "AiConversationGeneratedImagePhase");

            var failure = service.imageEventMappingFailure(
                    "generation-safe", (short) 2, cause);

            assertThat(failure.code())
                    .isEqualTo(AiConversationErrorCode.AI_RUNTIME_LINKAGE_FAILED);
            assertThat(failure.retryable()).isFalse();
            assertThat(failure.getCause()).isSameAs(cause);
            assertThat(availability.get()).isNotNull();
            assertThat(availability.get().getState())
                    .isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        } finally {
            context.close();
        }
    }

    @Test
    void unrelatedContextEventsDoNotAffectTheFaultResult() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.refresh();
        try {
            context.publishEvent(new ContextRefreshedEvent(context));
            AiConversationRuntimeFaultServiceImpl service =
                    new AiConversationRuntimeFaultServiceImpl(context);

            var failure = service.imageEventMappingFailure(
                    "generation-safe", (short) 0,
                    new ClassFormatError("broken class"));

            assertThat(failure.code())
                    .isEqualTo(AiConversationErrorCode.AI_RUNTIME_LINKAGE_FAILED);
        } finally {
            context.close();
        }
    }
}
