package com.example.temperate.service.registration.service.lifecycle;
import com.example.temperate.service.registration.dto.command.RegistrationCompleteCommand;
import com.example.temperate.service.registration.dto.command.RegistrationSendCodeCommand;
import com.example.temperate.service.registration.dto.command.RegistrationStartCommand;
import com.example.temperate.service.registration.dto.command.RegistrationTurnstileCommand;
import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodesCommand;
import com.example.temperate.service.registration.dto.query.RegistrationStatusQuery;
import com.example.temperate.service.registration.dto.result.RegistrationCompleteResult;
import com.example.temperate.service.registration.dto.result.RegistrationStartResult;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.dto.result.VerificationDispatchResult;
import reactor.core.publisher.Mono;

/**
 * 注册生命周期的业务服务接口。
 *
 * <p>用途：向 Web 层提供注册开始、状态查询、人工校验、验证码投递与校验以及最终开户的统一编排入口。</p>
 */
public interface RegistrationService {

    RegistrationStartResult start(RegistrationStartCommand command);

    RegistrationStatusResult status(RegistrationStatusQuery query);

    Mono<RegistrationStatusResult> verifyTurnstile(RegistrationTurnstileCommand command);

    VerificationDispatchResult sendCode(RegistrationSendCodeCommand command);

    RegistrationStatusResult verifyCode(RegistrationVerifyCodeCommand command);

    RegistrationStatusResult verifyCodes(RegistrationVerifyCodesCommand command);

    RegistrationCompleteResult complete(RegistrationCompleteCommand command);
}
