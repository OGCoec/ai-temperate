package com.example.temperate.service.user.apikey.bloom.impl;

import com.example.temperate.service.bloom.CountingBloomEngine;
import com.example.temperate.service.bloom.CountingBloomNamespace;
import com.example.temperate.service.user.apikey.bloom.ApiKeyBloomService;
import com.example.temperate.service.user.apikey.bloom.ApiKeyBloomUnavailableException;
import com.example.temperate.service.user.apikey.credential.ApiKeyCredentialService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 该实现是来把 API Key 摘要映射到固定 v1 通用计数 Bloom，并用 positive mutation 把创建与恢复有效状态的崩溃窗口变为 DEGRADED 回源。
 */
@Service
public final class ApiKeyBloomServiceImpl implements ApiKeyBloomService {

    private final CountingBloomEngine engine;
    private final ApiKeyCredentialService credentialService;
    private final CountingBloomNamespace namespace;

    public ApiKeyBloomServiceImpl(
            CountingBloomEngine engine,
            ApiKeyCredentialService credentialService,
            @Qualifier("apiKeyCountingBloomNamespace")
            CountingBloomNamespace namespace) {
        this.engine = Objects.requireNonNull(engine);
        this.credentialService = Objects.requireNonNull(credentialService);
        this.namespace = Objects.requireNonNull(namespace);
    }

    @Override
    public LookupResult lookup(byte[] digest) {
        CountingBloomEngine.LookupResult result = engine.lookup(
                namespace, credentialService.digestIdentifier(digest));
        return switch (result) {
            case DEFINITELY_NOT_PRESENT -> LookupResult.DEFINITELY_NOT_PRESENT;
            case MAYBE_PRESENT -> LookupResult.MAYBE_PRESENT;
            case UNAVAILABLE -> LookupResult.UNAVAILABLE;
        };
    }

    @Override
    public PositiveMutation beginPositiveMutation(byte[] digest) {
        PositiveMutation mutation = new PositiveMutation(
                UUID.randomUUID().toString().replace("-", ""), digest.clone());
        try {
            engine.beginPositiveMutation(
                    namespace,
                    mutation.mutationId(),
                    credentialService.digestIdentifier(digest));
            return mutation;
        } catch (RuntimeException exception) {
            throw new ApiKeyBloomUnavailableException(
                    "API Key Bloom positive mutation is unavailable", exception);
        }
    }

    @Override
    public void commitPositiveMutation(PositiveMutation mutation) {
        CountingBloomEngine.UpdateResult result = engine.add(
                namespace,
                credentialService.digestIdentifier(mutation.digest()));
        engine.finishPositiveMutation(
                namespace,
                mutation.mutationId(),
                result != CountingBloomEngine.UpdateResult.UNAVAILABLE);
    }

    @Override
    public void rollbackPositiveMutation(PositiveMutation mutation) {
        engine.finishPositiveMutation(namespace, mutation.mutationId(), true);
    }

    @Override
    public void remove(byte[] digest) {
        CountingBloomEngine.UpdateResult result = engine.remove(
                namespace, credentialService.digestIdentifier(digest));
        if (result == CountingBloomEngine.UpdateResult.UNAVAILABLE) {
            engine.markDegraded(namespace, "api_key_remove_failed");
        }
    }

}
