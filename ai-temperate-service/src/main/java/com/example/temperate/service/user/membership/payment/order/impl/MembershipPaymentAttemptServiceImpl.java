package com.example.temperate.service.user.membership.payment.order.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.callback.PaymentFactReconciliationService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.exception.MembershipPaymentInfrastructureException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentLifecycleDiagnostics;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentTraceContext;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderRealtimeGuard;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptDatabaseResult;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptResult;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptService;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptTransactionService;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProvider;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutMode;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutSubmission;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCreateCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCreateResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderReference;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCoordinator;
import com.example.temperate.service.user.membership.payment.store.MembershipProviderTradeNoPatchOutcome;
import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来先提交 PostgreSQL 支付发起事实，再以单调版本刷新 Redis，并将并发产生的更新后快照返回客户端。
 *
 * <p>事务提交后才调用请求明确选择的外部 Provider；数据库与 Redis 都绑定真实交易号后，才生成浏览器提交描述。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentAttemptServiceImpl
        implements MembershipPaymentAttemptService {

    private final MembershipPaymentAttemptTransactionService transactionService;
    private final MembershipOrderSnapshotStore snapshotStore;
    private final MembershipOrderSnapshotWriteCoordinator snapshotWriteCoordinator;
    private final MembershipPaymentProviderRegistry providerRegistry;
    private final PaymentFactReconciliationService reconciliationService;
    private final MembershipPaymentProperties properties;
    private final HybridBase64UrlCodec base64UrlCodec;
    private final Clock clock;

    public MembershipPaymentAttemptServiceImpl(
            MembershipPaymentAttemptTransactionService transactionService,
            MembershipOrderSnapshotStore snapshotStore,
            MembershipOrderSnapshotWriteCoordinator snapshotWriteCoordinator,
            MembershipPaymentProviderRegistry providerRegistry,
            PaymentFactReconciliationService reconciliationService,
            MembershipPaymentProperties properties,
            HybridBase64UrlCodec base64UrlCodec,
            Clock clock) {
        this.transactionService = Objects.requireNonNull(transactionService);
        this.snapshotStore = Objects.requireNonNull(snapshotStore);
        this.snapshotWriteCoordinator = Objects.requireNonNull(snapshotWriteCoordinator);
        this.providerRegistry = Objects.requireNonNull(providerRegistry);
        this.reconciliationService = Objects.requireNonNull(reconciliationService);
        this.properties = Objects.requireNonNull(properties);
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 数据库事务返回即代表发起事实已提交；Redis 只接受更高版本，不能用旧快照覆盖并发回调状态。 */
    @Override
    public MembershipPaymentAttemptResult start(
            long loginIdentityId,
            byte[] orderId,
            PaymentProviderType requestedProvider,
            String canonicalClientIp) {
        if (!properties.checkoutEnabled()) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.PAYMENT_CHECKOUT_DISABLED,
                    "Membership payment checkout is temporarily disabled.");
        }
        if (loginIdentityId <= 0L || orderId == null) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.INPUT_INVALID,
                    "The current login identity or membership order is invalid.");
        }
        PaymentProviderType providerType = requirePublicProvider(requestedProvider);
        OffsetDateTime attemptedAt = MembershipPaymentTime.now(clock);
        String publicOrderId = base64UrlCodec.encode(orderId);
        try {
            // 取消和回调先在 Redis 形成实时状态；终态已明确时必须在 PostgreSQL 条件 UPDATE 前拒绝，
            // 否则数据库会产生与 Redis 终态相同版本的 PENDING 事实，后续检查消息可能把订单复活。
            snapshotStore.findRealtimeGuard(publicOrderId).ifPresent(snapshot ->
                    requirePaymentStartAllowed(snapshot, loginIdentityId, attemptedAt));
        } catch (MembershipPaymentInfrastructureException ignored) {
            // Redis 暂时不可读时仍让 PostgreSQL 作最终归属和状态裁决；提交后的缓存刷新会返回受控 503。
        }
        MembershipPaymentAttemptDatabaseResult databaseResult = transactionService.startOrGet(
                loginIdentityId,
                orderId.clone(),
                attemptedAt);
        MembershipOrderSnapshot databaseSnapshot = toSnapshot(databaseResult.order());
        try {
            // 数据库事实提交后用单条 Lua 写入并返回实时快照，移除原 put 后的第二次 Redis 网络往返。
            MembershipOrderSnapshot current =
                    snapshotWriteCoordinator.patchPaymentAttempt(databaseSnapshot);
            // 回调状态先在 Redis 原子迁移、再异步批量入库；数据库短暂仍为 PENDING 时，必须以更高版本的
            // Redis 终态拒绝重放，不能把已 PAID/CANCELLED/CLOSED 的订单误报为可继续发起支付。
            requirePaymentStartAllowed(current, loginIdentityId, attemptedAt);
            requirePaymentPatchConsistent(databaseSnapshot, current);
            MembershipPaymentProvider provider = providerRegistry.getRequired(providerType);
            if (!databaseResult.started()) {
                PaymentProviderType boundProvider = safeBoundProvider(current.providerTradeNo());
                if (boundProvider != null && boundProvider != providerType) {
                    throw new MembershipPaymentException(
                            MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT,
                            "The membership order is already bound to another payment provider.");
                }
                if (providerType == PaymentProviderType.LIUHAO) {
                    // 首发结果可能已经在六号成功落单；重放只允许只读查询并恢复同一二维码入口，绝不能再次提交。
                    return recoverLiuhaoPayment(
                            loginIdentityId,
                            orderId,
                            databaseResult,
                            current,
                            provider,
                            canonicalClientIp);
                }
                // 支付入口不持久化；不支持恢复的 Provider 保持不确定态，禁止重放创建第二笔外部订单。
                throw new MembershipPaymentException(
                        MembershipPaymentErrorCode.PAYMENT_CREATE_OUTCOME_UNKNOWN,
                        "The existing external payment result is still being confirmed.");
            }
            if (current.providerTradeNo() != null) {
                throw new MembershipPaymentException(
                        MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT,
                        "The membership order acquired a provider trade before checkout creation.");
            }
            if (providerType == PaymentProviderType.LIUHAO) {
                return startLiuhaoPayment(
                        loginIdentityId,
                        orderId,
                        databaseResult,
                        current,
                        provider,
                        canonicalClientIp);
            }
            PaymentCheckoutResult checkout = provider.createCheckout(
                    new PaymentCheckoutCommand(
                            current.orderId(),
                            current.payAmountYuan(),
                            current.payType(),
                            "会员模拟支付订单"));
            if (checkout == null
                    || !checkout.created()
                    || checkout.providerTradeNo() == null) {
                MembershipPaymentLifecycleDiagnostics.externalPaymentCreateValidation(
                        PaymentProviderType.BAR,
                        current.payType(),
                        checkout != null && checkout.providerTradeNo() != null,
                        "form_post",
                        checkout != null && checkout.checkoutSubmission() != null,
                        checkout != null && checkout.checkoutSubmission() != null
                                ? "signed_form"
                                : "missing",
                        false,
                        false,
                        "rejected",
                        "RESPONSE_INCOMPLETE",
                        MembershipPaymentTraceContext.currentTraceId());
                throw new MembershipPaymentException(
                        MembershipPaymentErrorCode.BAR_RESPONSE_INVALID,
                        "BAR checkout did not contain a real provider trade reference.");
            }
            String tradeReference = taggedTrade(providerType, checkout.providerTradeNo());
            try {
                current = bindProviderReference(
                        loginIdentityId,
                        orderId,
                        current,
                        providerType,
                        tradeReference,
                        "create_response");
            } catch (RuntimeException exception) {
                // 外部订单已经创建但真实流水未能完成本地绑定时，必须尽力关闭本次新流水；
                // payment_started_at 仍保留，后续重放不会再次创建第二笔外部订单。
                closeUnboundProviderTrade(provider, current.orderId(), tradeReference);
                throw exception;
            }
            if (checkout.checkoutSubmission() == null) {
                // BAR 已经返回真实流水时必须先保存交易事实；缺失浏览器入口只能受控失败，不能重建第三方订单。
                MembershipPaymentLifecycleDiagnostics.externalPaymentCreateValidation(
                        PaymentProviderType.BAR,
                        current.payType(),
                        true,
                        "form_post",
                        false,
                        "missing",
                        false,
                        false,
                        "rejected",
                        "CHECKOUT_SUBMISSION_MISSING",
                        MembershipPaymentTraceContext.currentTraceId());
                throw new MembershipPaymentException(
                        MembershipPaymentErrorCode.BAR_RESPONSE_INVALID,
                        "BAR created the order but did not return a browser submission.");
            }
            MembershipPaymentLifecycleDiagnostics.externalPaymentCreateValidation(
                    PaymentProviderType.BAR,
                    current.payType(),
                    true,
                    "form_post",
                    true,
                    "signed_form",
                    true,
                    true,
                    "accepted",
                    "VALIDATED",
                    MembershipPaymentTraceContext.currentTraceId());

            // Provider 调用后必须从 Redis 精简 Guard 重新核验；此处失败不能用调用前快照冒充实时状态。
            MembershipOrderRealtimeGuard afterCheckout = snapshotStore
                    .findRealtimeGuard(current.orderId())
                    .orElseThrow(() -> new MembershipPaymentInfrastructureException(
                            "Redis membership order realtime guard is missing after provider checkout."));
            OffsetDateTime revalidatedAt = MembershipPaymentTime.now(clock);
            if (afterCheckout.loginIdentityId() != loginIdentityId
                    || afterCheckout.status() != MembershipOrderStatus.PENDING_PAYMENT
                    || !revalidatedAt.isBefore(afterCheckout.expiresAt())) {
                PaymentCloseResult close = provider.closePayment(new PaymentCloseCommand(
                        afterCheckout.orderId(), tradeReference));
                if (close.status() == PaymentProviderStatus.PAID) {
                    reconciliationService.reconcilePaid(
                            current,
                            provider.queryPayment(new PaymentQueryCommand(
                                    afterCheckout.orderId(), tradeReference)));
                }
                throw new MembershipPaymentException(
                        MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT,
                        "The membership order no longer allows payment to start.");
            }
            PaymentCheckoutSubmission browserSubmission = boundToOrderExpiry(
                    checkout.checkoutSubmission(), afterCheckout.expiresAt());
            return new MembershipPaymentAttemptResult(
                    current,
                    databaseResult.started(),
                    providerType,
                    browserSubmission);
        } catch (MembershipPaymentInfrastructureException exception) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_REDIS_UNAVAILABLE,
                    "Membership payment state is temporarily unavailable.",
                    exception);
        }
    }

    /** 六号必须由后端完成支付宝统一下单或微信提交后查询确认；浏览器只接收已绑定真实流水的 HTTPS 收银台地址。 */
    private MembershipPaymentAttemptResult startLiuhaoPayment(
            long loginIdentityId,
            byte[] internalOrderId,
            MembershipPaymentAttemptDatabaseResult databaseResult,
            MembershipOrderSnapshot current,
            MembershipPaymentProvider provider,
            String canonicalClientIp) {
        PaymentCreateResult created;
        try {
            created = provider.createPayment(new PaymentCreateCommand(
                    current.orderId(),
                    current.payAmountYuan(),
                    current.payType(),
                    "会员支付订单",
                    canonicalClientIp));
        } catch (MembershipPaymentException exception) {
            if (exception.providerTradeNo() != null) {
                String taggedTrade = taggedTrade(
                        PaymentProviderType.LIUHAO, exception.providerTradeNo());
                bindProviderReference(
                        loginIdentityId,
                        internalOrderId,
                        current,
                        PaymentProviderType.LIUHAO,
                        taggedTrade,
                        "rejected_create_response");
            }
            throw exception;
        }
        return completeLiuhaoPayment(
                loginIdentityId,
                internalOrderId,
                databaseResult,
                current,
                provider,
                created,
                true);
    }

    /** 该恢复流程是来以同一本地订单号查询六号现有交易，并在不重新下单的前提下恢复二维码入口。 */
    private MembershipPaymentAttemptResult recoverLiuhaoPayment(
            long loginIdentityId,
            byte[] internalOrderId,
            MembershipPaymentAttemptDatabaseResult databaseResult,
            MembershipOrderSnapshot current,
            MembershipPaymentProvider provider,
            String canonicalClientIp) {
        PaymentCreateResult recovered;
        try {
            recovered = provider.recoverPayment(new PaymentCreateCommand(
                    current.orderId(),
                    current.payAmountYuan(),
                    current.payType(),
                    "会员支付订单",
                    canonicalClientIp),
                    current.providerTradeNo());
        } catch (MembershipPaymentException exception) {
            if (exception.providerTradeNo() != null) {
                bindProviderReference(
                        loginIdentityId,
                        internalOrderId,
                        current,
                        PaymentProviderType.LIUHAO,
                        taggedTrade(PaymentProviderType.LIUHAO, exception.providerTradeNo()),
                        "recovery_response");
            }
            throw exception;
        }
        if (recovered == null) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.PAYMENT_CREATE_OUTCOME_UNKNOWN,
                    "The existing Liuhao payment result is still being confirmed.");
        }
        return completeLiuhaoPayment(
                loginIdentityId,
                internalOrderId,
                databaseResult,
                current,
                provider,
                recovered,
                false);
    }

    /**
     * 该完成步骤是来统一首发与恢复的交易绑定、实时状态复核和浏览器跳转描述；首发绑定失败才允许尝试关单。
     */
    private MembershipPaymentAttemptResult completeLiuhaoPayment(
            long loginIdentityId,
            byte[] internalOrderId,
            MembershipPaymentAttemptDatabaseResult databaseResult,
            MembershipOrderSnapshot current,
            MembershipPaymentProvider provider,
            PaymentCreateResult created,
            boolean closeOnBindingFailure) {
        if (created == null
                || !created.created()
                || !PaymentProviderReference.isTrade(
                        PaymentProviderType.LIUHAO, created.providerTradeNo())) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.LIUHAO_RESPONSE_INVALID,
                    "Liuhao create response did not contain a complete real trade.");
        }
        try {
            current = bindProviderReference(
                    loginIdentityId,
                    internalOrderId,
                    current,
                    PaymentProviderType.LIUHAO,
                    created.providerTradeNo(),
                    closeOnBindingFailure ? "create_response" : "recovery_response");
        } catch (RuntimeException exception) {
            if (closeOnBindingFailure) {
                closeUnboundProviderTrade(provider, current.orderId(), created.providerTradeNo());
            }
            throw exception;
        }
        if (!("jump".equals(created.providerPayType())
                || "qrcode".equals(created.providerPayType()))) {
            // 第二道载体校验失败时真实流水已经单调绑定，后续只能查询或关单，禁止再次创建。
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.LIUHAO_CHECKOUT_UNAVAILABLE,
                    "Liuhao created the order but returned an unsupported payment carrier.");
        }
        URI action = requireLiuhaoRedirect(current.payType(), created);

        MembershipOrderRealtimeGuard guard = snapshotStore.findRealtimeGuard(current.orderId())
                .orElse(null);
        if (guard == null) {
            throw new MembershipPaymentInfrastructureException(
                    "Redis membership order realtime guard is missing after Liuhao create.");
        }
        if (snapshotStore.callbackInProgress(current.orderId())) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_CALLBACK_IN_PROGRESS,
                    "Membership payment callback is already being processed.");
        }
        OffsetDateTime revalidatedAt = MembershipPaymentTime.now(clock);
        if (guard.loginIdentityId() != loginIdentityId
                || guard.status() != MembershipOrderStatus.PENDING_PAYMENT
                || !revalidatedAt.isBefore(guard.expiresAt())) {
            provider.closePayment(new PaymentCloseCommand(
                    current.orderId(), created.providerTradeNo()));
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT,
                    "The membership order no longer allows payment to start.");
        }

        PaymentCheckoutSubmission browserSubmission = new PaymentCheckoutSubmission(
                PaymentProviderType.LIUHAO,
                PaymentCheckoutMode.REDIRECT_URL,
                action,
                "GET",
                null,
                guard.expiresAt(),
                null);
        return new MembershipPaymentAttemptResult(
                current,
                databaseResult.started(),
                PaymentProviderType.LIUHAO,
                browserSubmission);
    }

    private MembershipOrderSnapshot bindProviderReference(
            long loginIdentityId,
            byte[] internalOrderId,
            MembershipOrderSnapshot current,
            PaymentProviderType provider,
            String tradeReference,
            String source) {
        String databaseBind = Objects.equals(current.providerTradeNo(), tradeReference)
                ? "unchanged"
                : "applied";
        MembershipOrder bound;
        try {
            bound = transactionService.bindProviderTradeNo(
                    loginIdentityId, internalOrderId.clone(), tradeReference);
        } catch (RuntimeException exception) {
            recordReferenceBindFailure(current, provider, source, "DATABASE_BIND_FAILED",
                    "failed", "not_attempted");
            throw exception;
        }

        String redisBind;
        MembershipOrderSnapshot converged;
        try {
            MembershipProviderTradeNoPatchOutcome patchOutcome =
                    snapshotStore.patchProviderTradeNo(
                            current.orderId(), loginIdentityId, tradeReference);
            if (patchOutcome == MembershipProviderTradeNoPatchOutcome.MISSING) {
                // Key 丢失时只用数据库最新事实恢复完整快照；若并发终态已先写入，单调版本仍会阻止回退。
                converged = snapshotWriteCoordinator.putAndGet(toSnapshot(bound));
                redisBind = "applied";
            } else if (patchOutcome == MembershipProviderTradeNoPatchOutcome.APPLIED) {
                converged = withProviderTradeNo(current, tradeReference);
                redisBind = "applied";
            } else if (patchOutcome == MembershipProviderTradeNoPatchOutcome.UNCHANGED) {
                converged = withProviderTradeNo(current, tradeReference);
                redisBind = "unchanged";
            } else {
                recordReferenceBindFailure(current, provider, source, "REDIS_BIND_CONFLICT",
                        databaseBind, "conflict");
                throw new MembershipPaymentException(
                        MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT,
                        "The provider trade reference conflicts with realtime state.");
            }
        } catch (MembershipPaymentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            recordReferenceBindFailure(current, provider, source, "REDIS_BIND_FAILED",
                    databaseBind, "failed");
            throw exception;
        }
        if (!Objects.equals(tradeReference, converged.providerTradeNo())) {
            recordReferenceBindFailure(current, provider, source, "REDIS_BIND_CONFLICT",
                    databaseBind, "conflict");
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT,
                    "The provider trade reference did not converge in realtime state.");
        }
        MembershipPaymentLifecycleDiagnostics.referenceBound(
                converged,
                provider,
                source,
                databaseBind,
                redisBind,
                MembershipPaymentTime.now(clock),
                MembershipPaymentTraceContext.currentTraceId(),
                "unavailable");
        return converged;
    }

    private static void recordReferenceBindFailure(
            MembershipOrderSnapshot order,
            PaymentProviderType provider,
            String source,
            String reason,
            String databaseBind,
            String redisBind) {
        MembershipPaymentLifecycleDiagnostics.referenceResolution(
                order,
                provider,
                source,
                "reference_bind",
                "failed",
                PaymentProviderStatus.UNKNOWN,
                true,
                databaseBind,
                redisBind,
                "retry_query",
                reason,
                MembershipPaymentTraceContext.currentTraceId(),
                "unavailable");
    }

    private static void closeUnboundProviderTrade(
            MembershipPaymentProvider provider,
            String orderId,
            String tradeReference) {
        try {
            provider.closePayment(new PaymentCloseCommand(orderId, tradeReference));
        } catch (RuntimeException ignored) {
            // 关单失败不能覆盖真实流水绑定失败这一主异常；started + trade null 会由全 Provider 发现流程继续收敛。
        }
    }

    private PaymentProviderType requirePublicProvider(PaymentProviderType requested) {
        if (requested == null
                || requested == PaymentProviderType.LOCAL_SIMULATOR
                || !properties.publicProviders().contains(requested)) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.PAYMENT_PROVIDER_UNSUPPORTED,
                    "The requested payment provider is not public.");
        }
        boolean enabled = switch (requested) {
            case BAR -> properties.bar().enabled();
            case LIUHAO -> properties.liuhao().enabled();
            case LOCAL_SIMULATOR -> false;
        };
        if (!enabled) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.PAYMENT_PROVIDER_UNSUPPORTED,
                    "The requested payment provider is unavailable.");
        }
        providerRegistry.getRequired(requested);
        return requested;
    }

    private static PaymentProviderType safeBoundProvider(String reference) {
        try {
            return PaymentProviderReference.tryResolveTrade(reference);
        } catch (IllegalArgumentException exception) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT,
                    "The stored provider trade reference is invalid.",
                    exception);
        }
    }

    private static String taggedTrade(
            PaymentProviderType provider,
            String providerTradeNo) {
        if (providerTradeNo != null
                && (providerTradeNo.startsWith("BAR:TRADE:")
                        || providerTradeNo.startsWith("LIUHAO:TRADE:"))) {
            PaymentProviderType existing = PaymentProviderReference.resolveTrade(providerTradeNo);
            if (existing != provider) {
                throw new MembershipPaymentException(
                        MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT,
                        "Provider trade belongs to another payment provider.");
            }
            return providerTradeNo;
        }
        try {
            return PaymentProviderReference.trade(provider, providerTradeNo);
        } catch (IllegalArgumentException exception) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_PAYMENT_PROVIDER_TRADE_CONFLICT,
                    "Provider trade reference is invalid.",
                    exception);
        }
    }

    private static URI requireSafeRedirect(String payInfo) {
        try {
            URI action = URI.create(payInfo);
            if (!action.isAbsolute()
                    || !"https".equalsIgnoreCase(action.getScheme())
                    || action.getHost() == null
                    || action.getHost().isBlank()
                    || action.getUserInfo() != null
                    || payInfo.length() > 4096
                    || payInfo.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Unsafe payment redirect URI.");
            }
            return action;
        } catch (RuntimeException exception) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.LIUHAO_CHECKOUT_UNAVAILABLE,
                    "Liuhao checkout URL cannot be opened safely.",
                    exception);
        }
    }

    /**
     * 六号支付入口必须按用户实际选择的渠道分流：微信只允许同源 QR 页面，支付宝继续接受已验签的 HTTPS jump 地址。
     * 两条规则不能互相复用，否则支付宝官方收银台会被微信路径约束误判为不安全入口。
     */
    private URI requireLiuhaoRedirect(
            String requestedPayType,
            PaymentCreateResult created) {
        if ("wxpay".equals(requestedPayType)) {
            return requireLiuhaoQrcodeRedirect(created);
        }
        if ("alipay".equals(requestedPayType)) {
            if (!"jump".equals(created.providerPayType())) {
                throw new MembershipPaymentException(
                        MembershipPaymentErrorCode.LIUHAO_CHECKOUT_UNAVAILABLE,
                        "Liuhao alipay checkout did not return a jump carrier.");
            }
            return requireSafeRedirect(created.payInfo());
        }
        throw new MembershipPaymentException(
                MembershipPaymentErrorCode.LIUHAO_CHECKOUT_UNAVAILABLE,
                "Liuhao checkout channel is unsupported.");
    }

    /**
     * 六号微信只能把已经绑定的真实流水映射到同源 QR 页面；服务层再次校验可防止其他实现误把 JSPay 或外域地址返回浏览器。
     */
    private URI requireLiuhaoQrcodeRedirect(PaymentCreateResult created) {
        URI action = requireSafeRedirect(created.payInfo());
        String rawTradeNo;
        try {
            rawTradeNo = PaymentProviderReference.rawTradeNo(created.providerTradeNo());
        } catch (IllegalArgumentException exception) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.LIUHAO_CHECKOUT_UNAVAILABLE,
                    "Liuhao QR checkout trade reference is invalid.",
                    exception);
        }
        URI baseUrl = properties.liuhao().baseUrl();
        if (baseUrl == null
                || !"https".equalsIgnoreCase(baseUrl.getScheme())
                || baseUrl.getHost() == null
                || !baseUrl.getHost().equalsIgnoreCase(action.getHost())
                || action.getPort() != -1
                || action.getUserInfo() != null
                || action.getRawQuery() != null
                || action.getFragment() != null
                || !Objects.equals(action.getPath(), "/pay/qrcode/" + rawTradeNo + "/")) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.LIUHAO_CHECKOUT_UNAVAILABLE,
                    "Liuhao checkout did not return the canonical QR page.");
        }
        return action;
    }

    private static PaymentCheckoutSubmission boundToOrderExpiry(
            PaymentCheckoutSubmission submission,
            OffsetDateTime orderExpiresAt) {
        if (submission == null
                || !submission.submitExpiresAt().isAfter(orderExpiresAt)) {
            return submission;
        }

        // 本地订单截止时间是浏览器能否继续付款的最终业务边界；
        // 只缩短提交描述元数据，绝不改写已经参与 Provider 签名的表单字段。
        return new PaymentCheckoutSubmission(
                submission.provider(),
                submission.checkoutMode(),
                submission.action(),
                submission.method(),
                submission.contentType(),
                orderExpiresAt,
                submission.fields());
    }

    private static void requirePaymentStartAllowed(
            MembershipOrderSnapshot snapshot,
            long loginIdentityId,
            OffsetDateTime attemptedAt) {
        if (snapshot.loginIdentityId() != loginIdentityId) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_ORDER_NOT_FOUND,
                    "The membership order was not found.");
        }
        if (snapshot.status() != MembershipOrderStatus.PENDING_PAYMENT
                || !attemptedAt.isBefore(snapshot.expiresAt())) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT,
                    "The membership order no longer allows payment to start.");
        }
    }

    private static void requirePaymentStartAllowed(
            MembershipOrderRealtimeGuard snapshot,
            long loginIdentityId,
            OffsetDateTime attemptedAt) {
        if (snapshot.loginIdentityId() != loginIdentityId) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_ORDER_NOT_FOUND,
                    "The membership order was not found.");
        }
        if (snapshot.status() != MembershipOrderStatus.PENDING_PAYMENT
                || !attemptedAt.isBefore(snapshot.expiresAt())) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT,
                    "The membership order no longer allows payment to start.");
        }
    }

    private static MembershipOrderSnapshot withProviderTradeNo(
            MembershipOrderSnapshot snapshot,
            String providerTradeNo) {
        return new MembershipOrderSnapshot(
                snapshot.schemaVersion(),
                snapshot.orderId(),
                snapshot.loginIdentityId(),
                snapshot.membershipTier(),
                snapshot.payAmountYuan(),
                snapshot.payType(),
                snapshot.status(),
                snapshot.idempotencyKey(),
                providerTradeNo,
                snapshot.paymentStartedAt(),
                snapshot.expiresAt(),
                snapshot.closingDeadlineAt(),
                snapshot.paidAt(),
                snapshot.stateVersion(),
                snapshot.createdAt(),
                snapshot.updatedAt());
    }

    private static void requirePaymentPatchConsistent(
            MembershipOrderSnapshot databaseSnapshot,
            MembershipOrderSnapshot current) {
        if (current.stateVersion() < databaseSnapshot.stateVersion()
                || (current.stateVersion() == databaseSnapshot.stateVersion()
                    && !Objects.equals(
                            current.paymentStartedAt(), databaseSnapshot.paymentStartedAt()))) {
            throw new MembershipPaymentInfrastructureException(
                    "Redis membership payment attempt patch did not converge to the database fact.");
        }
    }

    private MembershipOrderSnapshot toSnapshot(MembershipOrder order) {
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                base64UrlCodec.encode(order.getId()),
                required(order.getLoginIdentityId(), "owner"),
                order.getMembershipTier(),
                order.getPayAmountYuan(),
                order.getPayType(),
                order.getStatus(),
                order.getIdempotencyKey(),
                order.getProviderTradeNo(),
                order.getPaymentStartedAt(),
                order.getExpiresAt(),
                order.getClosingDeadlineAt(),
                order.getPaidAt(),
                required(order.getStateVersion(), "state version"),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    private static long required(Long value, String name) {
        if (value == null || value <= 0L) {
            throw new IllegalStateException("Membership order " + name + " is invalid.");
        }
        return value;
    }
}
