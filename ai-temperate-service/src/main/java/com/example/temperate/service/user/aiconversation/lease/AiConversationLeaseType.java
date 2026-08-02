package com.example.temperate.service.user.aiconversation.lease;

/**
 * 区分用户生成请求单活租约与后台持久化压缩单飞租约。
 */
public enum AiConversationLeaseType {
    INFLIGHT,
    COMPACTION
}
