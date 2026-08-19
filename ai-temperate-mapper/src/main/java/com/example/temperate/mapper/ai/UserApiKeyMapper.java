package com.example.temperate.mapper.ai;

import com.example.temperate.model.ai.entity.ApiKeyReservationAuthorization;
import com.example.temperate.model.ai.entity.UserApiKey;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 该 Mapper 是来提供 API Key 幂等插入、摘要认证、稳定游标查询、乐观锁生命周期更新及预扣前行锁校验，明确不提供物理删除能力。
 */
@Mapper
public interface UserApiKeyMapper {

    int insert(UserApiKey apiKey);

    UserApiKey findByCreateIdempotencyKey(
            @Param("createIdempotencyKey") UUID createIdempotencyKey);

    UserApiKey findByDigest(@Param("keyDigest") byte[] keyDigest);

    UserApiKey findOwnedById(
            @Param("id") byte[] id,
            @Param("loginIdentityId") long loginIdentityId);

    UserApiKey findOwnedByIdForUpdate(
            @Param("id") byte[] id,
            @Param("loginIdentityId") long loginIdentityId);

    List<UserApiKey> findOwnedPage(
            @Param("loginIdentityId") long loginIdentityId,
            @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
            @Param("cursorId") byte[] cursorId,
            @Param("limit") int limit);

    List<UserApiKey> findBloomBuildPage(
            @Param("afterId") byte[] afterId,
            @Param("now") OffsetDateTime now,
            @Param("limit") int limit);

    int updateLifecycle(
            @Param("id") byte[] id,
            @Param("loginIdentityId") long loginIdentityId,
            @Param("expectedRowVersion") long expectedRowVersion,
            @Param("status") int status,
            @Param("expiresAt") OffsetDateTime expiresAt);

    int softDelete(
            @Param("id") byte[] id,
            @Param("loginIdentityId") long loginIdentityId,
            @Param("expectedRowVersion") long expectedRowVersion,
            @Param("deletedAt") OffsetDateTime deletedAt);

    int touchLastUsed(
            @Param("id") byte[] id,
            @Param("lastUsedAt") OffsetDateTime lastUsedAt);

    int incrementRowVersion(
            @Param("id") byte[] id,
            @Param("loginIdentityId") long loginIdentityId,
            @Param("expectedRowVersion") long expectedRowVersion);

    ApiKeyReservationAuthorization findReservationAuthorizationForUpdate(
            @Param("apiKeyId") byte[] apiKeyId,
            @Param("aiModelId") long aiModelId);
}
