package com.example.temperate.service.user.membership.payment.callback.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.user.membership.payment.MembershipPaymentCallbackMapper;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackWriteResult;
import com.example.temperate.model.user.membership.payment.MembershipPaymentRefundTerminalFact;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackPersistenceService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackResolutionCommand;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackSnapshot;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 该实现是来在单个 PostgreSQL 本地事务中序列化有界回调批次，并调用一次 Mapper 完成插入和唯一冲突解析。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class PaymentCallbackPersistenceServiceImpl
        implements PaymentCallbackPersistenceService {

    private static final int MAXIMUM_BATCH = 500;

    private final MembershipPaymentCallbackMapper callbackMapper;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final ObjectMapper objectMapper;

    public PaymentCallbackPersistenceServiceImpl(
            MembershipPaymentCallbackMapper callbackMapper,
            HybridBase64UrlCodec base64UrlCodec,
            ObjectMapper objectMapper) {
        this.callbackMapper = Objects.requireNonNull(callbackMapper);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /** 数据库唯一键是跨进程最终幂等裁决；同一批次的输出顺序必须与输入 ordinal 完全对应。 */
    @Override
    @Transactional
    public List<MembershipPaymentCallbackWriteResult> persist(
            List<PaymentCallbackSnapshot> callbacks) {
        List<PaymentCallbackSnapshot> values = List.copyOf(
                Objects.requireNonNull(callbacks));
        if (values.isEmpty() || values.size() > MAXIMUM_BATCH) {
            throw new IllegalArgumentException(
                    "Payment callback persistence batch must contain 1 to 500 rows.");
        }
        List<CallbackRow> rows = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            PaymentCallbackSnapshot callback = Objects.requireNonNull(values.get(index));
            rows.add(new CallbackRow(
                    index + 1,
                    HexFormat.of().formatHex(base64UrlCodec.decode(callback.callbackId())),
                    HexFormat.of().formatHex(base64UrlCodec.decode(callback.orderId())),
                    callback.providerTradeNo(),
                    callback.tradeStatus(),
                    callback.paidAmountYuan().toPlainString(),
                    callback.paidAt(),
                    callback.receivedAt()));
        }
        List<MembershipPaymentCallbackWriteResult> results =
                callbackMapper.batchInsertOrResolve(toJson(rows));
        if (results == null || results.size() != values.size()) {
            throw new MembershipPaymentInfrastructureException(
                    "Payment callback persistence result is incomplete.");
        }
        validateResults(values, results);
        return List.copyOf(results);
    }

    /** 已解析记录只允许写入相同结果；影响行数不足表示数据库事实发生冲突，整批事务必须失败并重试。 */
    @Override
    @Transactional
    public void resolve(List<PaymentCallbackResolutionCommand> resolutions) {
        List<PaymentCallbackResolutionCommand> values = List.copyOf(
                Objects.requireNonNull(resolutions));
        if (values.isEmpty()) {
            return;
        }
        if (values.size() > MAXIMUM_BATCH
                || values.stream()
                        .map(PaymentCallbackResolutionCommand::callbackId)
                        .distinct()
                        .count() != values.size()) {
            throw new IllegalArgumentException(
                    "Payment callback resolution batch must contain 1 to 500 unique rows.");
        }
        List<ResolutionRow> rows = values.stream()
                .map(command -> new ResolutionRow(
                        HexFormat.of().formatHex(base64UrlCodec.decode(command.callbackId())),
                        command.resolution().name(),
                        command.resolvedAt()))
                .toList();
        if (callbackMapper.batchResolve(toJson(rows)) != values.size()) {
            throw new MembershipPaymentInfrastructureException(
                    "Payment callback resolution result is incomplete.");
        }
    }

    /**
     * Redis 快照缺失时只能读取已提交的 PostgreSQL 终态；结果必须精确属于本次请求且不得重复。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, MembershipPaymentRefundTerminalFact> findRefundTerminalFacts(
            Collection<String> callbackIds) {
        Objects.requireNonNull(callbackIds, "callback IDs must not be null");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        List<String> idsHex = new ArrayList<>(callbackIds.size());
        for (String callbackId : callbackIds) {
            String canonical = base64UrlCodec.encode(base64UrlCodec.decode(
                    Objects.requireNonNull(callbackId)));
            if (!unique.add(canonical)) {
                throw new IllegalArgumentException(
                        "Refund terminal fact callback IDs must be unique.");
            }
            idsHex.add(HexFormat.of().formatHex(base64UrlCodec.decode(canonical)));
        }
        if (unique.isEmpty()) {
            return Map.of();
        }
        if (unique.size() > MAXIMUM_BATCH) {
            throw new IllegalArgumentException(
                    "Refund terminal fact batch must contain at most 500 callback IDs.");
        }
        List<MembershipPaymentRefundTerminalFact> facts =
                callbackMapper.findRefundTerminalFactsByIdsJson(toJson(idsHex));
        if (facts == null) {
            throw new MembershipPaymentInfrastructureException(
                    "Refund terminal fact result is unavailable.");
        }
        Map<String, MembershipPaymentRefundTerminalFact> byCallbackId = new LinkedHashMap<>();
        for (MembershipPaymentRefundTerminalFact fact : facts) {
            if (fact == null || fact.getCallbackId() == null) {
                throw new MembershipPaymentInfrastructureException(
                        "Refund terminal fact result is malformed.");
            }
            String callbackId = base64UrlCodec.encode(fact.getCallbackId());
            if (!unique.contains(callbackId)
                    || byCallbackId.put(callbackId, fact) != null) {
                throw new MembershipPaymentInfrastructureException(
                        "Refund terminal fact result does not match its request.");
            }
        }
        return Map.copyOf(byCallbackId);
    }

    private void validateResults(
            List<PaymentCallbackSnapshot> callbacks,
            List<MembershipPaymentCallbackWriteResult> results) {
        boolean[] seen = new boolean[callbacks.size()];
        for (MembershipPaymentCallbackWriteResult result : results) {
            if (result == null
                    || result.getOrdinal() == null
                    || result.getOrdinal() < 1
                    || result.getOrdinal() > callbacks.size()
                    || seen[result.getOrdinal() - 1]) {
                throw new MembershipPaymentInfrastructureException(
                        "Payment callback persistence ordinal is invalid.");
            }
            int index = result.getOrdinal() - 1;
            seen[index] = true;
            PaymentCallbackSnapshot callback = callbacks.get(index);
            if (!Arrays.equals(
                            result.getCallbackId(),
                            base64UrlCodec.decode(callback.callbackId()))
                    || !Arrays.equals(
                            result.getRequestedOrderId(),
                            base64UrlCodec.decode(callback.orderId()))
                    || !Objects.equals(
                            result.getProviderTradeNo(), callback.providerTradeNo())
                    || !Objects.equals(result.getTradeStatus(), callback.tradeStatus())
                    || result.getPersistedCallbackId() == null
                    || result.getPersistedOrderId() == null
                    || result.getInserted() == null
                    || result.getDuplicate() == null
                    || result.getSameCallback() == null
                    || result.getOrderMismatch() == null) {
                throw new MembershipPaymentInfrastructureException(
                        "Payment callback persistence result does not match its input.");
            }
        }
    }

    private String toJson(List<CallbackRow> rows) {
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (JsonProcessingException exception) {
            throw new MembershipPaymentInfrastructureException(
                    "Payment callback persistence serialization failed.", exception);
        }
    }

    private String toJson(Object rows) {
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (JsonProcessingException exception) {
            throw new MembershipPaymentInfrastructureException(
                    "Payment callback persistence serialization failed.", exception);
        }
    }

    /** 该载荷只承载 Mapper 所需最小字段，禁止加入签名、buyer 或完整原始请求。 */
    private record CallbackRow(
            int ordinal,
            String idHex,
            String orderIdHex,
            String providerTradeNo,
            String tradeStatus,
            String paidAmountYuan,
            OffsetDateTime paidAt,
            OffsetDateTime receivedAt) {
    }

    /** 该载荷只承载回调最终裁决，禁止加入订单、用户或第三方敏感字段。 */
    private record ResolutionRow(
            String callbackIdHex,
            String resolution,
            OffsetDateTime resolvedAt) {
    }
}
