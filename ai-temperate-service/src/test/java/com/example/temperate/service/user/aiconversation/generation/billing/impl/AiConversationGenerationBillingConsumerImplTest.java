package com.example.temperate.service.user.aiconversation.generation.billing.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.mapper.ai.AiConversationGenerationPayloadMapper;
import com.example.temperate.mapper.ai.AiModelUsageDetailMapper;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.model.ai.entity.AiConversationGenerationPayload;
import com.example.temperate.model.ai.entity.AiModelUsageDetail;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentFinalization;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentCategory;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentService;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextStore;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStatus;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalType;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBilledEvent;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingCommand;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingMode;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingResult;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingTransactionService;
import com.example.temperate.service.user.aiconversation.generation.input.AiConversationGenerationInputCodec;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationTerminated;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAction;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewBroker;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageProfile;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.image.AiConversationPersistedGeneratedAttachmentCodec;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.example.temperate.service.user.aiconversation.response.AiConversationTerminalBillingPolicy;
import com.example.temperate.service.user.aiconversation.text.AiConversationTextTokenizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 验证资金事务已提交后的 Rabbit 幂等补发会从冻结证据恢复多图数量、消息 ID 和正式附件，且不会再次访问 OSS。
 */
final class AiConversationGenerationBillingConsumerImplTest {

    @Test
    void missingXaiImageCostDeliversFrozenImageAndRequestsReconciliation() {
        byte[] generationId = new byte[] {9};
        byte[] conversationId = new byte[] {8};
        byte[] usageId = new byte[] {7};
        AiConversationGeneration generation = new AiConversationGeneration();
        generation.setId(generationId);
        generation.setConversationId(conversationId);
        generation.setUsageId(usageId);
        generation.setLoginIdentityId(42L);
        generation.setModelId(11L);
        generation.setGenerationStatus(
                AiConversationGenerationStatus.TERMINAL_PENDING_BILLING.code());
        generation.setTerminalVersion(6);
        generation.setTerminalType(AiConversationGenerationTerminalType.COMPLETED.name());
        generation.setTerminalReason("IMAGE_COMPLETED");

        ObjectMapper objectMapper = new ObjectMapper();
        AiConversationGenerationInputCodec inputCodec =
                new AiConversationGenerationInputCodec(objectMapper);
        AiConversationPersistedGeneratedAttachmentCodec attachmentCodec =
                new AiConversationPersistedGeneratedAttachmentCodec(objectMapper);
        AiConversationAttachment image = AiConversationAttachment.available(
                "image-attachment",
                "generated-1.webp",
                "image/webp",
                4096L,
                AiConversationAttachmentCategory.IMAGE,
                "https://cdn.example/generated-1.webp");
        AiConversationGenerationPayload payload = new AiConversationGenerationPayload();
        payload.setGenerationId(generationId);
        payload.setInputText("draw");
        payload.setInputAttachmentsJson(inputCodec.encode(
                java.util.List.of(),
                AiConversationImageGenerationOptions.from(
                        AiConversationImageAspect.SQUARE,
                        new AiConversationImageProfile(
                                AiConversationImageQuality.LOW,
                                1024,
                                1024,
                                AiConversationReasoningEffort.LOW),
                        AiConversationImageAction.GENERATE,
                        (short) 1)));
        payload.setMeteringBasis(
                AiConversationMeteringBasis.PROVIDER_COST_TICKS.code());
        payload.setAssistantText("");
        payload.setAssistantAttachmentsJson(attachmentCodec.encode(List.of(image)));
        payload.setMeteringEvidenceJson("""
                {"schemaVersion":1,"basis":"PROVIDER_COST_TICKS","outputs":[
                  {"outputIndex":0,"status":"MISSING_COST","requestId":"req-safe","costTicks":null}
                ]}
                """);
        payload.setModelFinishReason("STOP");
        AiModelUsageDetail detail = new AiModelUsageDetail();
        detail.setUsageId(usageId);
        detail.setMeteringBasis(
                AiConversationMeteringBasis.PROVIDER_COST_TICKS.code());
        detail.setRequestedOutputCount((short) 1);
        detail.setReservedQuotaMinor(10_000L);

        HybridBase64UrlCodec idCodec = mock(HybridBase64UrlCodec.class);
        PublicIdCodec publicIdCodec = mock(PublicIdCodec.class);
        AiConversationGenerationMapper generationMapper =
                mock(AiConversationGenerationMapper.class);
        AiConversationGenerationPayloadMapper payloadMapper =
                mock(AiConversationGenerationPayloadMapper.class);
        AiModelUsageDetailMapper detailMapper = mock(AiModelUsageDetailMapper.class);
        AiConversationGenerationBillingTransactionService transactionService =
                mock(AiConversationGenerationBillingTransactionService.class);
        AiConversationAttachmentService attachmentService =
                mock(AiConversationAttachmentService.class);
        ApplicationEventPublisher eventPublisher =
                mock(ApplicationEventPublisher.class);
        AiConversationImagePreviewBroker previewBroker =
                mock(AiConversationImagePreviewBroker.class);
        when(idCodec.decode("generation")).thenReturn(generationId);
        when(idCodec.encode(usageId)).thenReturn("usage");
        when(idCodec.encode(conversationId)).thenReturn("conversation");
        when(publicIdCodec.encode(42L)).thenReturn("user");
        when(publicIdCodec.encode(99L)).thenReturn("message");
        when(publicIdCodec.encode(11L)).thenReturn("model");
        when(generationMapper.findById(generationId)).thenReturn(generation);
        when(payloadMapper.findByGenerationId(generationId)).thenReturn(payload);
        when(detailMapper.findByUsageId(usageId)).thenReturn(detail);
        when(transactionService.getOrReserveMessageId(generationId)).thenReturn(99L);
        when(attachmentService.finalizeAttachments(
                "user", "conversation", "message", List.of(), List.of()))
                .thenReturn(new AiConversationAttachmentFinalization(
                        List.of(), List.of(), List.of(), false));
        when(transactionService.settle(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AiConversationGenerationBillingResult(
                        true, "RECONCILE_REQUIRED", 99L));
        AiConversationGenerationBillingConsumerImpl consumer =
                new AiConversationGenerationBillingConsumerImpl(
                        generationMapper,
                        payloadMapper,
                        detailMapper,
                        mock(AiConversationTerminalBillingPolicy.class),
                        transactionService,
                        attachmentService,
                        mock(AiConversationTextTokenizer.class),
                        mock(AiConversationContextStore.class),
                        idCodec,
                        publicIdCodec,
                        objectMapper,
                        mock(AiConversationMetrics.class),
                        inputCodec,
                        attachmentCodec,
                        eventPublisher,
                        previewBroker,
                        mock(com.example.temperate.service.user.aiconversation.compaction
                                .AiConversationCompactionCoordinator.class));

        consumer.consume(new AiConversationGenerationTerminated(
                "generation",
                "usage",
                AiConversationGenerationTerminalType.COMPLETED.name(),
                "IMAGE_COMPLETED",
                6), "trace");

        ArgumentCaptor<AiConversationGenerationBillingCommand> command =
                ArgumentCaptor.forClass(AiConversationGenerationBillingCommand.class);
        org.mockito.Mockito.verify(transactionService).settle(command.capture());
        assertThat(command.getValue().mode())
                .isEqualTo(AiConversationGenerationBillingMode.COMPLETE_RECONCILE);
        assertThat(command.getValue().failureCode())
                .isEqualTo("AI_IMAGE_COST_EVIDENCE_MISSING");
        assertThat(command.getValue().settlementCommand().usage()).isNull();
        assertThat(command.getValue().settlementCommand().assistant().attachments())
                .containsExactly(image);
    }

    @Test
    void republishesFrozenImageTerminalWithoutRepeatingSettlementOrOss()
            throws Exception {
        byte[] generationId = new byte[] {1};
        byte[] usageId = new byte[] {2};
        AiConversationGeneration generation = new AiConversationGeneration();
        generation.setGenerationStatus(AiConversationGenerationStatus.SETTLED.code());
        generation.setUsageId(usageId);
        generation.setTerminalVersion(3);
        generation.setTerminalType(AiConversationGenerationTerminalType.COMPLETED.name());
        generation.setTerminalReason("IMAGE_PARTIAL_COMPLETED");

        ObjectMapper objectMapper = new ObjectMapper();
        AiConversationGenerationInputCodec inputCodec =
                new AiConversationGenerationInputCodec(objectMapper);
        AiConversationPersistedGeneratedAttachmentCodec attachmentCodec =
                new AiConversationPersistedGeneratedAttachmentCodec(objectMapper);
        AiConversationGenerationPayload payload = new AiConversationGenerationPayload();
        payload.setGenerationId(generationId);
        payload.setConversationMessageId(99L);
        payload.setInputAttachmentsJson(inputCodec.encode(
                java.util.List.of(),
                AiConversationImageGenerationOptions.from(
                        AiConversationImageAspect.PORTRAIT,
                        new AiConversationImageProfile(
                                AiConversationImageQuality.HIGH,
                                1024,
                                1536,
                                AiConversationReasoningEffort.MEDIUM),
                        AiConversationImageAction.GENERATE,
                        (short) 4)));
        AiConversationAttachment attachment = AiConversationAttachment.available(
                "image-attachment-1",
                "generated-1.webp",
                "image/webp",
                4096L,
                AiConversationAttachmentCategory.IMAGE,
                "https://cdn.example/generated-1.webp");
        payload.setAssistantAttachmentsJson(attachmentCodec.encode(
                java.util.List.of(attachment)));

        HybridBase64UrlCodec idCodec = mock(HybridBase64UrlCodec.class);
        PublicIdCodec publicIdCodec = mock(PublicIdCodec.class);
        AiConversationGenerationMapper generationMapper =
                mock(AiConversationGenerationMapper.class);
        AiConversationGenerationPayloadMapper payloadMapper =
                mock(AiConversationGenerationPayloadMapper.class);
        AiConversationGenerationBillingTransactionService transactionService =
                mock(AiConversationGenerationBillingTransactionService.class);
        AiConversationAttachmentService attachmentService =
                mock(AiConversationAttachmentService.class);
        ApplicationEventPublisher eventPublisher =
                mock(ApplicationEventPublisher.class);
        AiConversationImagePreviewBroker previewBroker =
                mock(AiConversationImagePreviewBroker.class);
        when(idCodec.decode("generation")).thenReturn(generationId);
        when(idCodec.encode(usageId)).thenReturn("usage");
        when(publicIdCodec.encode(99L)).thenReturn("AAAAAAAAAAI");
        when(generationMapper.findById(generationId)).thenReturn(generation);
        when(payloadMapper.findByGenerationId(generationId)).thenReturn(payload);
        AiConversationGenerationBillingConsumerImpl consumer =
                new AiConversationGenerationBillingConsumerImpl(
                        generationMapper,
                        payloadMapper,
                        mock(AiModelUsageDetailMapper.class),
                        mock(AiConversationTerminalBillingPolicy.class),
                        transactionService,
                        attachmentService,
                        mock(AiConversationTextTokenizer.class),
                        mock(AiConversationContextStore.class),
                        idCodec,
                        publicIdCodec,
                        objectMapper,
                        mock(AiConversationMetrics.class),
                        inputCodec,
                        attachmentCodec,
                        eventPublisher,
                        previewBroker,
                        mock(com.example.temperate.service.user.aiconversation.compaction
                                .AiConversationCompactionCoordinator.class));

        consumer.consume(new AiConversationGenerationTerminated(
                "generation",
                "usage",
                AiConversationGenerationTerminalType.COMPLETED.name(),
                "IMAGE_PARTIAL_COMPLETED",
                3), "trace");

        ArgumentCaptor<AiConversationGenerationBilledEvent> event =
                ArgumentCaptor.forClass(AiConversationGenerationBilledEvent.class);
        InOrder terminalOrder = inOrder(eventPublisher, previewBroker);
        terminalOrder.verify(eventPublisher).publishEvent(event.capture());
        JsonNode data = objectMapper.readTree(event.getValue().dataJson());
        assertThat(data.path("messagePublicId").asText()).isEqualTo("AAAAAAAAAAI");
        assertThat(data.path("requestedImageCount").asInt()).isEqualTo(4);
        assertThat(data.path("attachments")).hasSize(1);
        assertThat(data.path("attachments").get(0).path("fileName").asText())
                .isEqualTo("generated-1.webp");
        terminalOrder.verify(previewBroker).release("generation");
        verifyNoInteractions(transactionService, attachmentService);
    }

    @Test
    void republishesFailedImageTerminalWithoutDecodingLegacyArrayOrCallingOss()
            throws Exception {
        byte[] generationId = new byte[] {3};
        byte[] usageId = new byte[] {4};
        AiConversationGeneration generation = new AiConversationGeneration();
        generation.setGenerationStatus(AiConversationGenerationStatus.REFUNDED.code());
        generation.setUsageId(usageId);
        generation.setTerminalVersion(5);
        generation.setTerminalType(
                AiConversationGenerationTerminalType.UPSTREAM_FAILED.name());
        generation.setTerminalReason("AI_UPSTREAM_STREAM_FAILED");

        ObjectMapper objectMapper = new ObjectMapper();
        AiConversationGenerationInputCodec inputCodec =
                new AiConversationGenerationInputCodec(objectMapper);
        AiConversationPersistedGeneratedAttachmentCodec attachmentCodec =
                new AiConversationPersistedGeneratedAttachmentCodec(objectMapper);
        AiConversationGenerationPayload payload = new AiConversationGenerationPayload();
        payload.setGenerationId(generationId);
        payload.setInputAttachmentsJson(inputCodec.encode(
                java.util.List.of(),
                AiConversationImageGenerationOptions.from(
                        AiConversationImageAspect.SQUARE,
                        new AiConversationImageProfile(
                                AiConversationImageQuality.HIGH,
                                1024,
                                1024,
                                AiConversationReasoningEffort.MEDIUM),
                        AiConversationImageAction.GENERATE,
                        (short) 4)));
        // 失败和取消终态沿用旧空数组证据；幂等补发不得把它当图片 URL 信封解码。
        payload.setAssistantAttachmentsJson("[]");

        HybridBase64UrlCodec idCodec = mock(HybridBase64UrlCodec.class);
        PublicIdCodec publicIdCodec = mock(PublicIdCodec.class);
        AiConversationGenerationMapper generationMapper =
                mock(AiConversationGenerationMapper.class);
        AiConversationGenerationPayloadMapper payloadMapper =
                mock(AiConversationGenerationPayloadMapper.class);
        AiConversationGenerationBillingTransactionService transactionService =
                mock(AiConversationGenerationBillingTransactionService.class);
        AiConversationAttachmentService attachmentService =
                mock(AiConversationAttachmentService.class);
        ApplicationEventPublisher eventPublisher =
                mock(ApplicationEventPublisher.class);
        AiConversationImagePreviewBroker previewBroker =
                mock(AiConversationImagePreviewBroker.class);
        when(idCodec.decode("generation")).thenReturn(generationId);
        when(idCodec.encode(usageId)).thenReturn("usage");
        when(generationMapper.findById(generationId)).thenReturn(generation);
        when(payloadMapper.findByGenerationId(generationId)).thenReturn(payload);
        AiConversationGenerationBillingConsumerImpl consumer =
                new AiConversationGenerationBillingConsumerImpl(
                        generationMapper,
                        payloadMapper,
                        mock(AiModelUsageDetailMapper.class),
                        mock(AiConversationTerminalBillingPolicy.class),
                        transactionService,
                        attachmentService,
                        mock(AiConversationTextTokenizer.class),
                        mock(AiConversationContextStore.class),
                        idCodec,
                        publicIdCodec,
                        objectMapper,
                        mock(AiConversationMetrics.class),
                        inputCodec,
                        attachmentCodec,
                        eventPublisher,
                        previewBroker,
                        mock(com.example.temperate.service.user.aiconversation.compaction
                                .AiConversationCompactionCoordinator.class));

        consumer.consume(new AiConversationGenerationTerminated(
                "generation",
                "usage",
                AiConversationGenerationTerminalType.UPSTREAM_FAILED.name(),
                "AI_UPSTREAM_STREAM_FAILED",
                5), "trace");

        ArgumentCaptor<AiConversationGenerationBilledEvent> event =
                ArgumentCaptor.forClass(AiConversationGenerationBilledEvent.class);
        InOrder terminalOrder = inOrder(eventPublisher, previewBroker);
        terminalOrder.verify(eventPublisher).publishEvent(event.capture());
        terminalOrder.verify(previewBroker).release("generation");
        JsonNode data = objectMapper.readTree(event.getValue().dataJson());
        assertThat(data.path("messagePublicId").asText()).isEmpty();
        assertThat(data.path("requestedImageCount").asInt()).isEqualTo(4);
        assertThat(data.path("attachments")).isEmpty();
        verifyNoInteractions(transactionService, attachmentService);
    }
}
