package com.example.temperate.service.user.apikey.management.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.AiModelMapper;
import com.example.temperate.mapper.ai.UserApiKeyMapper;
import com.example.temperate.mapper.ai.UserApiKeyModelMapper;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.ai.entity.ApiKeyModelGrantView;
import com.example.temperate.model.ai.entity.UserApiKey;
import com.example.temperate.service.user.apikey.bloom.ApiKeyBloomService;
import com.example.temperate.service.user.apikey.cache.ApiKeyAuthenticationCache;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.apikey.credential.ApiKeyCredentialService;
import com.example.temperate.service.user.apikey.credential.GeneratedApiKey;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementErrorCode;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementException;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.CreateCommand;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.ReplaceModelsCommand;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Status;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.UpdateCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 该测试是来锁定 API Key 创建只返回一次明文、模型授权全量软替换、乐观锁和 HTTP 删除对应数据库软删除及提交后缓存/Bloom 收敛。
 */
final class UserApiKeyServiceImplTest {

    private static final byte[] DIGEST = new byte[32];
    private static final String PLAINTEXT = "sk-" + "A".repeat(86);
    private static final String IDENTIFIER = "B".repeat(43);
    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-08-13T12:00:00Z");

    private final PublicIdCodec publicIdCodec = new PublicIdCodec();
    private UserApiKeyMapper apiKeyMapper;
    private UserApiKeyModelMapper modelMapper;
    private AiModelMapper aiModelMapper;
    private UserLoginIdentityMapper identityMapper;
    private ApiKeyCredentialService credentialService;
    private ApiKeyAuthenticationCache authenticationCache;
    private ApiKeyBloomService bloomService;
    private UserApiKeyServiceImpl service;

    @BeforeEach
    void setUp() {
        apiKeyMapper = mock(UserApiKeyMapper.class);
        modelMapper = mock(UserApiKeyModelMapper.class);
        aiModelMapper = mock(AiModelMapper.class);
        identityMapper = mock(UserLoginIdentityMapper.class);
        credentialService = mock(ApiKeyCredentialService.class);
        authenticationCache = mock(ApiKeyAuthenticationCache.class);
        bloomService = mock(ApiKeyBloomService.class);
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setEnabled(true);
        when(credentialService.digestIdentifier(any(byte[].class)))
                .thenReturn(IDENTIFIER);
        when(credentialService.mask("abcd")).thenReturn("sk-…abcd");
        service = new UserApiKeyServiceImpl(
                apiKeyMapper,
                modelMapper,
                aiModelMapper,
                identityMapper,
                credentialService,
                authenticationCache,
                bloomService,
                properties,
                publicIdCodec,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createPersistsOnlyDigestAndKeepsPlaintextResponseWhenBloomCallbackFails() {
        TransactionSynchronizationManager.initSynchronization();
        String modelPublicId = publicIdCodec.encode(23L);
        GeneratedApiKey generated =
                new GeneratedApiKey(PLAINTEXT, DIGEST, "abcd", "sk-…abcd");
        ApiKeyBloomService.PositiveMutation mutation =
                new ApiKeyBloomService.PositiveMutation("mutation-1", DIGEST);
        UserApiKey persisted = key(11L, 1, 0L);
        when(identityMapper.existsById(17L)).thenReturn(true);
        when(aiModelMapper.findEnabledIdsForShare(List.of(23L)))
                .thenReturn(List.of(23L));
        when(credentialService.generate()).thenReturn(generated);
        when(bloomService.beginPositiveMutation(any(byte[].class)))
                .thenReturn(mutation);
        doAnswer(invocation -> {
            UserApiKey inserted = invocation.getArgument(0);
            inserted.setId(11L);
            return 1;
        }).when(apiKeyMapper).insert(any(UserApiKey.class));
        when(modelMapper.upsertActiveBatch(eq(11L), eq(List.of(23L)), any()))
                .thenReturn(1);
        when(apiKeyMapper.findOwnedById(11L, 17L)).thenReturn(persisted);
        when(modelMapper.findActiveModelDetails(11L))
                .thenReturn(List.of(grant(23L)));

        var created = service.create(
                17L,
                new CreateCommand(null, List.of(modelPublicId)));

        assertThat(created.apiKey()).isEqualTo(PLAINTEXT);
        assertThat(created.detail().key().maskedKey()).isEqualTo("sk-…abcd");
        ArgumentCaptor<UserApiKey> inserted = ArgumentCaptor.forClass(UserApiKey.class);
        verify(apiKeyMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getKeyDigest()).containsExactly(DIGEST);
        assertThat(inserted.getValue().getKeyHint()).isEqualTo("abcd");
        assertThat(inserted.getValue().getStatus()).isEqualTo(1);

        doThrow(new IllegalStateException("redis unavailable"))
                .when(bloomService).commitPositiveMutation(mutation);
        assertThatCode(UserApiKeyServiceImplTest::commitSynchronizations)
                .doesNotThrowAnyException();
        verify(bloomService).commitPositiveMutation(mutation);
        verify(authenticationCache).invalidate(IDENTIFIER);
    }

    @Test
    void replaceModelsRevokesMissingAndUpsertsTheWholeDesiredSet() {
        TransactionSynchronizationManager.initSynchronization();
        UserApiKey current = key(11L, 1, 3L);
        UserApiKey persisted = key(11L, 1, 4L);
        when(apiKeyMapper.findOwnedByIdForUpdate(11L, 17L)).thenReturn(current);
        when(aiModelMapper.findEnabledIdsForShare(List.of(24L, 25L)))
                .thenReturn(List.of(24L, 25L));
        when(modelMapper.findActiveModelIds(11L)).thenReturn(List.of(23L, 24L));
        when(modelMapper.revokeMissing(eq(11L), eq(List.of(24L, 25L)), any()))
                .thenReturn(1);
        when(modelMapper.upsertActiveBatch(eq(11L), eq(List.of(24L, 25L)), any()))
                .thenReturn(2);
        when(apiKeyMapper.incrementRowVersion(11L, 17L, 3L)).thenReturn(1);
        when(apiKeyMapper.findOwnedById(11L, 17L)).thenReturn(persisted);
        when(modelMapper.findActiveModelDetails(11L))
                .thenReturn(List.of(grant(24L), grant(25L)));

        var detail = service.replaceModels(
                17L,
                publicIdCodec.encode(11L),
                3L,
                new ReplaceModelsCommand(List.of(
                        publicIdCodec.encode(24L),
                        publicIdCodec.encode(25L))));

        assertThat(detail.key().rowVersion()).isEqualTo(4L);
        assertThat(detail.models()).extracting(model -> model.modelPublicId())
                .containsExactly(publicIdCodec.encode(24L), publicIdCodec.encode(25L));
        commitSynchronizations();
        verify(authenticationCache).invalidate(IDENTIFIER);
        verify(bloomService, never()).remove(any(byte[].class));
    }

    @Test
    void deleteOnlySoftDeletesAndRevokesEveryActiveGrant() {
        TransactionSynchronizationManager.initSynchronization();
        UserApiKey current = key(11L, 1, 3L);
        when(apiKeyMapper.findOwnedByIdForUpdate(11L, 17L)).thenReturn(current);
        when(modelMapper.findActiveModelIds(11L)).thenReturn(List.of(23L, 24L));
        when(modelMapper.revokeAll(eq(11L), any())).thenReturn(2);
        when(apiKeyMapper.softDelete(eq(11L), eq(17L), eq(3L), any()))
                .thenReturn(1);

        service.delete(17L, publicIdCodec.encode(11L), 3L);

        verify(modelMapper).revokeAll(eq(11L), any());
        verify(apiKeyMapper).softDelete(eq(11L), eq(17L), eq(3L), any());
        commitSynchronizations();
        verify(authenticationCache).invalidate(IDENTIFIER);
        verify(bloomService).remove(any(byte[].class));
    }

    @Test
    void staleVersionFailsBeforeAnyLifecycleUpdate() {
        when(apiKeyMapper.findOwnedByIdForUpdate(11L, 17L))
                .thenReturn(key(11L, 1, 4L));

        assertThatThrownBy(() -> service.update(
                17L,
                publicIdCodec.encode(11L),
                3L,
                new UpdateCommand(Status.DISABLED, null)))
                .isInstanceOf(ApiKeyManagementException.class)
                .extracting(failure -> ((ApiKeyManagementException) failure).code())
                .isEqualTo(ApiKeyManagementErrorCode.VERSION_CONFLICT);

        verify(apiKeyMapper, never()).updateLifecycle(
                eq(11L), eq(17L), eq(3L), eq(0), any());
    }

    private static UserApiKey key(long id, int status, long rowVersion) {
        UserApiKey key = new UserApiKey();
        key.setId(id);
        key.setLoginIdentityId(17L);
        key.setKeyDigest(DIGEST);
        key.setKeyHint("abcd");
        key.setStatus(status);
        key.setRowVersion(rowVersion);
        key.setCreatedAt(NOW.minusDays(1));
        key.setUpdatedAt(NOW);
        return key;
    }

    private static ApiKeyModelGrantView grant(long modelId) {
        ApiKeyModelGrantView grant = new ApiKeyModelGrantView();
        grant.setAiModelId(modelId);
        grant.setModelName("model-" + modelId);
        grant.setVendor("openai");
        grant.setEnabled(true);
        return grant;
    }

    private static void commitSynchronizations() {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(
                TransactionSynchronization.STATUS_COMMITTED));
    }
}
