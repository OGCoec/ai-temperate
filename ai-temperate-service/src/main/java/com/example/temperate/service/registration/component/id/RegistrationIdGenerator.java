package com.example.temperate.service.registration.component.id;

/**
 * 定义注册身份创建所需的正数内部 ID 生成能力。
 */
public interface RegistrationIdGenerator {

    long nextPositiveId();
}
