package com.example.temperate.service.humanverification;

import reactor.core.publisher.Mono;

/**
 * 定义第三方人机验证的统一非阻塞服务契约，由业务层通过注册表选择供应商实现。
 *
 * <p>该接口只负责服务端验证一次性响应 Token，不负责创建业务 Flow、修改 Redis 状态或决定业务错误映射。
 */
public interface HumanVerificationService {

    String TRACE_ID_CONTEXT_KEY = "ait.human-verification.traceId";

    /**
     * 返回实现对应的稳定策略类型，供启动阶段 Registry 建立唯一映射。
     *
     * @return 不依赖 Bean 名称的人机验证类型
     */
    HumanVerificationType type();

    /**
     * 惰性校验一次性供应商响应，只有订阅返回的 Mono 后才允许发起网络请求。
     *
     * <p>供应商明确拒绝 Token 或安全绑定失败时传播业务拒绝异常；网络、TLS、超时或无法信任的供应商
     * 响应必须传播 {@code HumanVerificationUnavailableException}，由 Web 层统一映射为服务不可用。</p>
     *
     * @param command 当前业务 Flow 构造的统一校验命令
     * @return 成功时为空结果，失败时传播受控拒绝或供应商不可用异常
     */
    Mono<Void> verify(HumanVerificationCommand command);
}
