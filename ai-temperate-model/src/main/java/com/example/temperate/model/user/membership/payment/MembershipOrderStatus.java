package com.example.temperate.model.user.membership.payment;

import java.util.Arrays;

/**
 * 该枚举是来统一表达会员支付订单持久化与 Redis 状态机共享的五种状态及稳定数据库编码。
 *
 * <p>枚举只定义状态身份和终态属性，允许迁移关系由支付业务状态机负责，避免调用方绕过并发校验。</p>
 */
public enum MembershipOrderStatus {

    PENDING_PAYMENT(0, false),
    CLOSING(1, false),
    PAID(2, true),
    CANCELLED(3, true),
    CLOSED(4, true);

    private final int code;
    private final boolean terminal;

    MembershipOrderStatus(int code, boolean terminal) {
        this.code = code;
        this.terminal = terminal;
    }

    public int code() {
        return code;
    }

    public boolean terminal() {
        return terminal;
    }

    /** 将数据库或 Redis 的稳定数字编码转换为受控状态，未知编码必须显式失败。 */
    public static MembershipOrderStatus fromCode(int code) {
        return Arrays.stream(values())
                .filter(status -> status.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported membership order status code: " + code));
    }
}
