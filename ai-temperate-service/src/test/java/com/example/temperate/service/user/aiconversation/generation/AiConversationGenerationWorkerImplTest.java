package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.model.ai.entity.AiConversationGenerationPayload;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheSnapshot;
import com.example.temperate.service.user.aiconversation.concurrency.AiConversationConcurrencyPermit;
import com.example.temperate.service.user.aiconversation.concurrency.AiConversationConcurrencyService;
import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextService;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextStore;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextWriteOutcome;
import com.example.temperate.service.user.aiconversation.context.AiConversationEphemeralStart;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingBoundary;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingClock;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingPath;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputStore;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationActiveRegistry;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationClaim;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationControlService;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationWorkItem;
import com.example.temperate.service.user.aiconversation.generation.worker.impl.AiConversationGenerationWorkerImpl;
import com.example.temperate.service.user.aiconversation.lease.AiConversationLease;
import com.example.temperate.service.user.aiconversation.lease.AiConversationLeaseService;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelChunk;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelClient;
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * 使用进程内假模型流验证后台 Worker 的正常、无 Usage、上游失败和取消终态，不调用真实付费模型。
 */
final class AiConversationGenerationWorkerImplTest {

    private static final HybridBase64UrlCodec ID_CODEC = new HybridBase64UrlCodec();
    private static final PublicIdCodec PUBLIC_ID_CODEC = new PublicIdCodec();

    @Test
    void cancellationBeforeWorkerClaimNeverCallsUpstreamAndFreezesNoOutputTerminal() {
        Fixture fixture = fixture(Flux.never());
        fixture.cancelled.setCancelSource("CLIENT_EXIT_TIMEOUT");
        when(fixture.controlService.claim(fixture.generationId))
                .thenReturn(new AiConversationGenerationClaim(
                        "CANCELLED_BEFORE_START",
                        new AiConversationGenerationWorkItem(
                                fixture.cancelled, fixture.payload)));

        fixture.worker.execute(fixture.generationPublicId, "trace-test");

        verify(fixture.modelClient, never()).stream(any());
        AiConversationGenerationTerminalCommand command = terminalCommand(fixture);
        assertThat(command.terminalType())
                .isEqualTo(AiConversationGenerationTerminalType.CLIENT_CANCELLED);
        assertThat(command.terminalReason()).isEqualTo("CLIENT_EXIT_TIMEOUT");
        assertThat(command.assistantText()).isEmpty();
        assertThat(command.usage()).isNull();
    }

    @Test
    void cancellationAfterClaimButBeforeUpstreamCallNeverStartsTheModelRequest() {
        Fixture fixture = fixture(Flux.never());
        fixture.cancelled.setCancelSource("USER_STOP");
        when(fixture.controlService.load(fixture.generationId))
                .thenReturn(new AiConversationGenerationWorkItem(
                        fixture.cancelled, fixture.payload));

        fixture.worker.execute(fixture.generationPublicId, "trace-test");

        verify(fixture.modelClient, never()).stream(any());
        AiConversationGenerationTerminalCommand command = terminalCommand(fixture);
        assertThat(command.terminalType())
                .isEqualTo(AiConversationGenerationTerminalType.CLIENT_CANCELLED);
        assertThat(command.terminalReason()).isEqualTo("USER_STOP");
    }

    @Test
    void completedFakeModelFreezesReportedUsageAndStop() {
        AiConversationUsage usage = new AiConversationUsage(12, 2, 5, 1);
        Fixture fixture = fixture(Flux.just(new AiConversationModelChunk(
                "完成回答", usage, "upstream-safe", "STOP")));

        fixture.worker.execute(fixture.generationPublicId, "trace-test");

        AiConversationGenerationTerminalCommand command = terminalCommand(fixture);
        assertThat(command.terminalType())
                .isEqualTo(AiConversationGenerationTerminalType.COMPLETED);
        assertThat(command.terminalReason()).isEqualTo("STOP");
        assertThat(command.assistantText()).isEqualTo("完成回答");
        assertThat(command.usage()).isEqualTo(usage);
    }

    @Test
    void partialOutputThenUpstreamFailureAlwaysFreezesRefundAuthority() {
        Fixture fixture = fixture(Flux.concat(
                Flux.just(new AiConversationModelChunk("部分回答", null, null, null)),
                Flux.error(new AiConversationException(
                        AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                        "受控上游失败",
                        true))));

        fixture.worker.execute(fixture.generationPublicId, "trace-test");

        AiConversationGenerationTerminalCommand command = terminalCommand(fixture);
        assertThat(command.terminalType())
                .isEqualTo(AiConversationGenerationTerminalType.UPSTREAM_FAILED);
        assertThat(command.terminalReason()).isEqualTo("AI_UPSTREAM_STREAM_FAILED");
        assertThat(command.assistantText()).isEqualTo("部分回答");
    }

    @Test
    void streamCompletingWithoutUsageFreezesSystemFailure() {
        Fixture fixture = fixture(Flux.just(
                new AiConversationModelChunk("没有 usage", null, null, "STOP")));

        fixture.worker.execute(fixture.generationPublicId, "trace-test");

        AiConversationGenerationTerminalCommand command = terminalCommand(fixture);
        assertThat(command.terminalType())
                .isEqualTo(AiConversationGenerationTerminalType.SYSTEM_FAILED);
        assertThat(command.terminalReason())
                .isEqualTo("AI_STREAM_TERMINATED_WITHOUT_USAGE");
    }

    @Test
    void cancellationWinningAfterFinalUsageFreezesClientCancelledWithUsageEvidence() {
        AiConversationUsage usage = new AiConversationUsage(9, 1, 4, 0);
        Fixture fixture = fixture(Flux.just(new AiConversationModelChunk(
                "取消前回答", usage, "upstream-safe", "STOP")));
        fixture.cancelled.setCancelSource("USER_STOP");
        when(fixture.controlService.load(fixture.generationId))
                .thenReturn(
                        new AiConversationGenerationWorkItem(
                                generation(
                                        fixture.generationId,
                                        bytes(2),
                                        bytes(3),
                                        AiConversationGenerationStatus.RUNNING,
                                        null),
                                fixture.payload),
                        new AiConversationGenerationWorkItem(
                                fixture.cancelled, fixture.payload));

        fixture.worker.execute(fixture.generationPublicId, "trace-test");

        AiConversationGenerationTerminalCommand command = terminalCommand(fixture);
        assertThat(command.terminalType())
                .isEqualTo(AiConversationGenerationTerminalType.CLIENT_CANCELLED);
        assertThat(command.terminalReason()).isEqualTo("USER_STOP");
        assertThat(command.usage()).isEqualTo(usage);
    }

    @Test
    void workerInstallsAsyncTimingContextAndObservesBatcherOutput() {
        AiConversationUsage usage = new AiConversationUsage(12, 0, 5, 0);
        Fixture fixture = fixture(Flux.just(new AiConversationModelChunk(
                "诊断片段", usage, "upstream-safe", "STOP")));

        fixture.worker.execute(fixture.generationPublicId, "trace-safe");

        ArgumentCaptor<AiConversationStreamTimingContext> context =
                ArgumentCaptor.forClass(AiConversationStreamTimingContext.class);
        verify(fixture.timingDiagnosticService).withSession(any(), context.capture());
        verify(fixture.timingDiagnosticService).observeBoundary(
                any(), eq(AiConversationStreamTimingBoundary.AFTER_STREAM_BATCHER), any());
        assertThat(context.getValue().traceId()).isEqualTo("trace-safe");
        assertThat(context.getValue().usagePublicId()).isEqualTo(fixture.usagePublicId);
        assertThat(context.getValue().conversationPublicId())
                .isEqualTo(fixture.conversationPublicId);
        assertThat(context.getValue().modelPublicId()).isEqualTo(fixture.modelPublicId);
        assertThat(context.getValue().path())
                .isEqualTo(AiConversationStreamTimingPath.ASYNC_GENERATION_WORKER);
        assertThat(context.getValue().startedNanos()).isEqualTo(123L);
    }

    private static AiConversationGenerationTerminalCommand terminalCommand(Fixture fixture) {
        ArgumentCaptor<AiConversationGenerationTerminalCommand> captor =
                ArgumentCaptor.forClass(AiConversationGenerationTerminalCommand.class);
        verify(fixture.terminalService).freeze(captor.capture());
        return captor.getValue();
    }

    private static Fixture fixture(Flux<AiConversationModelChunk> upstream) {
        byte[] generationId = bytes(1);
        byte[] conversationId = bytes(2);
        byte[] usageId = bytes(3);
        String generationPublicId = ID_CODEC.encode(generationId);
        AiConversationGeneration running = generation(
                generationId,
                conversationId,
                usageId,
                AiConversationGenerationStatus.RUNNING,
                null);
        AiConversationGeneration cancelled = generation(
                generationId,
                conversationId,
                usageId,
                AiConversationGenerationStatus.CANCEL_REQUESTED,
                "USER_STOP");
        AiConversationGenerationPayload payload = new AiConversationGenerationPayload();
        payload.setGenerationId(generationId);
        payload.setInputText("测试输入");
        payload.setInputAttachmentsJson("[]");
        payload.setReasoningEffort(2);
        AiConversationGenerationWorkItem runningItem =
                new AiConversationGenerationWorkItem(running, payload);

        AiConversationGenerationControlService controlService =
                mock(AiConversationGenerationControlService.class);
        AiConversationGenerationActiveRegistry activeRegistry =
                mock(AiConversationGenerationActiveRegistry.class);
        AiConversationGenerationTerminalService terminalService =
                mock(AiConversationGenerationTerminalService.class);
        AiModelCacheService modelCacheService = mock(AiModelCacheService.class);
        AiConversationContextService contextService = mock(AiConversationContextService.class);
        AiConversationContextStore contextStore = mock(AiConversationContextStore.class);
        AiConversationConcurrencyService concurrencyService =
                mock(AiConversationConcurrencyService.class);
        AiConversationLeaseService leaseService = mock(AiConversationLeaseService.class);
        AiConversationModelClient modelClient = mock(AiConversationModelClient.class);
        AiConversationGenerationOutputStore outputStore =
                mock(AiConversationGenerationOutputStore.class);
        AiConversationStreamTimingDiagnosticService timingDiagnosticService =
                mock(AiConversationStreamTimingDiagnosticService.class);
        AiConversationStreamTimingClock timingClock = () -> 123L;
        when(timingDiagnosticService.observeBoundary(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(timingDiagnosticService.withSession(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(controlService.claim(generationId))
                .thenReturn(new AiConversationGenerationClaim("CLAIMED", runningItem));
        when(controlService.load(generationId)).thenReturn(runningItem);
        when(terminalService.freeze(any())).thenReturn(new AiConversationGenerationTerminalResult(
                true,
                generationPublicId,
                ID_CODEC.encode(usageId),
                "TEST",
                "TEST",
                1));
        when(modelCacheService.getOrLoadEnabledSnapshot())
                .thenReturn(new AiModelCacheSnapshot(
                        AiModelCacheSnapshot.CURRENT_SCHEMA_VERSION,
                        List.of(model())));
        when(contextService.prepare(any(), any(), any(), any()))
                .thenReturn(new AiConversationPromptSnapshot(
                        "system",
                        null,
                        null,
                        List.of(),
                        new AiConversationContent("测试输入", List.of()),
                        "cache-generation",
                        8,
                        false));
        when(concurrencyService.tryAcquire(42L))
                .thenReturn(Optional.of(mock(AiConversationConcurrencyPermit.class)));
        when(leaseService.tryAcquire(any(), any()))
                .thenReturn(Optional.of(mock(AiConversationLease.class)));
        when(contextStore.appendEphemeralUser(any(), any(), any(), any()))
                .thenReturn(AiConversationEphemeralStart.applied(1L));
        when(contextStore.appendAssistantChunks(any(), any(), any(Long.class), any(Integer.class), any()))
                .thenReturn(AiConversationContextWriteOutcome.APPLIED);
        when(modelClient.stream(any())).thenReturn(upstream);

        AiConversationGenerationWorkerImpl worker = new AiConversationGenerationWorkerImpl(
                controlService,
                activeRegistry,
                terminalService,
                modelCacheService,
                contextService,
                contextStore,
                concurrencyService,
                leaseService,
                modelClient,
                outputStore,
                conversationProperties(),
                asyncProperties(),
                ID_CODEC,
                PUBLIC_ID_CODEC,
                timingDiagnosticService,
                timingClock,
                mock(AiConversationMetrics.class),
                new ObjectMapper().findAndRegisterModules());
        return new Fixture(
                generationId,
                generationPublicId,
                ID_CODEC.encode(conversationId),
                ID_CODEC.encode(usageId),
                PUBLIC_ID_CODEC.encode(7L),
                payload,
                cancelled,
                controlService,
                terminalService,
                modelClient,
                timingDiagnosticService,
                worker);
    }

    private static AiConversationGeneration generation(
            byte[] generationId,
            byte[] conversationId,
            byte[] usageId,
            AiConversationGenerationStatus status,
            String cancelSource) {
        AiConversationGeneration generation = new AiConversationGeneration();
        generation.setId(generationId);
        generation.setLoginIdentityId(42L);
        generation.setConversationId(conversationId);
        generation.setUsageId(usageId);
        generation.setModelId(7L);
        generation.setGenerationStatus(status.code());
        generation.setCancelSource(cancelSource);
        return generation;
    }

    private static AiModelCacheEntry model() {
        return new AiModelCacheEntry(
                7L,
                "fake-model",
                "test",
                "test",
                null,
                List.of(),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                128_000,
                2_000,
                List.of());
    }

    private static AiConversationProperties conversationProperties() {
        return new AiConversationProperties(
                Duration.ofHours(72),
                Duration.ofMinutes(2),
                Duration.ofMinutes(2),
                Duration.ofMillis(10),
                1024,
                100,
                1000,
                900,
                2,
                8,
                1000,
                80,
                Duration.ofSeconds(5),
                Duration.ofMinutes(1),
                100,
                false,
                "system");
    }

    private static AiConversationAsyncGenerationProperties asyncProperties() {
        return new AiConversationAsyncGenerationProperties(
                true,
                "instance-test",
                Duration.ofMillis(250),
                Duration.ofMillis(50),
                Duration.ofHours(24),
                1,
                Duration.ofSeconds(2));
    }

    private static byte[] bytes(int marker) {
        byte[] value = new byte[16];
        value[15] = (byte) marker;
        return value;
    }

    private record Fixture(
            byte[] generationId,
            String generationPublicId,
            String conversationPublicId,
            String usagePublicId,
            String modelPublicId,
            AiConversationGenerationPayload payload,
            AiConversationGeneration cancelled,
            AiConversationGenerationControlService controlService,
            AiConversationGenerationTerminalService terminalService,
            AiConversationModelClient modelClient,
            AiConversationStreamTimingDiagnosticService timingDiagnosticService,
            AiConversationGenerationWorkerImpl worker) {
    }
}
