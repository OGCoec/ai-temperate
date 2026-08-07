package com.example.temperate.service.user.aiconversation.attachment;

import java.time.Duration;
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

    /**
     * 为一次图片 Generation 打开流式上传会话，使已经完成的槽位无需等待兄弟槽位即可进入有界 OSS 执行器。
     *
     * @param userPublicId 用户公共 ID
     * @param conversationPublicId 会话公共 ID
     * @param messagePublicId 预留消息公共 ID
     * @return 只能由当前 Worker 使用并在终态后提交或补偿的上传会话
     */
    AiConversationGeneratedUploadSession openGeneratedUploadSession(
            String userPublicId,
            String conversationPublicId,
            String messagePublicId);

    AiConversationAttachmentFinalization finalizeAttachments(
            String userPublicId,
            String conversationPublicId,
            String messagePublicId,
            List<AiConversationAttachment> inputAttachments,
            List<AiConversationGeneratedMedia> generatedMedia);

    /**
     * 在调用方剩余生命周期内完成附件最终化；超时后必须清理本批已经创建且尚未被引用的对象。
     *
     * @param userPublicId 用户公共 ID
     * @param conversationPublicId 会话公共 ID
     * @param messagePublicId 消息公共 ID
     * @param inputAttachments 用户输入附件
     * @param generatedMedia 模型生成媒体
     * @param timeout 本批最终化可使用的剩余时间
     * @return 保持输入顺序的最终化结果
     */
    default AiConversationAttachmentFinalization finalizeAttachments(
            String userPublicId,
            String conversationPublicId,
            String messagePublicId,
            List<AiConversationAttachment> inputAttachments,
            List<AiConversationGeneratedMedia> generatedMedia,
            Duration timeout) {
        return finalizeAttachments(
                userPublicId,
                conversationPublicId,
                messagePublicId,
                inputAttachments,
                generatedMedia);
    }

    void compensateCreatedObjects(List<String> objectKeys);
}
