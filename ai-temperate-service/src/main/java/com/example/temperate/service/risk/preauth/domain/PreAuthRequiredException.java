package com.example.temperate.service.risk.preauth.domain;

/**
 * 表示 PreAuth 在已解析后因并发过期、撤销或绑定变化而无法继续原子更新。
 *
 * <p>该异常不携带原始 Token 或 Redis Key，Web 层应将其映射为重新 Bootstrap 的稳定 428 响应。</p>
 */
public final class PreAuthRequiredException extends RuntimeException {

    public PreAuthRequiredException() {
        super("PreAuth is missing or no longer bound to this request.");
    }
}
