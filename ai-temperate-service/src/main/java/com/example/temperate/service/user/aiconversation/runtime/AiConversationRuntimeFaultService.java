package com.example.temperate.service.user.aiconversation.runtime;

import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;

/**
 * 负责把 AI 会话运行时链接故障转换为可收敛的系统失败，并通知宿主实例停止接收新流量。
 *
 * <p>该服务只处理 {@link LinkageError}，不负责吞掉虚拟机资源耗尽、线程终止或其他不可恢复错误。</p>
 */
public interface AiConversationRuntimeFaultService {

    /**
     * 处理图片事件映射阶段的类链接故障。
     *
     * @param generationPublicId 仅用于安全关联日志的 Generation 公共 ID
     * @param outputIndex 发生故障的图片输出槽位
     * @param failure JVM 报告的类加载或链接故障
     * @return 可进入 Reactor onError 与现有终态冻结流程的受控异常
     */
    AiConversationException imageEventMappingFailure(
            String generationPublicId,
            short outputIndex,
            LinkageError failure);

    /**
     * 为不启动完整 Spring 宿主的旧单元测试保留相同错误语义，但不发布实例可用性事件。
     */
    static AiConversationRuntimeFaultService withoutAvailabilitySignal() {
        return (generationPublicId, outputIndex, failure) ->
                new AiConversationException(
                        AiConversationErrorCode.AI_RUNTIME_LINKAGE_FAILED,
                        "AI 服务运行环境异常",
                        false,
                        failure);
    }
}
