package com.example.temperate.service.registration.component.token;

/**
 * 定义注册流程 Token、CSRF、挑战句柄和完成领取标识的随机生成能力。
 */
public interface RegistrationTokenGenerator {

    String newRegisterToken();

    String newFlowCsrf();

    String newChallengeHandle();

    String newCompletionClaim();
}
