package com.example.temperate.service.user.apikey.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.apikey.authentication.impl.ApiKeyAuthenticationServiceImpl;
import com.example.temperate.service.user.apikey.bloom.ApiKeyBloomService;
import com.example.temperate.service.user.apikey.cache.ApiKeyAuthenticationCache;
import com.example.temperate.service.user.apikey.cache.ApiKeyAuthenticationCache.CachedCredential;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.apikey.credential.ApiKeyCredentialService;
import com.example.temperate.service.user.apikey.credential.impl.ApiKeyCredentialServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束 API Key 认证的格式→Bloom→缓存→PostgreSQL 懒加载顺序、Bloom Fail Open、生命周期状态检查和统一无效凭证结果。
 */
final class ApiKeyAuthenticationServiceImplTest {

    private static final String API_KEY = "sk-" + "A".repeat(86);
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");

    @Test
    void firstMaybePresentCallLoadsDatabaseAndSecondCallUsesPositiveCache() {
        Fixture fixture = fixture(ApiKeyBloomService.LookupResult.MAYBE_PRESENT);

        ApiKeyPrincipal first = fixture.service().authenticate(API_KEY);
        ApiKeyPrincipal second = fixture.service().authenticate(API_KEY);

        assertThat(first.apiKeyId()).isEqualTo(11L);
        assertThat(second.modelIds()).containsExactly(23L);
        assertThat(fixture.databaseLoads()).isEqualTo(1);
    }

    @Test
    void unavailableBloomFailsOpenToDatabase() {
        Fixture fixture = fixture(ApiKeyBloomService.LookupResult.UNAVAILABLE);

        assertThat(fixture.service().authenticate(API_KEY).loginIdentityId()).isEqualTo(17L);
        assertThat(fixture.databaseLoads()).isEqualTo(1);
    }

    @Test
    void definitiveBloomNegativeNeverTouchesDatabaseAndUsesUnifiedError() {
        Fixture fixture = fixture(ApiKeyBloomService.LookupResult.DEFINITELY_NOT_PRESENT);

        assertThatThrownBy(() -> fixture.service().authenticate(API_KEY))
                .isInstanceOf(ApiKeyAuthenticationException.class);
        assertThat(fixture.databaseLoads()).isZero();
        assertThat(fixture.cached().negative()).isTrue();
    }

    @Test
    void databaseMissUsesUnifiedErrorAndNegativeCache() {
        Fixture fixture = fixture(ApiKeyBloomService.LookupResult.MAYBE_PRESENT, null);

        assertInvalidApiKey(fixture);
        assertInvalidApiKey(fixture);

        assertThat(fixture.databaseLoads()).isEqualTo(1);
        assertThat(fixture.cached()).isNotNull();
        assertThat(fixture.cached().negative()).isTrue();
    }

    @Test
    void disabledCredentialLoadedFromDatabaseUsesUnifiedErrorAndNegativeCache() {
        Fixture fixture = fixture(
                ApiKeyBloomService.LookupResult.MAYBE_PRESENT,
                credential(0, null));

        assertInvalidApiKey(fixture);

        assertThat(fixture.databaseLoads()).isEqualTo(1);
        assertThat(fixture.cached()).isNotNull();
        assertThat(fixture.cached().negative()).isTrue();
    }

    @Test
    void softDeletedCredentialLoadedFromDatabaseUsesUnifiedErrorAndNegativeCache() {
        Fixture fixture = fixture(
                ApiKeyBloomService.LookupResult.MAYBE_PRESENT,
                credential(2, null));

        assertInvalidApiKey(fixture);

        assertThat(fixture.databaseLoads()).isEqualTo(1);
        assertThat(fixture.cached()).isNotNull();
        assertThat(fixture.cached().negative()).isTrue();
    }

    @Test
    void expiredCredentialLoadedFromDatabaseUsesUnifiedErrorAndNegativeCache() {
        Fixture fixture = fixture(
                ApiKeyBloomService.LookupResult.MAYBE_PRESENT,
                credential(1, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));

        assertInvalidApiKey(fixture);

        assertThat(fixture.databaseLoads()).isEqualTo(1);
        assertThat(fixture.cached()).isNotNull();
        assertThat(fixture.cached().negative()).isTrue();
    }

    @Test
    void disabledPositiveCacheCannotBypassCredentialStatusCheck() {
        Fixture fixture = fixture(ApiKeyBloomService.LookupResult.MAYBE_PRESENT);
        fixture.cache(credential(0, null));

        assertInvalidApiKey(fixture);

        assertThat(fixture.databaseLoads()).isZero();
    }

    @Test
    void expiredPositiveCacheCannotBypassCredentialExpiryCheck() {
        Fixture fixture = fixture(ApiKeyBloomService.LookupResult.MAYBE_PRESENT);
        fixture.cache(credential(
                1, OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC)));

        assertInvalidApiKey(fixture);

        assertThat(fixture.databaseLoads()).isZero();
    }

    private static Fixture fixture(ApiKeyBloomService.LookupResult result) {
        return fixture(result, credential(1, null));
    }

    private static Fixture fixture(
            ApiKeyBloomService.LookupResult result,
            CachedCredential databaseCredential) {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setEnabled(true);
        properties.setHmacSecretBase64(Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        ApiKeyCredentialService credentialService =
                new ApiKeyCredentialServiceImpl(properties);
        MemoryCache cache = new MemoryCache();
        AtomicInteger databaseLoads = new AtomicInteger();
        ApiKeyAuthenticationDatabaseService database = digest -> {
            databaseLoads.incrementAndGet();
            return databaseCredential;
        };
        ApiKeyAuthenticationService service = new ApiKeyAuthenticationServiceImpl(
                credentialService,
                bloom(result),
                cache,
                database,
                new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(service, databaseLoads, cache.value);
    }

    private static CachedCredential credential(int status, OffsetDateTime expiresAt) {
        return new CachedCredential(
                1, 11L, 17L, status, expiresAt, Set.of(23L), false);
    }

    private static void assertInvalidApiKey(Fixture fixture) {
        assertThatThrownBy(() -> fixture.service().authenticate(API_KEY))
                .isExactlyInstanceOf(ApiKeyAuthenticationException.class);
    }

    private static ApiKeyBloomService bloom(ApiKeyBloomService.LookupResult result) {
        return new ApiKeyBloomService() {
            @Override
            public LookupResult lookup(byte[] digest) {
                return result;
            }

            @Override
            public PositiveMutation beginPositiveMutation(byte[] digest) {
                throw new UnsupportedOperationException("authentication test only");
            }

            @Override
            public void commitPositiveMutation(PositiveMutation mutation) {
                throw new UnsupportedOperationException("authentication test only");
            }

            @Override
            public void rollbackPositiveMutation(PositiveMutation mutation) {
                throw new UnsupportedOperationException("authentication test only");
            }

            @Override
            public void remove(byte[] digest) {
                throw new UnsupportedOperationException("authentication test only");
            }
        };
    }

    private static final class MemoryCache implements ApiKeyAuthenticationCache {
        private final AtomicReference<CachedCredential> value = new AtomicReference<>();

        @Override
        public CachedCredential get(String digestIdentifier) {
            return value.get();
        }

        @Override
        public void putPositive(String digestIdentifier, CachedCredential credential) {
            value.set(credential);
        }

        @Override
        public void putNegative(String digestIdentifier) {
            value.set(CachedCredential.negativeEntry());
        }

        @Override
        public void invalidate(String digestIdentifier) {
            value.set(null);
        }
    }

    private record Fixture(
            ApiKeyAuthenticationService service,
            AtomicInteger databaseLoadCounter,
            AtomicReference<CachedCredential> cachedReference) {

        int databaseLoads() {
            return databaseLoadCounter.get();
        }

        CachedCredential cached() {
            return cachedReference.get();
        }

        void cache(CachedCredential credential) {
            cachedReference.set(credential);
        }
    }
}
