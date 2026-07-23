package com.example.temperate.service.registration.verification.delivery.exception;

/**
 * 表示验证码 Provider 请求最终可确认的结果边界，供消费者决定是否允许再次创建外部消息。
 *
 * <p>UNKNOWN 不代表 Provider 一定失败，而是客户端无法确认请求是否已经被 Provider 接收；
 * 这个状态禁止直接进入发送重试队列，以避免一次已成功的外部请求被重复创建。
 */
public enum VerificationDeliveryOutcome {
    /** Provider 返回合法资源标识，表示创建请求已被接受。 */
    ACCEPTED,

    /** Provider 明确返回了可以分类的失败结果。 */
    EXPLICIT_FAILURE,

    /** 请求结果无法确认，不能安全地再次创建外部消息。 */
    UNKNOWN
}
