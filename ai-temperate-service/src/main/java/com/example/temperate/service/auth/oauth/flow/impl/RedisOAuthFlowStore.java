package com.example.temperate.service.auth.oauth.flow.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.oauth.domain.OAuthProofType;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.service.auth.oauth.flow.OAuthAuthorizationStateSnapshot;
import com.example.temperate.service.auth.oauth.flow.OAuthClientPlatform;
import com.example.temperate.service.auth.oauth.flow.OAuthCompletionClaim;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowErrorCode;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowException;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowSnapshot;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowState;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowStore;
import com.example.temperate.service.auth.oauth.flow.OAuthInteractionMode;
import com.example.temperate.service.auth.oauth.flow.ProtectedOAuthAuthorizationState;
import com.example.temperate.service.auth.oauth.flow.ProtectedOAuthFlowAccess;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountDecision;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 使用 Redis Hash 与短时 Lua 脚本保存 OAuth Flow、state、launch ticket 和原生 nonce。
 *
 * <p>Redis Key 只使用用途隔离 HMAC；state、launch ticket 与 nonce 的消费在单脚本内校验绑定并标记或删除，
 * Redis 不可用时统一 Fail Closed，不允许继续换码、发短信或签发会话。</p>
 */
@Component
public final class RedisOAuthFlowStore implements OAuthFlowStore {

    private static final long IDLE_TTL_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final long ABSOLUTE_TTL_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final long BROWSER_TTL_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final RedisScript<Long> CREATE_FLOW = longScript("create_oauth_flow.lua");
    private static final RedisScript<List> GET_FLOW = listScript("get_oauth_flow.lua");
    private static final RedisScript<Long> COMPLETE_PROVIDER =
            longScript("complete_oauth_provider.lua");
    private static final RedisScript<Long> CREATE_STATE =
            longScript("create_oauth_authorization_state.lua");
    private static final RedisScript<List> CONSUME_STATE =
            listScript("consume_oauth_authorization_state.lua");
    private static final RedisScript<Long> CREATE_LAUNCH =
            longScript("create_oauth_launch_ticket.lua");
    private static final RedisScript<List> CONSUME_LAUNCH =
            listScript("consume_oauth_launch_ticket.lua");
    private static final RedisScript<Long> CONSUME_NATIVE_NONCE =
            longScript("consume_oauth_native_nonce.lua");
    private static final RedisScript<Long> BIND_PHONE_FLOW =
            longScript("bind_oauth_phone_flow.lua");
    private static final RedisScript<Long> MARK_PHONE_HUMAN =
            longScript("mark_oauth_phone_human_verified.lua");
    private static final RedisScript<Long> REQUIRE_PHONE_READY =
            longScript("require_oauth_phone_code_ready.lua");
    private static final RedisScript<Long> MARK_PHONE_VERIFIED =
            longScript("mark_oauth_phone_verified.lua");
    private static final RedisScript<Long> CLAIM_COMPLETION =
            longScript("claim_oauth_completion.lua");
    private static final RedisScript<Long> RELEASE_COMPLETION =
            longScript("release_oauth_completion.lua");
    private static final RedisScript<Long> MARK_COMPLETION =
            longScript("mark_oauth_completion.lua");
    private static final RedisScript<Long> MARK_FAILED =
            longScript("mark_oauth_failed.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;

    public RedisOAuthFlowStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
    }

    @Override
    public void create(
            ProtectedOAuthFlowAccess access,
            OAuthProvider provider,
            OAuthClientPlatform platform,
            OAuthInteractionMode interactionMode,
            HmacIdentifier nativeNonceId,
            Instant createdAt) {
        ProtectedOAuthFlowAccess valid = requireAccess(access);
        requireMode(provider, platform, interactionMode, nativeNonceId);
        Long result = execute(
                CREATE_FLOW,
                List.of(keyFactory.oauthFlowKey(valid.flowId())),
                Long.toString(createdAt.toEpochMilli()),
                Long.toString(IDLE_TTL_MILLIS),
                Long.toString(ABSOLUTE_TTL_MILLIS),
                provider.name(),
                platform.name(),
                interactionMode.name(),
                valid.deviceId().value(),
                valid.clientIpId().value(),
                nativeNonceId == null ? "" : nativeNonceId.value());
        if (result != 0L) {
            throw unavailable();
        }
    }

    @Override
    public OAuthFlowSnapshot getRequired(ProtectedOAuthFlowAccess access, Instant now) {
        ProtectedOAuthFlowAccess valid = requireAccess(access);
        return snapshot(execute(
                GET_FLOW,
                List.of(keyFactory.oauthFlowKey(valid.flowId())),
                valid.deviceId().value(),
                valid.clientIpId().value(),
                Long.toString(now.toEpochMilli()),
                Long.toString(IDLE_TTL_MILLIS)));
    }

    @Override
    public void completeProvider(
            HmacIdentifier flowId,
            TrustedOAuthIdentity identity,
            OAuthAccountDecision decision,
            Instant now) {
        Objects.requireNonNull(flowId);
        Objects.requireNonNull(identity);
        Objects.requireNonNull(decision);
        long result = execute(
                COMPLETE_PROVIDER,
                List.of(keyFactory.oauthFlowKey(flowId)),
                Long.toString(now.toEpochMilli()),
                identity.provider().name(),
                identity.providerSubject(),
                identity.verifiedEmail(),
                identity.proofType().name(),
                Long.toString(decision.existingIdentityId()),
                "1",
                decision.phoneRequired() ? "1" : "0",
                Long.toString(IDLE_TTL_MILLIS));
        requireTransitionResult(result);
    }

    @Override
    public void createAuthorizationState(
            ProtectedOAuthAuthorizationState state,
            HmacIdentifier flowId,
            OAuthProvider provider,
            OAuthClientPlatform platform,
            String codeVerifier,
            HmacIdentifier nonceId,
            String redirectUri,
            Instant createdAt) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(flowId);
        Objects.requireNonNull(provider);
        requirePkceVerifier(codeVerifier);
        requireRedirectUri(redirectUri);
        Instant expiresAt = createdAt.plusMillis(BROWSER_TTL_MILLIS);
        long result = execute(
                CREATE_STATE,
                List.of(keyFactory.oauthAuthorizationStateKey(state.stateId())),
                flowId.value(),
                state.browserBindingId().value(),
                provider.name(),
                platform.name(),
                codeVerifier,
                nonceId == null ? "" : nonceId.value(),
                redirectUri,
                Long.toString(expiresAt.toEpochMilli()));
        if (result != 0L) {
            throw new OAuthFlowException(
                    OAuthFlowErrorCode.STATE_REJECTED,
                    "OAuth authorization state could not be created.");
        }
    }

    @Override
    public OAuthAuthorizationStateSnapshot consumeAuthorizationState(
            ProtectedOAuthAuthorizationState state,
            OAuthProvider expectedProvider,
            Instant now) {
        Objects.requireNonNull(state);
        List<?> result = execute(
                CONSUME_STATE,
                List.of(keyFactory.oauthAuthorizationStateKey(state.stateId())),
                state.browserBindingId().value(),
                expectedProvider.name(),
                Long.toString(now.toEpochMilli()));
        int status = status(result);
        if (status != 0 || result.size() < 7) {
            throw stateRejected(status);
        }
        String nonce = text(result.get(5));
        return new OAuthAuthorizationStateSnapshot(
                HmacIdentifier.fromProtectedValue(text(result.get(1))),
                OAuthProvider.valueOf(text(result.get(2))),
                OAuthClientPlatform.valueOf(text(result.get(3))),
                text(result.get(4)),
                nonce.isBlank() ? null : HmacIdentifier.fromProtectedValue(nonce),
                text(result.get(6)));
    }

    @Override
    public void createLaunchTicket(
            HmacIdentifier launchTicketId,
            HmacIdentifier flowId,
            OAuthProvider provider,
            Instant createdAt) {
        Instant expiresAt = createdAt.plusMillis(BROWSER_TTL_MILLIS);
        long result = execute(
                CREATE_LAUNCH,
                List.of(keyFactory.oauthLaunchTicketKey(launchTicketId)),
                flowId.value(),
                provider.name(),
                Long.toString(expiresAt.toEpochMilli()));
        if (result != 0L) {
            throw new OAuthFlowException(
                    OAuthFlowErrorCode.STATE_REJECTED,
                    "OAuth launch ticket could not be created.");
        }
    }

    @Override
    public HmacIdentifier consumeLaunchTicket(
            HmacIdentifier launchTicketId,
            OAuthProvider expectedProvider,
            Instant now) {
        List<?> result = execute(
                CONSUME_LAUNCH,
                List.of(keyFactory.oauthLaunchTicketKey(launchTicketId)),
                expectedProvider.name(),
                Long.toString(now.toEpochMilli()));
        int status = status(result);
        if (status != 0 || result.size() < 2) {
            throw stateRejected(status);
        }
        return HmacIdentifier.fromProtectedValue(text(result.get(1)));
    }

    @Override
    public void consumeNativeNonce(
            ProtectedOAuthFlowAccess access,
            HmacIdentifier presentedNonceId,
            Instant now) {
        ProtectedOAuthFlowAccess valid = requireAccess(access);
        long result = execute(
                CONSUME_NATIVE_NONCE,
                List.of(keyFactory.oauthFlowKey(valid.flowId())),
                Long.toString(now.toEpochMilli()),
                valid.deviceId().value(),
                valid.clientIpId().value(),
                Objects.requireNonNull(presentedNonceId).value());
        if (result != 0L) {
            throw new OAuthFlowException(
                    result == 2L ? OAuthFlowErrorCode.FLOW_EXPIRED
                            : OAuthFlowErrorCode.NONCE_REJECTED,
                    "OAuth native nonce was rejected.");
        }
    }

    @Override
    public void bindPhoneFlow(
            ProtectedOAuthFlowAccess access,
            HmacIdentifier phoneFlowId,
            HmacIdentifier phoneChallengeId,
            String normalizedPhone,
            Instant now) {
        executePhoneTransition(
                BIND_PHONE_FLOW,
                requireAccess(access),
                now,
                Objects.requireNonNull(phoneFlowId).value(),
                Objects.requireNonNull(phoneChallengeId).value(),
                requirePhone(normalizedPhone));
    }

    @Override
    public void markPhoneHumanVerified(
            ProtectedOAuthFlowAccess access,
            HmacIdentifier phoneFlowId,
            HmacIdentifier phoneChallengeId,
            Instant now) {
        executePhoneTransition(
                MARK_PHONE_HUMAN,
                requireAccess(access),
                now,
                Objects.requireNonNull(phoneFlowId).value(),
                Objects.requireNonNull(phoneChallengeId).value());
    }

    @Override
    public void requirePhoneCodeReady(
            ProtectedOAuthFlowAccess access,
            HmacIdentifier phoneFlowId,
            HmacIdentifier phoneChallengeId,
            Instant now) {
        executePhoneTransition(
                REQUIRE_PHONE_READY,
                requireAccess(access),
                now,
                Objects.requireNonNull(phoneFlowId).value(),
                Objects.requireNonNull(phoneChallengeId).value());
    }

    @Override
    public void markPhoneVerified(
            ProtectedOAuthFlowAccess access,
            HmacIdentifier phoneFlowId,
            HmacIdentifier phoneChallengeId,
            String verifiedPhone,
            Instant now) {
        executePhoneTransition(
                MARK_PHONE_VERIFIED,
                requireAccess(access),
                now,
                Objects.requireNonNull(phoneFlowId).value(),
                Objects.requireNonNull(phoneChallengeId).value(),
                requirePhone(verifiedPhone));
    }

    @Override
    public OAuthCompletionClaim claimCompletion(ProtectedOAuthFlowAccess access, Instant now) {
        ProtectedOAuthFlowAccess valid = requireAccess(access);
        long result = execute(
                CLAIM_COMPLETION,
                List.of(keyFactory.oauthFlowKey(valid.flowId())),
                Long.toString(now.toEpochMilli()),
                valid.deviceId().value(),
                valid.clientIpId().value());
        return switch (Math.toIntExact(result)) {
            case 0 -> OAuthCompletionClaim.CLAIMED;
            case 4 -> OAuthCompletionClaim.IN_PROGRESS;
            case 5 -> OAuthCompletionClaim.ALREADY_COMPLETED;
            case 1 -> throw new OAuthFlowException(
                    OAuthFlowErrorCode.FLOW_NOT_FOUND, "OAuth flow was not found.");
            case 2 -> throw new OAuthFlowException(
                    OAuthFlowErrorCode.FLOW_EXPIRED, "OAuth flow expired.");
            case 3 -> throw new OAuthFlowException(
                    OAuthFlowErrorCode.FLOW_FORBIDDEN, "OAuth flow is forbidden.");
            default -> throw new OAuthFlowException(
                    OAuthFlowErrorCode.INVALID_TRANSITION,
                    "OAuth flow is not ready to complete.");
        };
    }

    @Override
    public void releaseCompletionClaim(ProtectedOAuthFlowAccess access) {
        ProtectedOAuthFlowAccess valid = requireAccess(access);
        long result = execute(
                RELEASE_COMPLETION,
                List.of(keyFactory.oauthFlowKey(valid.flowId())),
                valid.deviceId().value(),
                valid.clientIpId().value());
        if (result != 0L && result != 1L) {
            requireTransitionResult(result);
        }
    }

    @Override
    public void markCompletionResult(
            ProtectedOAuthFlowAccess access,
            OAuthFlowState resultState,
            Instant now) {
        if (resultState != OAuthFlowState.AUTHENTICATED
                && resultState != OAuthFlowState.TOTP_REQUIRED) {
            throw new IllegalArgumentException("OAuth completion state is invalid.");
        }
        ProtectedOAuthFlowAccess valid = requireAccess(access);
        long result = execute(
                MARK_COMPLETION,
                List.of(keyFactory.oauthFlowKey(valid.flowId())),
                Long.toString(now.toEpochMilli()),
                valid.deviceId().value(),
                valid.clientIpId().value(),
                resultState.name());
        requireTransitionResult(result);
    }

    @Override
    public void markFailed(HmacIdentifier flowId, Instant now) {
        long result = execute(
                MARK_FAILED,
                List.of(keyFactory.oauthFlowKey(Objects.requireNonNull(flowId))),
                Long.toString(Objects.requireNonNull(now).toEpochMilli()));
        if (result != 0L && result != 1L && result != 2L) {
            requireTransitionResult(result);
        }
    }

    @Override
    public void delete(HmacIdentifier flowId) {
        try {
            redisTemplate.unlink(keyFactory.oauthFlowKey(Objects.requireNonNull(flowId)));
        } catch (RuntimeException exception) {
            throw new OAuthFlowException(
                    OAuthFlowErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "OAuth flow storage is unavailable.", exception);
        }
    }

    private static OAuthFlowSnapshot snapshot(List<?> result) {
        int status = status(result);
        if (status != 0) {
            throw switch (status) {
                case 1 -> new OAuthFlowException(
                        OAuthFlowErrorCode.FLOW_NOT_FOUND, "OAuth flow was not found.");
                case 2 -> new OAuthFlowException(
                        OAuthFlowErrorCode.FLOW_EXPIRED, "OAuth flow expired.");
                case 3 -> new OAuthFlowException(
                        OAuthFlowErrorCode.FLOW_FORBIDDEN, "OAuth flow is forbidden.");
                default -> unavailable();
            };
        }
        if (result.size() < 15) {
            throw unavailable();
        }
        OAuthProvider provider = OAuthProvider.valueOf(text(result.get(1)));
        String subject = text(result.get(5));
        TrustedOAuthIdentity identity = subject.isBlank()
                ? null
                : new TrustedOAuthIdentity(
                        provider,
                        subject,
                        text(result.get(6)),
                        true,
                        OAuthProofType.valueOf(text(result.get(7))));
        return new OAuthFlowSnapshot(
                provider,
                OAuthClientPlatform.valueOf(text(result.get(2))),
                OAuthInteractionMode.valueOf(text(result.get(3))),
                OAuthFlowState.valueOf(text(result.get(4))),
                identity,
                number(result.get(8)),
                "1".equals(text(result.get(9))),
                emptyToNull(text(result.get(10))),
                "1".equals(text(result.get(11))),
                Instant.ofEpochMilli(number(result.get(12))),
                Instant.ofEpochMilli(number(result.get(13))),
                Instant.ofEpochMilli(number(result.get(14))));
    }

    private static void requireMode(
            OAuthProvider provider,
            OAuthClientPlatform platform,
            OAuthInteractionMode mode,
            HmacIdentifier nonceId) {
        boolean nativeGoogle = provider == OAuthProvider.GOOGLE
                && platform == OAuthClientPlatform.ANDROID
                && mode == OAuthInteractionMode.GOOGLE_NATIVE;
        if (nativeGoogle != (nonceId != null)) {
            throw new IllegalArgumentException(
                    "Only Android native Google flows require a nonce.");
        }
        if (mode == OAuthInteractionMode.GOOGLE_NATIVE && !nativeGoogle) {
            throw new IllegalArgumentException("OAuth interaction mode is invalid.");
        }
    }

    private void executePhoneTransition(
            RedisScript<Long> script,
            ProtectedOAuthFlowAccess access,
            Instant now,
            String... trailingArguments) {
        Object[] arguments = new Object[3 + trailingArguments.length];
        arguments[0] = Long.toString(now.toEpochMilli());
        arguments[1] = access.deviceId().value();
        arguments[2] = access.clientIpId().value();
        System.arraycopy(trailingArguments, 0, arguments, 3, trailingArguments.length);
        long result = execute(
                script,
                List.of(keyFactory.oauthFlowKey(access.flowId())),
                arguments);
        requireTransitionResult(result);
    }

    private static String requirePhone(String value) {
        if (value == null || !value.matches("^\\+[1-9][0-9]{7,14}$")) {
            throw new IllegalArgumentException("OAuth phone must be normalized E.164.");
        }
        return value;
    }

    private static ProtectedOAuthFlowAccess requireAccess(ProtectedOAuthFlowAccess access) {
        if (access == null || access.flowId() == null
                || access.deviceId() == null || access.globalDeviceId() == null
                || access.clientIpId() == null) {
            throw new OAuthFlowException(
                    OAuthFlowErrorCode.FLOW_FORBIDDEN,
                    "OAuth flow access is invalid.");
        }
        return access;
    }

    private static void requirePkceVerifier(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9._~-]{43,128}$")) {
            throw new IllegalArgumentException("PKCE verifier is invalid.");
        }
    }

    private static void requireRedirectUri(String value) {
        if (value == null || !value.startsWith("https://") || value.length() > 512) {
            throw new IllegalArgumentException("OAuth redirect URI is invalid.");
        }
    }

    private static void requireTransitionResult(long result) {
        if (result == 0L) {
            return;
        }
        throw new OAuthFlowException(
                switch ((int) result) {
                    case 1 -> OAuthFlowErrorCode.FLOW_NOT_FOUND;
                    case 2 -> OAuthFlowErrorCode.FLOW_EXPIRED;
                    case 3 -> OAuthFlowErrorCode.FLOW_FORBIDDEN;
                    default -> OAuthFlowErrorCode.INVALID_TRANSITION;
                },
                "OAuth provider transition was rejected.");
    }

    private static OAuthFlowException stateRejected(int status) {
        return new OAuthFlowException(
                status == 2 ? OAuthFlowErrorCode.FLOW_EXPIRED
                        : OAuthFlowErrorCode.STATE_REJECTED,
                "OAuth browser state was rejected.");
    }

    private <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
        try {
            T result = redisTemplate.execute(script, keys, args);
            if (result == null) {
                throw unavailable();
            }
            return result;
        } catch (OAuthFlowException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OAuthFlowException(
                    OAuthFlowErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "OAuth flow storage is unavailable.", exception);
        }
    }

    private static int status(List<?> result) {
        if (result == null || result.isEmpty()) {
            throw unavailable();
        }
        return Math.toIntExact(number(result.getFirst()));
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue()
                : Long.parseLong(text(value));
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value == null) {
            return "";
        }
        return value.toString();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static OAuthFlowException unavailable() {
        return new OAuthFlowException(
                OAuthFlowErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                "OAuth flow storage is unavailable.");
    }

    private static RedisScript<Long> longScript(String name) {
        return script(name, Long.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RedisScript<List> listScript(String name) {
        return (RedisScript) script(name, List.class);
    }

    private static <T> RedisScript<T> script(String name, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/auth-oauth/" + name));
        script.setResultType(type);
        return script;
    }
}
