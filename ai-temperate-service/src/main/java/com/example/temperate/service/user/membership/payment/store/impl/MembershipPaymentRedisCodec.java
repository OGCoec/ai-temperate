package com.example.temperate.service.user.membership.payment.store.impl;

import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteOutcome;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteResult;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderResult;
import com.example.temperate.service.user.membership.payment.provider.SimulatedPaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 该编解码器是来在固定 Redis Hash 字段与会员支付快照之间转换，并集中校验版本、金额和时间边界。
 *
 * <p>空可选值统一编码为空字符串，避免不同调用点产生字段集合漂移；解析失败必须视为基础设施数据损坏。</p>
 */
final class MembershipPaymentRedisCodec {

    private static final List<String> ORDER_FIELDS = List.of(
            "schemaVersion",
            "orderId",
            "loginIdentityId",
            "membershipTier",
            "payAmountYuan",
            "payType",
            "status",
            "idempotencyKey",
            "providerTradeNo",
            "paymentStartedAt",
            "expiresAt",
            "closingDeadlineAt",
            "paidAt",
            "stateVersion",
            "createdAt",
            "updatedAt");
    private MembershipPaymentRedisCodec() {
    }

    static Map<String, String> writeOrder(MembershipOrderSnapshot snapshot) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", Integer.toString(snapshot.schemaVersion()));
        fields.put("orderId", snapshot.orderId());
        fields.put("loginIdentityId", Long.toString(snapshot.loginIdentityId()));
        fields.put("membershipTier", snapshot.membershipTier().name());
        fields.put("payAmountYuan", snapshot.payAmountYuan().toPlainString());
        fields.put("payType", snapshot.payType());
        fields.put("status", snapshot.status().name());
        fields.put("idempotencyKey", snapshot.idempotencyKey().toString());
        fields.put("providerTradeNo", text(snapshot.providerTradeNo()));
        fields.put("paymentStartedAt", epoch(snapshot.paymentStartedAt()));
        fields.put("expiresAt", epoch(snapshot.expiresAt()));
        fields.put("closingDeadlineAt", epoch(snapshot.closingDeadlineAt()));
        fields.put("paidAt", epoch(snapshot.paidAt()));
        fields.put("stateVersion", Long.toString(snapshot.stateVersion()));
        fields.put("createdAt", epoch(snapshot.createdAt()));
        fields.put("updatedAt", epoch(snapshot.updatedAt()));
        return Map.copyOf(fields);
    }

    static MembershipOrderSnapshot readOrder(Map<String, String> fields) {
        return new MembershipOrderSnapshot(
                integer(fields, "schemaVersion"),
                required(fields, "orderId"),
                number(fields, "loginIdentityId"),
                MembershipTier.valueOf(required(fields, "membershipTier")),
                decimal(fields, "payAmountYuan"),
                required(fields, "payType"),
                MembershipOrderStatus.valueOf(required(fields, "status")),
                UUID.fromString(required(fields, "idempotencyKey")),
                optional(fields, "providerTradeNo"),
                time(fields, "paymentStartedAt", false),
                time(fields, "expiresAt", true),
                time(fields, "closingDeadlineAt", false),
                time(fields, "paidAt", false),
                number(fields, "stateVersion"),
                time(fields, "createdAt", true),
                time(fields, "updatedAt", true));
    }

    /**
     * 解析协调器混合写入结果；正常写入直接复用已提交数据库快照，只有冲突或陈旧裁决读取 Redis 当前 16 字段。
     */
    static MembershipOrderSnapshotWriteResult readOrderWriteReply(
            List<?> reply,
            MembershipOrderSnapshot submittedSnapshot) {
        if (reply == null || reply.isEmpty()) {
            throw new IllegalArgumentException(
                    "Membership order write script reply is empty.");
        }
        Objects.requireNonNull(submittedSnapshot, "submittedSnapshot must not be null");
        MembershipOrderSnapshotWriteOutcome outcome =
                MembershipOrderSnapshotWriteOutcome.valueOf(scriptText(reply.get(0)));
        if (outcome == MembershipOrderSnapshotWriteOutcome.MISSING
                || outcome == MembershipOrderSnapshotWriteOutcome.REQUIRES_RESTORE) {
            if (reply.size() != 1) {
                throw new IllegalArgumentException(
                        "Snapshotless membership order write reply contains unexpected fields.");
            }
            return new MembershipOrderSnapshotWriteResult(outcome, null);
        }
        if (reply.size() == 1) {
            if (outcome == MembershipOrderSnapshotWriteOutcome.CREATED
                    || outcome == MembershipOrderSnapshotWriteOutcome.REPLACED
                    || outcome == MembershipOrderSnapshotWriteOutcome.APPLIED
                    || outcome == MembershipOrderSnapshotWriteOutcome.UNCHANGED) {
                return new MembershipOrderSnapshotWriteResult(outcome, submittedSnapshot);
            }
            throw new IllegalArgumentException(
                    "Membership order write conflict reply is missing current fields.");
        }
        if (reply.size() != ORDER_FIELDS.size() + 1) {
            throw new IllegalArgumentException(
                    "Membership order write script reply has an invalid size.");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 0; index < ORDER_FIELDS.size(); index++) {
            fields.put(ORDER_FIELDS.get(index), scriptText(reply.get(index + 1)));
        }
        return new MembershipOrderSnapshotWriteResult(outcome, readOrder(fields));
    }

    static Map<String, String> writeCallback(PaymentCallbackSnapshot snapshot) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", Integer.toString(snapshot.schemaVersion()));
        fields.put("callbackId", snapshot.callbackId());
        fields.put("orderId", snapshot.orderId());
        fields.put("pid", snapshot.pid());
        fields.put("providerTradeNo", snapshot.providerTradeNo());
        fields.put("channelTradeNo", snapshot.channelTradeNo());
        fields.put("payType", snapshot.payType());
        fields.put("tradeStatus", snapshot.tradeStatus());
        fields.put("paidAmountYuan", snapshot.paidAmountYuan().toPlainString());
        fields.put("paidAt", epoch(snapshot.paidAt()));
        fields.put("receivedAt", epoch(snapshot.receivedAt()));
        fields.put(
                "requestTimestampEpochSeconds",
                Long.toString(snapshot.requestTimestampEpochSeconds()));
        fields.put("idempotencyFingerprint", snapshot.idempotencyFingerprint());
        fields.put("payloadDigest", snapshot.payloadDigest());
        return Map.copyOf(fields);
    }

    static PaymentCallbackSnapshot readCallback(Map<String, String> fields) {
        return new PaymentCallbackSnapshot(
                integer(fields, "schemaVersion"),
                required(fields, "callbackId"),
                required(fields, "orderId"),
                required(fields, "pid"),
                required(fields, "providerTradeNo"),
                required(fields, "channelTradeNo"),
                required(fields, "payType"),
                required(fields, "tradeStatus"),
                decimal(fields, "paidAmountYuan"),
                time(fields, "paidAt", true),
                time(fields, "receivedAt", true),
                number(fields, "requestTimestampEpochSeconds"),
                required(fields, "idempotencyFingerprint"),
                required(fields, "payloadDigest"));
    }

    static Map<String, String> writeProvider(SimulatedPaymentProviderResult result) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", Integer.toString(result.schemaVersion()));
        fields.put("orderId", result.orderId());
        fields.put("status", result.status().name());
        fields.put("callbackId", text(result.callbackId()));
        fields.put("providerTradeNo", text(result.providerTradeNo()));
        fields.put("payType", text(result.payType()));
        fields.put(
                "paidAmountYuan",
                result.paidAmountYuan() == null
                        ? ""
                        : result.paidAmountYuan().toPlainString());
        fields.put("updatedAt", epoch(result.updatedAt()));
        return Map.copyOf(fields);
    }

    static SimulatedPaymentProviderResult readProvider(Map<String, String> fields) {
        String amount = optional(fields, "paidAmountYuan");
        return new SimulatedPaymentProviderResult(
                integer(fields, "schemaVersion"),
                required(fields, "orderId"),
                SimulatedPaymentProviderStatus.valueOf(required(fields, "status")),
                optional(fields, "callbackId"),
                optional(fields, "providerTradeNo"),
                optional(fields, "payType"),
                amount == null ? null : new BigDecimal(amount),
                time(fields, "updatedAt", true));
    }

    static Map<String, String> stringMap(Map<?, ?> raw) {
        Map<String, String> values = new LinkedHashMap<>();
        raw.forEach((key, value) -> values.put(string(key), string(value)));
        return Map.copyOf(values);
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing Redis payment field: " + name);
        }
        return value;
    }

    private static String optional(Map<String, String> fields, String name) {
        String value = fields.get(name);
        return value == null || value.isEmpty() ? null : value;
    }

    private static int integer(Map<String, String> fields, String name) {
        return Math.toIntExact(number(fields, name));
    }

    private static long number(Map<String, String> fields, String name) {
        try {
            return Long.parseLong(required(fields, name));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid Redis payment number: " + name, exception);
        }
    }

    private static BigDecimal decimal(Map<String, String> fields, String name) {
        try {
            return new BigDecimal(required(fields, name));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid Redis payment decimal: " + name, exception);
        }
    }

    private static OffsetDateTime time(
            Map<String, String> fields,
            String name,
            boolean required) {
        String value = optional(fields, name);
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException("Missing Redis payment time: " + name);
            }
            return null;
        }
        try {
            return MembershipPaymentTime.fromEpochMicros(Long.parseLong(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid Redis payment time: " + name, exception);
        }
    }

    private static String epoch(OffsetDateTime value) {
        return value == null ? "" : Long.toString(MembershipPaymentTime.toEpochMicros(value));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String scriptText(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException(
                "Membership payment Redis script reply contains a missing or invalid field.");
    }

    private static String string(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    /**
     * 该结果是来同时携带 Lua 版本裁决和裁决后的当前订单快照，调用方只向业务层暴露快照。
     */
}
