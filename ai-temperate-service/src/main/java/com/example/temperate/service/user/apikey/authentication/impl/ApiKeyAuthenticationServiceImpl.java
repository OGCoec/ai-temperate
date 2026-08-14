package com.example.temperate.service.user.apikey.authentication.impl;

import com.example.temperate.service.user.apikey.authentication.ApiKeyAuthenticationDatabaseService;
import com.example.temperate.service.user.apikey.authentication.ApiKeyAuthenticationException;
import com.example.temperate.service.user.apikey.authentication.ApiKeyAuthenticationInfrastructureException;
import com.example.temperate.service.user.apikey.authentication.ApiKeyAuthenticationService;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.bloom.ApiKeyBloomService;
import com.example.temperate.service.user.apikey.cache.ApiKeyAuthenticationCache;
import com.example.temperate.service.user.apikey.cache.ApiKeyAuthenticationCache.CachedCredential;
import com.example.temperate.service.user.apikey.credential.ApiKeyCredentialService;
import com.example.temperate.service.user.apikey.credential.InvalidApiKeyFormatException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 该实现是来执行 API Key Cache-Aside 懒加载认证；Bloom 和 Redis 只做快速判断，数据库仍是事实来源且所有失败统一对外隐藏。
 */
@Service
public final class ApiKeyAuthenticationServiceImpl implements ApiKeyAuthenticationService {

    private static final int STATUS_ENABLED = 1;

    private final ApiKeyCredentialService credentialService;
    private final ApiKeyBloomService bloomService;
    private final ApiKeyAuthenticationCache cache;
    private final ApiKeyAuthenticationDatabaseService databaseService;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public ApiKeyAuthenticationServiceImpl(
            ApiKeyCredentialService credentialService,
            ApiKeyBloomService bloomService,
            ApiKeyAuthenticationCache cache,
            ApiKeyAuthenticationDatabaseService databaseService,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.credentialService = Objects.requireNonNull(credentialService);
        this.bloomService = Objects.requireNonNull(bloomService);
        this.cache = Objects.requireNonNull(cache);
        this.databaseService = Objects.requireNonNull(databaseService);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public ApiKeyPrincipal authenticate(String plaintextApiKey) {
        byte[] digest;
        try {
            digest = credentialService.digest(plaintextApiKey);
        } catch (InvalidApiKeyFormatException exception) {
            counter("format_invalid");
            throw invalid();
        }
        String identifier = credentialService.digestIdentifier(digest);

        ApiKeyBloomService.LookupResult bloom = bloomService.lookup(digest);
        if (bloom == ApiKeyBloomService.LookupResult.DEFINITELY_NOT_PRESENT) {
            cache.putNegative(identifier);
            counter("bloom_negative");
            throw invalid();
        }

        CachedCredential cached = cache.get(identifier);
        if (cached != null) {
            if (cached.negative() || !isEffective(cached.status(), cached.expiresAt())) {
                counter("cache_invalid");
                throw invalid();
            }
            counter("cache_positive");
            return principal(cached, digest, identifier);
        }

        // Bloom 为 UNAVAILABLE 或 MAYBE_PRESENT 都必须回源，只有数据库能够判定凭证真值。
        CachedCredential loaded;
        try {
            loaded = databaseService.load(digest);
        } catch (RuntimeException exception) {
            counter("database_unavailable");
            throw new ApiKeyAuthenticationInfrastructureException(exception);
        }
        if (loaded == null || !isEffective(loaded.status(), loaded.expiresAt())) {
            cache.putNegative(identifier);
            if (bloom == ApiKeyBloomService.LookupResult.MAYBE_PRESENT) {
                counter("bloom_false_positive");
            }
            counter("database_invalid");
            throw invalid();
        }
        cache.putPositive(identifier, loaded);
        counter(bloom == ApiKeyBloomService.LookupResult.UNAVAILABLE
                ? "database_fallback" : "database_loaded");
        return principal(loaded, digest, identifier);
    }

    private ApiKeyPrincipal principal(
            CachedCredential cached,
            byte[] digest,
            String identifier) {
        return new ApiKeyPrincipal(
                cached.apiKeyId(),
                cached.loginIdentityId(),
                digest,
                identifier,
                cached.modelIds());
    }

    private boolean isEffective(int status, OffsetDateTime expiresAt) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return status == STATUS_ENABLED && (expiresAt == null || expiresAt.isAfter(now));
    }

    private void counter(String result) {
        meterRegistry.counter("api.key.authentication", "result", result).increment();
    }

    private static ApiKeyAuthenticationException invalid() {
        return new ApiKeyAuthenticationException();
    }
}
