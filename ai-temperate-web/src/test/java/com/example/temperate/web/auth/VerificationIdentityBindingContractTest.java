package com.example.temperate.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.web.auth.login.controller.LoginController;
import com.example.temperate.web.auth.passwordreset.controller.PasswordResetController;
import com.example.temperate.web.auth.registration.controller.RegistrationController;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证验证码发送接口不能接收或覆盖联系方式，确保收件目标始终由服务端 Redis 流程决定。
 *
 * <p>本契约只约束人机验证后的发送请求体；邮箱、手机号和国家区号只能在 start 阶段进入流程。</p>
 */
class VerificationIdentityBindingContractTest {

    @Test
    void verificationCodeSendBodiesOnlyAllowDeliveryMethodSelection() {
        assertThat(componentNames(LoginController.CodeSendRequest.class))
                .containsExactly("deliveryMethod");
        assertThat(componentNames(PasswordResetController.CodeSendRequest.class))
                .containsExactly("deliveryMethod");
        assertThat(componentNames(RegistrationController.PhoneCodeSendRequest.class))
                .containsExactly("deliveryMethod");
    }

    @Test
    void verificationCodeSendBodiesNeverExposeIdentityOverrideFields() {
        for (Class<?> requestType : List.of(
                LoginController.CodeSendRequest.class,
                PasswordResetController.CodeSendRequest.class,
                RegistrationController.PhoneCodeSendRequest.class)) {
            assertThat(componentNames(requestType))
                    .doesNotContain("email", "phone", "phoneNumber", "countryIso2");
        }
    }

    private static List<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
