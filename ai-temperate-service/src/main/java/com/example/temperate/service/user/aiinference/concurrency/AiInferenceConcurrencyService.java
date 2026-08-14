package com.example.temperate.service.user.aiinference.concurrency;

/**
 * 该服务是来让 H5、Android 与所有 API Key 共享账号和全局并发池，并为公开 API 额外增加每 Key 三流限制。
 */
public interface AiInferenceConcurrencyService {

    AcquireResult tryAcquireAccount(long loginIdentityId, short weight);

    AcquireResult tryAcquireApiKey(
            long loginIdentityId,
            String apiKeyDigestIdentifier,
            short weight);

    boolean renew(AiInferenceConcurrencyPermit permit);

    void release(AiInferenceConcurrencyPermit permit);

    enum Result {
        ACQUIRED,
        API_KEY_LIMIT_EXCEEDED,
        ACCOUNT_LIMIT_EXCEEDED,
        GLOBAL_LIMIT_EXCEEDED,
        INFRASTRUCTURE_UNAVAILABLE
    }

    /** 非 ACQUIRED 结果不携带租约，调用方不得尝试续租或释放。 */
    record AcquireResult(Result result, AiInferenceConcurrencyPermit permit) {

        public AcquireResult {
            if ((result == Result.ACQUIRED) != (permit != null)) {
                throw new IllegalArgumentException("AI inference acquire result is inconsistent");
            }
        }

        public static AcquireResult rejected(Result result) {
            return new AcquireResult(result, null);
        }
    }
}
