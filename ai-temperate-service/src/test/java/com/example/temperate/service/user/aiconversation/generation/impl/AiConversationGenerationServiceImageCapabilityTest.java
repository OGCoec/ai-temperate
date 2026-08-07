package com.example.temperate.service.user.aiconversation.generation.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheSnapshot;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentCategory;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentService;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentUploadReference;
import com.example.temperate.service.user.aiconversation.config.AiConversationImageGenerationProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextService;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationCreateCommand;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationCreationTransactionService;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStart;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAction;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationRequest;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageProfile;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageProfileService;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingStrategy;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingStrategyRegistry;
import com.example.temperate.service.user.aiconversation.response.AiConversationResponseCommand;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.example.temperate.service.user.aiconversation.security.AiConversationIdempotencyHasher;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证图片数量和编辑动作只由模型能力集合授权，不依赖模型名称或普通图片输入能力。
 */
final class AiConversationGenerationServiceImageCapabilityTest {

    @Test
    void dualCapabilityAllowsTenEditOutputsAndFreezesEditAction() {
        Harness harness = harness(List.of(
                AiModelCapabilityCode.IMAGE_GENERATION,
                AiModelCapabilityCode.IMAGE_EDIT));

        harness.service().create(command((short) 10, true));

        assertThat(harness.created().get().imageGeneration().action())
                .isEqualTo(AiConversationImageAction.EDIT);
        assertThat(harness.created().get().imageGeneration().outputCount())
                .isEqualTo((short) 10);
    }

    @Test
    void generationOnlyCapabilityAllowsOneGenerateButRejectsMultiAndEdit() {
        Harness allowed = harness(List.of(AiModelCapabilityCode.IMAGE_GENERATION));
        allowed.service().create(command((short) 1, false));
        assertThat(allowed.created().get().imageGeneration().action())
                .isEqualTo(AiConversationImageAction.GENERATE);

        Harness multi = harness(List.of(AiModelCapabilityCode.IMAGE_GENERATION));
        assertInvalid(() -> multi.service().create(command((short) 2, false)));

        Harness edit = harness(List.of(AiModelCapabilityCode.IMAGE_GENERATION));
        assertInvalid(() -> edit.service().create(command((short) 1, true)));
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(AiConversationException.class)
                .extracting(failure -> ((AiConversationException) failure).code())
                .isEqualTo(AiConversationErrorCode.AI_REQUEST_INVALID);
    }

    private static Harness harness(List<AiModelCapabilityCode> capabilities) {
        AiModelCacheService modelCache = mock(AiModelCacheService.class);
        AiConversationAttachmentService attachments = mock(
                AiConversationAttachmentService.class);
        AiConversationContextService context = mock(AiConversationContextService.class);
        AiConversationGenerationCreationTransactionService creation = mock(
                AiConversationGenerationCreationTransactionService.class);
        AiConversationIdempotencyHasher hasher = mock(
                AiConversationIdempotencyHasher.class);
        AiConversationImageProfileService profiles = mock(
                AiConversationImageProfileService.class);
        AiConversationStreamingStrategyRegistry registry = mock(
                AiConversationStreamingStrategyRegistry.class);
        AiConversationStreamingStrategy strategy = mock(
                AiConversationStreamingStrategy.class);
        PublicIdCodec publicIds = mock(PublicIdCodec.class);
        AiModelCacheEntry model = model(capabilities);
        when(publicIds.decode("AAAAAAAAAAA")).thenReturn(1L);
        when(modelCache.getOrLoadEnabledSnapshot()).thenReturn(
                new AiModelCacheSnapshot(
                        AiModelCacheSnapshot.CURRENT_SCHEMA_VERSION,
                        List.of(model)));
        AiConversationAttachment input = editAttachment();
        when(attachments.validateTemporaryInputs(any(), any()))
                .thenAnswer(invocation -> {
                    List<?> references = invocation.getArgument(1);
                    return references.isEmpty() ? List.of() : List.of(input);
                });
        AiConversationPromptSnapshot prompt = mock(AiConversationPromptSnapshot.class);
        when(prompt.estimatedPromptTokens()).thenReturn(12L);
        when(context.prepareNew(any(), any())).thenReturn(prompt);
        when(hasher.digest(anyLong(), any(UUID.class)))
                .thenReturn(new byte[] {1});
        when(profiles.required(any(), any(), any(), any())).thenReturn(
                new AiConversationImageProfile(
                        AiConversationImageQuality.MEDIUM,
                        1024,
                        1024,
                        AiConversationReasoningEffort.MEDIUM));
        when(registry.getRequired(any(), any())).thenReturn(strategy);
        when(strategy.meteringBasis()).thenReturn(AiConversationMeteringBasis.TOKEN);
        AtomicReference<AiConversationGenerationCreateCommand> created =
                new AtomicReference<>();
        when(creation.create(any())).thenAnswer(invocation -> {
            created.set(invocation.getArgument(0));
            return new AiConversationGenerationStart(
                    "generation", "conversation", "usage",
                    "AAAAAAAAAAA", true, false);
        });
        return new Harness(new AiConversationGenerationServiceImpl(
                modelCache,
                attachments,
                context,
                creation,
                mock(AiConversationGenerationMapper.class),
                hasher,
                profiles,
                new AiConversationImageGenerationProperties(
                        true,
                        "/v1/images/generations",
                        "/v1/images/edits",
                        33_554_432,
                        268_435_456L),
                registry,
                mock(HybridBase64UrlCodec.class),
                publicIds,
                mock(com.example.temperate.service.user.aiconversation.compaction
                        .AiConversationCompactionCoordinator.class)), created);
    }

    private static AiConversationResponseCommand command(
            short outputCount,
            boolean edit) {
        List<AiConversationAttachmentUploadReference> references = edit
                ? List.of(new AiConversationAttachmentUploadReference(
                        "upload", "attachment", "input.webp",
                        "image/webp", 1024L))
                : List.of();
        return new AiConversationResponseCommand(
                42L,
                "AAAAAAAAAAA",
                null,
                "AAAAAAAAAAA",
                AiConversationReasoningEffort.MEDIUM,
                AiConversationWebSearchMode.OFF,
                new AiConversationImageGenerationRequest(
                        AiConversationImageAspect.SQUARE, outputCount),
                UUID.randomUUID(),
                new AiConversationContent("edit or generate", List.of(), references));
    }

    private static AiConversationAttachment editAttachment() {
        return AiConversationAttachment.available(
                "attachment",
                "input.webp",
                "image/webp",
                1024L,
                AiConversationAttachmentCategory.IMAGE,
                "ait-temp:temporary-object");
    }

    private static AiModelCacheEntry model(
            List<AiModelCapabilityCode> capabilities) {
        return new AiModelCacheEntry(
                1L,
                "vendor-compatible-image-model",
                "openai",
                "image",
                null,
                List.of(),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                100_000L,
                10_000L,
                capabilities);
    }

    private record Harness(
            AiConversationGenerationServiceImpl service,
            AtomicReference<AiConversationGenerationCreateCommand> created) {
    }
}
