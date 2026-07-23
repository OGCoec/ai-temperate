package com.example.temperate.service.auth.login.strategy.impl;

import com.example.temperate.service.auth.login.dto.command.LoginCommand;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.login.service.LoginService;
import com.example.temperate.service.auth.login.strategy.LoginStrategy;
import com.example.temperate.service.auth.login.strategy.LoginStrategyRequest;
import com.example.temperate.service.auth.login.strategy.LoginStrategyType;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import org.springframework.stereotype.Component;

/**
 * 将邮箱或国际手机号密码登录请求转换为统一密码登录命令的策略实现。
 *
 * <p>策略要求邮箱与手机号二选一，避免存在多个身份字段时产生不确定的认证目标。</p>
 */
@Component("passwordLoginStrategy")
public final class PasswordLoginStrategy implements LoginStrategy {

    private final LoginService loginService;
    private final RegistrationInputNormalizer inputNormalizer;

    public PasswordLoginStrategy(
            LoginService loginService,
            RegistrationInputNormalizer inputNormalizer) {
        this.loginService = loginService;
        this.inputNormalizer = inputNormalizer;
    }

    @Override
    public LoginStrategyType type() {
        return LoginStrategyType.PASSWORD;
    }

    @Override
    public LoginResult login(LoginStrategyRequest request) {
        if (request == null) {
            throw invalid();
        }
        String identifier;
        boolean hasEmail = request.email() != null && !request.email().isBlank();
        boolean hasPhone = request.phoneNumber() != null && !request.phoneNumber().isBlank();
        // 身份字段必须互斥，不能依据字段顺序静默选择其中一个，防止请求语义与审计主体不一致。
        if (hasEmail == hasPhone) {
            throw invalid();
        }
        if (hasEmail) {
            identifier = inputNormalizer.normalizeEmail(request.email());
        } else {
            identifier = inputNormalizer.normalizePhone(
                    request.countryIso2(), request.phoneNumber());
        }
        return loginService.login(new LoginCommand(
                identifier,
                request.password(),
                request.deviceInstallationId(),
                request.clientIp()));
    }

    private static LoginException invalid() {
        return new LoginException(
                LoginErrorCode.INVALID_INPUT,
                "Provide either email or international phone login fields.");
    }
}
