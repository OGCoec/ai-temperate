package com.example.temperate.common.redis.key;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 统一生成符合项目命名空间、长度和敏感标识保护规则的 Redis Key。
 *
 * <p>该工厂将环境、业务域、版本和标识组合成受校验的键，并强制邮箱、手机号、令牌等敏感标识使用
 * HMAC 值；它不执行 Redis 读写，也不负责业务数据的序列化。</p>
 */
public final class RedisKeyFactory {

    public static final int TARGET_MAX_BYTES = 96;
    public static final int NORMAL_MAX_BYTES = 128;
    public static final int ABSOLUTE_MAX_BYTES = 256;

    private static final String PROJECT_PREFIX = "ait";
    private static final String REGISTRATION_DOMAIN = "auth";
    private static final String REGISTRATION_OBJECT = "register";
    private static final String REGISTRATION_VERSION = "v2";
    private static final String AUTH_DOMAIN = "auth";
    private static final String DEVICE_OBJECT = "device";
    private static final String LOGIN_LIMIT_OBJECT = "limit";
    private static final String LOGIN_OBJECT = "login";
    private static final String PASSWORD_RESET_OBJECT = "password-reset";
    private static final String SESSION_OBJECT = "session";
    private static final String AUTH_VERSION = "v2";
    private static final String LEGACY_SESSION_VERSION = "v3";
    private static final String SESSION_VERSION = "v4";
    private static final Pattern NAMESPACE_SEGMENT =
            Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final System.Logger LOGGER = System.getLogger(RedisKeyFactory.class.getName());

    private final String environment;
    private final Consumer<KeyLengthWarning> warningSink;

    public RedisKeyFactory(String environment) {
        this(environment, RedisKeyFactory::logWarning);
    }

    public RedisKeyFactory(String environment, Consumer<KeyLengthWarning> warningSink) {
        this.environment = requireNamespaceSegment("environment", environment);
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink must not be null");
    }

    public String idKey(String domain, String object, String version, long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("Redis ID key requires a positive ID.");
        }
        return create(domain, object, version, IdentifierType.ID, Long.toString(id));
    }

    public String emailKey(
            String domain, String object, String version, HmacIdentifier identifier) {
        return create(domain, object, version, IdentifierType.EMAIL,
                requireHmacIdentifier(identifier));
    }

    public String phoneKey(
            String domain, String object, String version, HmacIdentifier identifier) {
        return create(domain, object, version, IdentifierType.PHONE,
                requireHmacIdentifier(identifier));
    }

    public String bucketKey(String domain, String object, String version, int bucketNumber) {
        if (bucketNumber < 0 || bucketNumber > 9_999) {
            throw new IllegalArgumentException("Redis bucket number must be between 0 and 9999.");
        }
        return create(domain, object, version, IdentifierType.BUCKET,
                String.format(Locale.ROOT, "%04d", bucketNumber));
    }

    public String registrationFlowKey(HmacIdentifier identifier) {
        return registrationKey(IdentifierType.REGISTRATION_FLOW, identifier);
    }

    public String registrationEmailCodeKey(HmacIdentifier identifier) {
        return registrationKey(IdentifierType.REGISTRATION_EMAIL_CODE, identifier);
    }

    public String registrationPhoneCodeKey(HmacIdentifier identifier) {
        return registrationKey(IdentifierType.REGISTRATION_PHONE_CODE, identifier);
    }

    public String registrationConflictKey(HmacIdentifier identifier) {
        return registrationKey(IdentifierType.REGISTRATION_CONFLICT, identifier);
    }

    public String registrationBlockKey(HmacIdentifier identifier) {
        return registrationKey(IdentifierType.REGISTRATION_BLOCK, identifier);
    }

    public String registrationChallengeKey(HmacIdentifier identifier) {
        return registrationKey(IdentifierType.REGISTRATION_CHALLENGE, identifier);
    }

    public String registrationSendRiskKey(HmacIdentifier identifier) {
        return registrationKey(IdentifierType.REGISTRATION_SEND_RISK, identifier);
    }

    public String registrationVerifyRiskKey(HmacIdentifier identifier) {
        return registrationKey(IdentifierType.REGISTRATION_VERIFY_RISK, identifier);
    }

    public String loginFailureKey(HmacIdentifier identifier) {
        return authKey(LOGIN_LIMIT_OBJECT, IdentifierType.LOGIN_FAILURE, identifier);
    }

    public String loginBlockKey(HmacIdentifier identifier) {
        return authKey(LOGIN_LIMIT_OBJECT, IdentifierType.LOGIN_BLOCK, identifier);
    }

    public String loginPasswordFailureKey(HmacIdentifier identifier) {
        return authKey(LOGIN_LIMIT_OBJECT, IdentifierType.LOGIN_PASSWORD_FAILURE, identifier);
    }

    public String loginCodeFailureKey(HmacIdentifier identifier) {
        return authKey(LOGIN_LIMIT_OBJECT, IdentifierType.LOGIN_CODE_FAILURE, identifier);
    }

    public String globalDeviceBlockKey(HmacIdentifier identifier) {
        return authKey(DEVICE_OBJECT, IdentifierType.DEVICE_BLOCK, identifier);
    }

    public String loginFlowKey(HmacIdentifier identifier) {
        return authKey(LOGIN_OBJECT, IdentifierType.AUTH_FLOW, identifier);
    }

    public String loginEmailCodeKey(HmacIdentifier identifier) {
        return authKey(LOGIN_OBJECT, IdentifierType.REGISTRATION_EMAIL_CODE, identifier);
    }

    public String loginPhoneCodeKey(HmacIdentifier identifier) {
        return authKey(LOGIN_OBJECT, IdentifierType.REGISTRATION_PHONE_CODE, identifier);
    }

    public String loginCodeKey(HmacIdentifier identifier) {
        return authKey(LOGIN_OBJECT, IdentifierType.LOGIN_CODE, identifier);
    }

    public String loginChallengeKey(HmacIdentifier identifier) {
        return authKey(LOGIN_OBJECT, IdentifierType.REGISTRATION_CHALLENGE, identifier);
    }

    public String passwordResetFlowKey(HmacIdentifier identifier) {
        return authKey(PASSWORD_RESET_OBJECT, IdentifierType.AUTH_FLOW, identifier);
    }

    public String passwordResetForgetKey(HmacIdentifier identifier) {
        return authKey(PASSWORD_RESET_OBJECT, IdentifierType.PASSWORD_RESET_FORGET, identifier);
    }

    public String passwordResetChallengeKey(HmacIdentifier identifier) {
        return authKey(PASSWORD_RESET_OBJECT, IdentifierType.REGISTRATION_CHALLENGE, identifier);
    }

    public String passwordResetCodeKey(HmacIdentifier identifier) {
        return authKey(PASSWORD_RESET_OBJECT, IdentifierType.LOGIN_CODE, identifier);
    }

    public String passwordResetEmailCodeKey(HmacIdentifier identifier) {
        return authKey(PASSWORD_RESET_OBJECT, IdentifierType.REGISTRATION_EMAIL_CODE, identifier);
    }

    public String passwordResetPhoneCodeKey(HmacIdentifier identifier) {
        return authKey(PASSWORD_RESET_OBJECT, IdentifierType.REGISTRATION_PHONE_CODE, identifier);
    }

    public String passwordResetSendRiskKey(HmacIdentifier identifier) {
        return authKey(PASSWORD_RESET_OBJECT, IdentifierType.REGISTRATION_SEND_RISK, identifier);
    }

    public String passwordResetVerifyRiskKey(HmacIdentifier identifier) {
        return authKey(PASSWORD_RESET_OBJECT, IdentifierType.REGISTRATION_VERIFY_RISK, identifier);
    }

    public String passwordResetBlockKey(HmacIdentifier identifier) {
        return authKey(PASSWORD_RESET_OBJECT, IdentifierType.REGISTRATION_BLOCK, identifier);
    }

    public String passwordResetTargetSendKey(HmacIdentifier identifier) {
        return authKey(PASSWORD_RESET_OBJECT, IdentifierType.PASSWORD_RESET_TARGET_SEND, identifier);
    }

    /** 生成 Twilio Message SID 的 HMAC 索引键，键中不保存第三方返回的原始 SID。 */
    public String twilioMessageStatusKey(HmacIdentifier identifier) {
        return create(AUTH_DOMAIN, "verification", "v1",
                IdentifierType.TWILIO_MESSAGE_STATUS, requireHmacIdentifier(identifier));
    }

    public String sessionRefreshTokenKey(HmacIdentifier identifier) {
        return sessionKey(IdentifierType.SESSION_REFRESH_TOKEN, identifier);
    }

    public String sessionUserIndexKey(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("Session user index requires a positive user ID.");
        }
        return sessionUserIndexKey(SESSION_VERSION, userId);
    }

    /**
     * 生成旧版会话用户索引键，供 v3 到 v4 迁移期间清理已经存在的旧会话。
     *
     * <p>该方法只用于兼容窗口内的读取和删除，不得用于创建新会话。</p>
     */
    public String legacySessionUserIndexKey(long userId) {
        return sessionUserIndexKey(LEGACY_SESSION_VERSION, userId);
    }

    private String sessionUserIndexKey(String version, long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("Session user index requires a positive user ID.");
        }
        return create(AUTH_DOMAIN, SESSION_OBJECT, version,
                IdentifierType.SESSION_USER_INDEX, Long.toString(userId));
    }

    public String sessionRefreshTokenKeyPrefix() {
        return sessionPrefix(IdentifierType.SESSION_REFRESH_TOKEN);
    }

    /** 生成旧版刷新会话键前缀，供迁移期将旧索引字段转换为完整 Key。 */
    public String legacySessionRefreshTokenKeyPrefix() {
        return sessionPrefix(LEGACY_SESSION_VERSION, IdentifierType.SESSION_REFRESH_TOKEN);
    }

    public String sessionUserIndexKeyPrefix() {
        return sessionPrefix(IdentifierType.SESSION_USER_INDEX);
    }

    /** 生成旧版用户会话索引键前缀，供迁移期识别旧版本键空间。 */
    public String legacySessionUserIndexKeyPrefix() {
        return sessionPrefix(LEGACY_SESSION_VERSION, IdentifierType.SESSION_USER_INDEX);
    }

    private String sessionKey(IdentifierType type, HmacIdentifier identifier) {
        return sessionKey(SESSION_VERSION, type, identifier);
    }

    /**
     * 生成旧版刷新会话键，供迁移期间删除旧版本会话；新会话不得调用该方法写入 v3。
     */
    public String legacySessionRefreshTokenKey(HmacIdentifier identifier) {
        return sessionKey(LEGACY_SESSION_VERSION, IdentifierType.SESSION_REFRESH_TOKEN, identifier);
    }

    private String sessionKey(
            String version, IdentifierType type, HmacIdentifier identifier) {
        return create(AUTH_DOMAIN, SESSION_OBJECT, version, type,
                requireHmacIdentifier(identifier));
    }

    private String sessionPrefix(IdentifierType type) {
        return sessionPrefix(SESSION_VERSION, type);
    }

    private String sessionPrefix(String version, IdentifierType type) {
        return String.join(":", PROJECT_PREFIX, environment, AUTH_DOMAIN, SESSION_OBJECT,
                version, type.segment) + ":";
    }

    private String authKey(
            String object, IdentifierType type, HmacIdentifier identifier) {
        return create(AUTH_DOMAIN, object, AUTH_VERSION, type, requireHmacIdentifier(identifier));
    }

    private String authPrefix(String object, IdentifierType type) {
        return String.join(":", PROJECT_PREFIX, environment, AUTH_DOMAIN, object,
                AUTH_VERSION, type.segment) + ":";
    }

    private String registrationKey(IdentifierType type, HmacIdentifier identifier) {
        return create(REGISTRATION_DOMAIN, REGISTRATION_OBJECT, REGISTRATION_VERSION, type,
                requireHmacIdentifier(identifier));
    }

    private String create(
            String domain,
            String object,
            String version,
            IdentifierType type,
            String identifier) {
        String validDomain = requireNamespaceSegment("domain", domain);
        String validObject = requireNamespaceSegment("object", object);
        String validVersion = requireNamespaceSegment("version", version);
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Redis key identifier must use Base64URL-safe characters.");
        }

        // 统一在构造出口校验命名空间与长度，避免不同调用点产生不可监控或含敏感数据的键。
        String key = String.join(":", PROJECT_PREFIX, environment, validDomain,
                validObject, validVersion, type.segment, identifier);
        int byteLength = key.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > ABSOLUTE_MAX_BYTES) {
            throw new IllegalArgumentException("Redis key exceeds the 256-byte absolute limit.");
        }
        if (byteLength > NORMAL_MAX_BYTES) {
            warningSink.accept(new KeyLengthWarning(environment, validDomain, validObject,
                    validVersion, type, byteLength));
        }
        return key;
    }

    private static String requireNamespaceSegment(String name, String value) {
        if (value == null || !NAMESPACE_SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase ASCII namespace segment.");
        }
        return value;
    }

    private static String requireHmacIdentifier(HmacIdentifier identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("Sensitive Redis keys require an HMAC identifier.");
        }
        return identifier.value();
    }

    private static void logWarning(KeyLengthWarning warning) {
        LOGGER.log(System.Logger.Level.WARNING,
                "Redis key exceeds {0} bytes: env={1}, domain={2}, object={3}, version={4}, type={5}, bytes={6}",
                NORMAL_MAX_BYTES, warning.environment(), warning.domain(), warning.object(),
                warning.version(), warning.type().segment, warning.byteLength());
    }

    public enum IdentifierType {
        ID("id"),
        EMAIL("email"),
        PHONE("phone"),
        BUCKET("bucket"),
        REGISTRATION_FLOW("flow"),
        REGISTRATION_EMAIL_CODE("email-code"),
        REGISTRATION_PHONE_CODE("phone-code"),
        REGISTRATION_CONFLICT("conflict"),
        REGISTRATION_BLOCK("block"),
        REGISTRATION_CHALLENGE("challenge"),
        REGISTRATION_SEND_RISK("send-risk"),
        REGISTRATION_VERIFY_RISK("verify-risk"),
        LOGIN_FAILURE("login-failure"),
        LOGIN_PASSWORD_FAILURE("password-failure"),
        LOGIN_CODE_FAILURE("code-failure"),
        LOGIN_BLOCK("login-block"),
        DEVICE_BLOCK("block"),
        LOGIN_CODE("code"),
        AUTH_FLOW("flow"),
        PASSWORD_RESET_FORGET("forget"),
        PASSWORD_RESET_TARGET_SEND("target-send"),
        TWILIO_MESSAGE_STATUS("twilio-status"),
        SESSION_REFRESH_TOKEN("rt"),
        SESSION_USER_INDEX("user-rts");

        private final String segment;

        IdentifierType(String segment) {
            this.segment = segment;
        }
    }

    public record KeyLengthWarning(
            String environment,
            String domain,
            String object,
            String version,
            IdentifierType type,
            int byteLength) {
    }
}
