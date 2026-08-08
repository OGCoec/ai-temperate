package com.example.temperate.service.risk.preauth.domain;

import java.time.Instant;

/**
 * 表示 Redis 原子开启 WebRTC 探测窗口的结果，并携带 Redis 时间计算出的剩余毫秒数。
 *
 * <p>只有 STARTED 与 PENDING_PRESERVED 携带截止时间；其他状态要求调用方重新读取 PreAuth
 * 终态或返回稳定的并发错误，避免使用 Java 节点时间推导安全窗口。</p>
 */
public record PreAuthWebRtcBeginResult(
        Status status,
        long generation,
        Instant deadlineAt,
        long remainingMillis) {

    public PreAuthWebRtcBeginResult {
        if (status == null || generation < 0 || remainingMillis < 0) {
            throw new IllegalArgumentException("WebRTC begin result is invalid.");
        }
        boolean pending = status == Status.STARTED
                || status == Status.PENDING_PRESERVED;
        if (pending != (deadlineAt != null) || (pending && generation <= 0)) {
            throw new IllegalArgumentException("WebRTC begin deadline is inconsistent.");
        }
    }

    /**
     * 定义 begin Lua 的稳定结果集合，区分幂等保留、终态保留和绑定竞态。
     */
    public enum Status {
        STARTED,
        PENDING_PRESERVED,
        VERIFIED_PRESERVED,
        FAILURE_PRESERVED,
        START_TIMEOUT,
        REPORT_TIMEOUT,
        NETWORK_CHANGED,
        STALE_GENERATION,
        STATE_INVALID
    }
}
