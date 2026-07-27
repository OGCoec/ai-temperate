package com.example.temperate.service.admin.registration;

import com.example.temperate.service.registration.dto.command.RegistrationSendCodeCommand;
import com.example.temperate.service.registration.dto.command.RegistrationStartCommand;
import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodesCommand;
import com.example.temperate.service.registration.dto.query.RegistrationStatusQuery;
import com.example.temperate.service.registration.dto.result.RegistrationStartResult;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.dto.result.VerificationDispatchResult;
import com.example.temperate.service.registration.flow.security.RegistrationAccess;
import reactor.core.publisher.Mono;

/**
 * 定义单管理员首次注册的 Flow、hCaptcha、双验证码和原子文件初始化编排。
 *
 * <p>该服务复用普通用户已验证的 Redis/RabbitMQ 注册状态机，但最终不访问 PostgreSQL。</p>
 */
public interface AdminRegistrationService {

    RegistrationStartResult start(RegistrationStartCommand command);

    RegistrationStatusResult status(RegistrationStatusQuery query);

    Mono<RegistrationStatusResult> verifyHcaptcha(
            RegistrationAccess access,
            String hcaptchaToken);

    VerificationDispatchResult sendCode(RegistrationSendCodeCommand command);

    RegistrationStatusResult verifyCodes(RegistrationVerifyCodesCommand command);

    void complete(AdminRegistrationCompleteCommand command);
}
