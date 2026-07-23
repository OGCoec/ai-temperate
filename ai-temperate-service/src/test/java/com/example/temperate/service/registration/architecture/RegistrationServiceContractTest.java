package com.example.temperate.service.registration.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.registration.dto.command.RegistrationCompleteCommand;
import com.example.temperate.service.registration.dto.command.RegistrationSendCodeCommand;
import com.example.temperate.service.registration.dto.command.RegistrationStartCommand;
import com.example.temperate.service.registration.dto.command.RegistrationTurnstileCommand;
import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.dto.query.RegistrationStatusQuery;
import com.example.temperate.service.registration.dto.result.RegistrationCompleteResult;
import com.example.temperate.service.registration.dto.result.RegistrationStartResult;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.dto.result.VerificationDispatchResult;
import com.example.temperate.service.registration.service.lifecycle.RegistrationService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * 验证注册服务接口与实现约束的架构测试。
 */
class RegistrationServiceContractTest {

    @Test
    void exposesEachRegistrationStepThroughTheServiceInterface() throws Exception {
        assertThat(RegistrationService.class).isInterface();
        assertReturnType("start", RegistrationStartCommand.class, RegistrationStartResult.class);
        assertReturnType("status", RegistrationStatusQuery.class, RegistrationStatusResult.class);
        assertReturnType(
                "verifyTurnstile",
                RegistrationTurnstileCommand.class,
                RegistrationStatusResult.class);
        assertReturnType(
                "sendCode", RegistrationSendCodeCommand.class, VerificationDispatchResult.class);
        assertReturnType(
                "verifyCode", RegistrationVerifyCodeCommand.class, RegistrationStatusResult.class);
        assertReturnType(
                "complete", RegistrationCompleteCommand.class, RegistrationCompleteResult.class);
    }

    private static void assertReturnType(
            String methodName, Class<?> parameterType, Class<?> returnType) throws Exception {
        Method method = RegistrationService.class.getMethod(methodName, parameterType);
        assertThat(method.getReturnType()).isEqualTo(returnType);
    }
}
