package com.example.temperate.service.user.aiconversation.attachment;

import java.util.List;

/**
 * 编排会话附件预上传、HEAD 校验、模型临时读取、正式落盘和数据库失败后的尽力补偿。
 */
public interface AiConversationAttachmentService {

    AiConversationPreuploadBatch createPreuploads(
            long userId,
            String userPublicId,
            List<AiConversationPreuploadFile> files);

    List<AiConversationAttachment> validateTemporaryInputs(
            String userPublicId,
            List<AiConversationAttachmentUploadReference> references);

    String resolveModelUrl(AiConversationAttachment attachment);

    AiConversationAttachmentFinalization finalizeAttachments(
            String userPublicId,
            String conversationPublicId,
            String messagePublicId,
            List<AiConversationAttachment> inputAttachments,
            List<AiConversationGeneratedMedia> generatedMedia);

    void compensateCreatedObjects(List<String> objectKeys);
}
