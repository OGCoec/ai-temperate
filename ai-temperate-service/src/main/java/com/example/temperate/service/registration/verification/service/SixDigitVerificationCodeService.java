package com.example.temperate.service.registration.verification.service;

import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.enums.VerificationProvider;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.logging.annotation.VerificationDeliveryLogged;
import reactor.core.publisher.Mono;

/**
 * 统一六位数验证码的供应商发送能力和注册流程校验入口。
 *
 * <p>各实现只负责自己的供应商发送适配；用户输入校验必须委托共享 Redis 校验器，禁止调用第三方供应商的
 * 验证接口或在实现类中复制缓存校验逻辑。</p>
 */
public interface SixDigitVerificationCodeService {

    /**
     * 返回实现声明的稳定供应商类型，供服务端注册表在启动阶段建立不可变映射。
     *
     * @return 供应商类型
     */
    VerificationProvider type();

    /**
     * 执行一次真实供应商投递，不在实现内部进行跨时间重试。
     *
     * @param request 受保护的目标地址、验证码和业务用途
     * @return 供应商接受结果
     */
    @VerificationDeliveryLogged
    Mono<VerificationDeliveryResult> sendCode(VerificationDeliveryRequest request);

    /**
     * 校验注册流程中的用户输入，具体实现必须委托共享 Redis 校验器。
     *
     * @param command 注册访问凭据、渠道和六位数验证码
     * @return 原子校验后的注册流程状态
     */
    RegistrationStatusResult verifyCode(RegistrationVerifyCodeCommand command);
}
