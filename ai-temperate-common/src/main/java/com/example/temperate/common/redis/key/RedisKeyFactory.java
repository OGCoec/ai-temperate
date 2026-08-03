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
 * HMAC 值、用户资料内部 ID 使用专用加密标识；它不执行 Redis 读写，也不负责业务数据序列化。</p>
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

    /**
     * 生成已注册身份 Bloom 双版本状态机使用的固定控制 Key。
     */
    public String identityPresenceBloomControlKey() {
        return create(
                "bloom",
                "uli-presence",
                "v1",
                IdentifierType.BLOOM_CONTROL,
                "state");
    }

    /**
     * 生成全量构建使用的分布式租约 Key，防止多个应用实例同时重建。
     */
    public String identityPresenceBloomBuildLockKey() {
        return create(
                "bloom",
                "uli-presence",
                "v1",
                IdentifierType.BLOOM_BUILD_LOCK,
                "lease");
    }

    /**
     * 生成某一构建代次的参数和统计元数据 Key。
     */
    public String identityPresenceBloomMetaKey(String generation) {
        return create(
                "bloom",
                "uli-presence",
                requireNamespaceSegment("generation", generation),
                IdentifierType.BLOOM_META,
                "config");
    }

    /**
     * 生成某一构建代次的用户 ID 幂等分片 Key。
     */
    public String identityPresenceBloomReceiptKey(String generation, int shardNumber) {
        if (shardNumber < 0 || shardNumber > 9_999) {
            throw new IllegalArgumentException("Bloom receipt shard must be between 0 and 9999.");
        }
        return create(
                "bloom",
                "uli-presence",
                requireNamespaceSegment("generation", generation),
                IdentifierType.BLOOM_RECEIPT,
                String.format(Locale.ROOT, "%04d", shardNumber));
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

    /**
     * 生成管理员短期登录流程 Key，Key 中只包含带业务域的 HMAC 标识。
     */
    public String adminLoginFlowKey(HmacIdentifier identifier) {
        return create(
                "admin",
                "login",
                "v1",
                IdentifierType.ADMIN_LOGIN_FLOW,
                requireHmacIdentifier(identifier));
    }

    /**
     * 生成唯一管理员全部设备会话共用的小型 Hash Key。
     *
     * <p>Hash Field 承载原始 Token 的 HMAC，Key 本身不包含管理员邮箱、手机号或 Token。</p>
     */
    public String adminSessionTokensKey() {
        return String.join(
                ":", PROJECT_PREFIX, environment, "admin", "session", "v1", "tokens");
    }

    /**
     * 生成 IP2Location 加密凭据 Hash Key；Hash Field 使用独立 HMAC 标识，Key 本身不携带凭据。
     */
    public String ip2LocationSecretHashKey() {
        return fixedKey("risk", "ip2location", "v1", "secret");
    }

    /**
     * 生成 IP2Location 剩余额度 Hash Key；字段必须与加密凭据 Hash 一一对应。
     */
    public String ip2LocationQuotaHashKey() {
        return fixedKey("risk", "ip2location", "v1", "quota");
    }

    /**
     * 生成全部启用 AI 模型共用的版本化加密快照 Key。
     *
     * <p>模型名称和厂商等业务内容只存在于加密 Value 中，Key 本身保持固定且不携带模型标识。</p>
     */
    public String aiModelEnabledSnapshotKey() {
        // v5 隔离细分前的媒体能力枚举，避免新应用把旧 IMAGE、AUDIO、VIDEO 快照当成有效数据。
        return fixedKey("ai", "model", "v5", "enabled");
    }

    /**
     * 生成保存压缩摘要、持久化尾部和中断覆盖层的 AI 会话上下文 Hash Key。
     */
    public String aiConversationContextKey(ConversationRedisId conversationId) {
        return aiConversationKey(IdentifierType.AI_CONVERSATION_CONTEXT, conversationId);
    }

    /**
     * 生成会话上下文分批重建的临时 Hash Key；提升成功前不会覆盖正式上下文。
     */
    public String aiConversationContextBuildKey(
            ConversationRedisId conversationId,
            ConversationRedisBuildId buildId) {
        if (conversationId == null || buildId == null) {
            throw new IllegalArgumentException(
                    "AI conversation Redis build key requires both IDs.");
        }
        return create(
                "ai",
                "conversation",
                "v1",
                IdentifierType.AI_CONVERSATION_CONTEXT_BUILD,
                conversationId.value() + "_" + buildId.value());
    }

    /**
     * 生成同一会话单活流式生成所使用的短期租约 Key。
     */
    public String aiConversationInflightKey(ConversationRedisId conversationId) {
        return aiConversationKey(IdentifierType.AI_CONVERSATION_INFLIGHT, conversationId);
    }

    /**
     * 生成同一会话持久化压缩任务所使用的单飞租约 Key。
     */
    public String aiConversationCompactionKey(ConversationRedisId conversationId) {
        return aiConversationKey(IdentifierType.AI_CONVERSATION_COMPACTION, conversationId);
    }

    /**
     * 生成全部应用实例共享的 AI 会话全局并发租约集合 Key。
     */
    public String aiConversationGlobalConcurrencyKey() {
        return fixedKey("ai", "conversation", "v1", "concurrency-global");
    }

    /**
     * 生成单个用户的 AI 会话并发租约集合 Key，内部用户 ID 必须先经过用途隔离 HMAC。
     */
    public String aiConversationUserConcurrencyKey(HmacIdentifier userIdentifier) {
        return create(
                "ai",
                "conversation",
                "v1",
                IdentifierType.AI_CONVERSATION_USER_CONCURRENCY,
                requireHmacIdentifier(userIdentifier));
    }

    /**
     * 生成直接 SSE 活动请求的实例所有权 Key，原始幂等 UUID 和用户 ID 均不会进入 Key。
     */
    public String aiConversationDirectResponseOwnerKey(
            HmacIdentifier requestIdentifier) {
        return create(
                "ai",
                "response",
                "v1",
                IdentifierType.AI_DIRECT_RESPONSE_OWNER,
                requireHmacIdentifier(requestIdentifier));
    }

    /**
     * 生成直接 SSE 显式 Stop 意图 Key，用于跨实例取消与订阅建立前竞态收敛。
     */
    public String aiConversationDirectResponseCancelKey(
            HmacIdentifier requestIdentifier) {
        return create(
                "ai",
                "response",
                "v1",
                IdentifierType.AI_DIRECT_RESPONSE_CANCEL,
                requireHmacIdentifier(requestIdentifier));
    }

    /**
     * 生成异步 Generation 的分块输出快照 Hash Key，公共 Generation ID 不包含内部数据库标识。
     */
    public String aiConversationGenerationSnapshotKey(
            GenerationRedisId generationId) {
        return create(
                "ai",
                "generation",
                "v1",
                IdentifierType.AI_GENERATION_SNAPSHOT,
                Objects.requireNonNull(generationId).value());
    }

    /**
     * 生成浏览器流式诊断摘要的单 Generation 去重 Key，公共 ID 仅用于限制重复提交，
     * Key 本身不保存模型正文、用户身份或诊断负载。
     */
    public String aiConversationGenerationBrowserDiagnosticKey(
            GenerationRedisId generationId) {
        return create(
                "ai",
                "generation",
                "v1",
                IdentifierType.AI_GENERATION_BROWSER_DIAGNOSTIC,
                Objects.requireNonNull(generationId).value());
    }

    /**
     * 生成异步 Generation 的全局易失通知频道，事件丢失由快照 revision 恢复。
     */
    public String aiConversationGenerationEventsChannel() {
        return fixedKey("ai", "generation", "v1", "events");
    }

    /**
     * 生成普通用户资料快照 Key，内部用户 ID 必须先经过专用 AES-256 保护器转换。
     */
    public String userProfileKey(EncryptedRedisId encryptedId) {
        if (encryptedId == null) {
            throw new IllegalArgumentException(
                    "User profile Redis key requires an encrypted ID.");
        }
        return create(
                "user",
                "profile",
                "v1",
                IdentifierType.ENCRYPTED_ID,
                encryptedId.value());
    }

    /**
     * 生成邮件检查任务元数据 Key，原始 Job ID 不会进入 Redis 命名空间。
     */
    public String adminMailInspectionJobMetaKey(HmacIdentifier jobHash) {
        return mailInspectionProtectedKey("meta", jobHash);
    }

    /**
     * 生成邮件检查任务计数 Key，计数与元数据共享同一绝对过期时间。
     */
    public String adminMailInspectionJobCountsKey(HmacIdentifier jobHash) {
        return mailInspectionProtectedKey("counts", jobHash);
    }

    /**
     * 生成邮件检查任务结果桶 Key，每个桶编号固定宽度以便受界扫描和诊断。
     */
    public String adminMailInspectionJobResultBucketKey(
            HmacIdentifier jobHash, int bucketNumber) {
        if (bucketNumber < 0 || bucketNumber > 9_999) {
            throw new IllegalArgumentException(
                    "Mail inspection result bucket must be between 0 and 9999.");
        }
        return mailInspectionKey(
                "results",
                requireHmacIdentifier(jobHash),
                String.format(Locale.ROOT, "%04d", bucketNumber));
    }

    /**
     * 生成邮件检查创建请求幂等索引 Key，客户端请求 ID 必须先使用独立用途 HMAC。
     */
    public String adminMailInspectionJobIdempotencyKey(
            HmacIdentifier requestHash) {
        return mailInspectionProtectedKey("idempotency", requestHash);
    }

    /**
     * 生成每种邮件检查类型的单活动任务索引 Key。
     */
    public String adminMailInspectionJobActiveKey(String inspectionType) {
        return mailInspectionKey(
                "active",
                requireNamespaceSegment("inspectionType", inspectionType));
    }

    /**
     * 生成每种邮件检查类型的接收闸门 Key。
     */
    public String adminMailInspectionJobAcceptanceKey(String inspectionType) {
        return mailInspectionKey(
                "acceptance",
                requireNamespaceSegment("inspectionType", inspectionType));
    }

    /**
     * 生成邮件检查任务单调修订号 Key。
     */
    public String adminMailInspectionJobRevisionKey(HmacIdentifier jobHash) {
        return mailInspectionProtectedKey("revision", jobHash);
    }

    /**
     * 生成邮件检查全局变更通知频道；该频道只承载唤醒通知，不保存任务历史。
     */
    public String adminMailInspectionJobEventsChannel() {
        return mailInspectionKey("events");
    }

    /**
     * 生成独立 IP 信用快照缓存 Key，明文 IP 必须先转换为 HMAC 标识。
     */
    public String ipIntelligenceCacheKey(HmacIdentifier identifier) {
        return create(
                "risk",
                "ipintel",
                "v3",
                IdentifierType.IP_INTELLIGENCE,
                requireHmacIdentifier(identifier));
    }

    /**
     * 生成同一 IP 外部查询的短期单飞锁 Key，避免多实例同时消耗第三方额度。
     */
    public String ipIntelligenceSingleFlightKey(HmacIdentifier identifier) {
        return create(
                "risk",
                "ipintel",
                "v3",
                IdentifierType.IP_SINGLE_FLIGHT,
                requireHmacIdentifier(identifier));
    }

    /**
     * 生成普通用户 PreAuth 状态 Key。
     */
    public String userPreAuthKey(HmacIdentifier identifier) {
        return create(
                "risk",
                "preauth-user",
                "v4",
                IdentifierType.PRE_AUTH,
                requireHmacIdentifier(identifier));
    }

    /**
     * 生成管理员 PreAuth 状态 Key。
     */
    public String adminPreAuthKey(HmacIdentifier identifier) {
        return create(
                "risk",
                "preauth-admin",
                "v4",
                IdentifierType.PRE_AUTH,
                requireHmacIdentifier(identifier));
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

    private String aiConversationKey(
            IdentifierType type, ConversationRedisId conversationId) {
        if (conversationId == null) {
            throw new IllegalArgumentException(
                    "AI conversation Redis key requires a conversation ID.");
        }
        return create("ai", "conversation", "v1", type, conversationId.value());
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

    private String fixedKey(
            String domain,
            String object,
            String version,
            String terminalSegment) {
        String validDomain = requireNamespaceSegment("domain", domain);
        String validObject = requireNamespaceSegment("object", object);
        String validVersion = requireNamespaceSegment("version", version);
        String validTerminal = requireNamespaceSegment("terminalSegment", terminalSegment);
        String key = String.join(
                ":",
                PROJECT_PREFIX,
                environment,
                validDomain,
                validObject,
                validVersion,
                validTerminal);
        int byteLength = key.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > ABSOLUTE_MAX_BYTES) {
            throw new IllegalArgumentException("Redis key exceeds the 256-byte absolute limit.");
        }
        return key;
    }

    private String mailInspectionProtectedKey(
            String type, HmacIdentifier identifier) {
        return mailInspectionKey(type, requireHmacIdentifier(identifier));
    }

    private String mailInspectionKey(String... suffixSegments) {
        String[] segments = new String[5 + suffixSegments.length];
        segments[0] = PROJECT_PREFIX;
        segments[1] = environment;
        segments[2] = "admin-mail";
        segments[3] = "job";
        segments[4] = "v2";
        for (int index = 0; index < suffixSegments.length; index++) {
            String segment = suffixSegments[index];
            if (segment == null
                    || (!NAMESPACE_SEGMENT.matcher(segment).matches()
                    && !IDENTIFIER.matcher(segment).matches())) {
                throw new IllegalArgumentException(
                        "Mail inspection Redis key segment is invalid.");
            }
            segments[index + 5] = segment;
        }
        String key = String.join(":", segments);
        int byteLength = key.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > ABSOLUTE_MAX_BYTES) {
            throw new IllegalArgumentException(
                    "Redis key exceeds the 256-byte absolute limit.");
        }
        if (byteLength > NORMAL_MAX_BYTES) {
            warningSink.accept(new KeyLengthWarning(
                    environment,
                    "admin-mail",
                    "job",
                    "v2",
                    IdentifierType.MAIL_JOB,
                    byteLength));
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
        BLOOM_CONTROL("control"),
        BLOOM_BUILD_LOCK("build-lock"),
        BLOOM_META("meta"),
        BLOOM_RECEIPT("receipt"),
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
        SESSION_USER_INDEX("user-rts"),
        ADMIN_LOGIN_FLOW("flow"),
        IP_INTELLIGENCE("ip"),
        IP_SINGLE_FLIGHT("single-flight"),
        PRE_AUTH("token"),
        ENCRYPTED_ID("enc-id"),
        AI_CONVERSATION_CONTEXT("ctx"),
        AI_CONVERSATION_CONTEXT_BUILD("ctx-build"),
        AI_CONVERSATION_INFLIGHT("inflight"),
        AI_CONVERSATION_COMPACTION("compact"),
        AI_CONVERSATION_USER_CONCURRENCY("concurrency-user"),
        AI_DIRECT_RESPONSE_OWNER("owner"),
        AI_DIRECT_RESPONSE_CANCEL("cancel"),
        AI_GENERATION_SNAPSHOT("snapshot"),
        AI_GENERATION_BROWSER_DIAGNOSTIC("browser-diagnostic"),
        MAIL_JOB("job");

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
