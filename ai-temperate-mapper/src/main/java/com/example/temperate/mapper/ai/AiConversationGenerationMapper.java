package com.example.temperate.mapper.ai;

import com.example.temperate.model.ai.entity.AiConversationGeneration;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供异步生成任务创建、观察者代际、Owner 领取、取消 CAS 和唯一终态冻结的持久化契约。
 */
@Mapper
public interface AiConversationGenerationMapper {

    int insert(AiConversationGeneration generation);

    AiConversationGeneration findById(@Param("generationId") byte[] generationId);

    AiConversationGeneration findByIdForUpdate(@Param("generationId") byte[] generationId);

    AiConversationGeneration findOwned(
            @Param("generationId") byte[] generationId,
            @Param("loginIdentityId") long loginIdentityId);

    AiConversationGeneration findOwnedByIdempotencyDigest(
            @Param("idempotencyKeyDigest") byte[] idempotencyKeyDigest,
            @Param("loginIdentityId") long loginIdentityId);

    List<AiConversationGeneration> findActiveOwned(
            @Param("loginIdentityId") long loginIdentityId,
            @Param("activeStatuses") List<Integer> activeStatuses,
            @Param("batchSize") int batchSize);

    List<AiConversationGeneration> findRecoveryCandidates(
            @Param("queuedStatus") int queuedStatus,
            @Param("runningStatus") int runningStatus,
            @Param("cancelRequestedStatus") int cancelRequestedStatus,
            @Param("terminalPendingStatus") int terminalPendingStatus,
            @Param("detachedStatus") int detachedStatus,
            @Param("publishCutoff") OffsetDateTime publishCutoff,
            @Param("runningCutoff") OffsetDateTime runningCutoff,
            @Param("detachedCutoff") OffsetDateTime detachedCutoff,
            @Param("batchSize") int batchSize);

    List<AiConversationGeneration> findTerminalCleanupCandidates(
            @Param("terminalStatuses") List<Integer> terminalStatuses,
            @Param("cutoff") OffsetDateTime cutoff,
            @Param("batchSize") int batchSize);

    int deleteTerminalByIds(
            @Param("generationIds") List<byte[]> generationIds,
            @Param("terminalStatuses") List<Integer> terminalStatuses);

    int claimQueued(
            @Param("generationId") byte[] generationId,
            @Param("queuedStatus") int queuedStatus,
            @Param("runningStatus") int runningStatus,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("now") OffsetDateTime now);

    int attachObserver(
            @Param("generationId") byte[] generationId,
            @Param("loginIdentityId") long loginIdentityId,
            @Param("attachedStatus") int attachedStatus,
            @Param("now") OffsetDateTime now);

    int detachObserver(
            @Param("generationId") byte[] generationId,
            @Param("loginIdentityId") long loginIdentityId,
            @Param("expectedEpoch") long expectedEpoch,
            @Param("attachedStatus") int attachedStatus,
            @Param("detachedStatus") int detachedStatus,
            @Param("now") OffsetDateTime now);

    int requestCancellation(
            @Param("generationId") byte[] generationId,
            @Param("expectedStatuses") List<Integer> expectedStatuses,
            @Param("cancelRequestedStatus") int cancelRequestedStatus,
            @Param("cancelSource") String cancelSource,
            @Param("now") OffsetDateTime now);

    int freezeTerminal(
            @Param("generationId") byte[] generationId,
            @Param("expectedStatuses") List<Integer> expectedStatuses,
            @Param("expectedTerminalVersion") int expectedTerminalVersion,
            @Param("terminalPendingStatus") int terminalPendingStatus,
            @Param("terminalType") String terminalType,
            @Param("terminalReason") String terminalReason,
            @Param("now") OffsetDateTime now);

    int completeBilling(
            @Param("generationId") byte[] generationId,
            @Param("expectedTerminalVersion") int expectedTerminalVersion,
            @Param("terminalPendingStatus") int terminalPendingStatus,
            @Param("finalStatus") int finalStatus,
            @Param("now") OffsetDateTime now);

    int markReconcileRequired(
            @Param("generationId") byte[] generationId,
            @Param("expectedStatuses") List<Integer> expectedStatuses,
            @Param("reconcileStatus") int reconcileStatus,
            @Param("terminalReason") String terminalReason,
            @Param("now") OffsetDateTime now);
}
