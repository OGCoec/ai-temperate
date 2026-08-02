package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.user.aiconversation.generation.cancellation.AiConversationGenerationCancellationEvent;
import com.example.temperate.service.user.aiconversation.generation.cancellation.impl.AiConversationGenerationCancellationPublisherImpl;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 验证启动前取消直接冻结终态，只有已经存在 Owner 时才发送定向控制消息。
 */
final class AiConversationGenerationCancellationPublisherTest {

    private AiConversationGenerationEventPublisher eventPublisher;
    private AiConversationGenerationTerminalService terminalService;
    private HybridBase64UrlCodec idCodec;
    private AiConversationGenerationCancellationPublisherImpl publisher;
    private String generationPublicId;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(AiConversationGenerationEventPublisher.class);
        terminalService = mock(AiConversationGenerationTerminalService.class);
        idCodec = new HybridBase64UrlCodec();
        publisher = new AiConversationGenerationCancellationPublisherImpl(
                eventPublisher, terminalService, idCodec);
        byte[] generationId = new byte[16];
        generationId[15] = 1;
        generationPublicId = idCodec.encode(generationId);
    }

    @Test
    void freezesCancellationWithoutPublishingToAnUnrelatedInstanceWhenOwnerIsMissing() {
        publisher.publish(new AiConversationGenerationCancellationEvent(
                generationPublicId,
                "USER_STOP",
                1,
                null,
                "trace-test"));

        ArgumentCaptor<AiConversationGenerationTerminalCommand> command =
                ArgumentCaptor.forClass(AiConversationGenerationTerminalCommand.class);
        verify(terminalService).freeze(command.capture());
        assertThat(command.getValue().terminalType())
                .isEqualTo(AiConversationGenerationTerminalType.CLIENT_CANCELLED);
        assertThat(command.getValue().assistantText()).isEmpty();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void routesCancellationOnlyToThePersistedOwner() {
        publisher.publish(new AiConversationGenerationCancellationEvent(
                generationPublicId,
                "ADMIN_CANCEL",
                1,
                "instance-owner",
                "trace-test"));

        verify(eventPublisher).publishCancelRequested(
                generationPublicId,
                "ADMIN_CANCEL",
                1,
                "instance-owner",
                "trace-test");
        verifyNoInteractions(terminalService);
    }
}
