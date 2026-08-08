package com.example.temperate.service.user.aiconversation.generation.progress;

/**
 * 区分生成媒体的上传类型，使同一条生成任务中的图片和视频进度拥有稳定且互不冲突的展示键。
 */
public enum AiConversationMediaType {
    IMAGE,
    VIDEO
}
