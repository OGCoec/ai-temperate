package com.example.temperate.service.user.aiconversation.attachment;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 管理一次图片 Generation 内可边生成边提交的 OSS 上传，并在终态确定前保留统一补偿能力。
 */
public interface AiConversationGeneratedUploadSession {

    /**
     * 原子预留槽位和累计字节后提交异步上传；单文件边界失败以已完成的失败 Future 表达，系统拒绝则抛出异常。
     *
     * @param outputIndex 当前 Generation 内稳定的图片槽位
     * @param media 已完成且不会再变化的原图字节
     * @return 该槽位独立完成的持久化结果
     */
    CompletableFuture<AiConversationGeneratedUploadResult> submit(
            short outputIndex,
            AiConversationGeneratedMedia media);

    /**
     * 封闭新提交，并在同一截止时间内等待全部已提交任务，结果必须按槽位序号稳定排序。
     *
     * @param timeout Worker 剩余生命周期
     * @return 包含成功与可恢复槽位失败的不可变结果
     */
    List<AiConversationGeneratedUploadResult> finish(Duration timeout);

    /**
     * 在权威数据库终态已经引用正式 URL 后解除补偿责任；除 SEALED 外的状态不得提交。
     */
    void commit();

    /**
     * 幂等取消未完成任务并删除本 Session 已创建但尚未交权的对象，晚到成功由上传任务自行补偿。
     */
    void abortAndCompensate();
}
