package com.example.temperate.service.user.aiconversation.runtime.impl;

import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.runtime.AiConversationRuntimeFaultService;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 将 AI 图片链路的类加载或链接故障标记为本地系统故障，并把当前实例切换为拒绝新流量状态。
 *
 * <p>该实现不会主动退出 JVM；现有任务通过受控异常完成退款和资源释放，实例重启由部署平台或人工处理。</p>
 */
@Service
public final class AiConversationRuntimeFaultServiceImpl
        implements AiConversationRuntimeFaultService {

    private static final Logger log = LoggerFactory.getLogger(
            AiConversationRuntimeFaultServiceImpl.class);

    private final ApplicationEventPublisher eventPublisher;

    public AiConversationRuntimeFaultServiceImpl(
            ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
    }

    @Override
    public AiConversationException imageEventMappingFailure(
            String generationPublicId,
            short outputIndex,
            LinkageError failure) {
        Objects.requireNonNull(failure);
        String safeGenerationPublicId = safeGenerationPublicId(
                generationPublicId);
        log.error(
                "event=ai_runtime_linkage_failure failureStage=IMAGE_EVENT_MAP "
                        + "failureType={} generationPublicId={} outputIndex={} "
                        + "readiness=REFUSING_TRAFFIC traceId=unavailable",
                failure.getClass().getName(),
                safeGenerationPublicId,
                outputIndex);
        // 类链接失败说明当前部署产物不完整或不兼容；先停止接收新流量，再让当前任务走既有退款终态。
        AvailabilityChangeEvent.publish(
                eventPublisher,
                failure,
                ReadinessState.REFUSING_TRAFFIC);
        return new AiConversationException(
                AiConversationErrorCode.AI_RUNTIME_LINKAGE_FAILED,
                "AI 服务运行环境异常",
                false,
                failure);
    }

    private static String safeGenerationPublicId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
            return "unavailable";
        }
        return value;
    }
}
