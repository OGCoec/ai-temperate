package com.example.temperate.service.registration.component.id.impl;

import com.example.temperate.common.id.snowflake.component.SnowflakeIdWorker;
import com.example.temperate.service.registration.component.id.RegistrationIdGenerator;
import org.springframework.stereotype.Component;

/**
 * 使用 Snowflake ID 生成器为注册身份提供正数内部主键的实现。
 */
@Component
public final class SnowflakeRegistrationIdGenerator implements RegistrationIdGenerator {

    private final SnowflakeIdWorker idWorker;

    public SnowflakeRegistrationIdGenerator(SnowflakeIdWorker idWorker) {
        this.idWorker = idWorker;
    }

    @Override
    public long nextPositiveId() {
        long id = idWorker.nextId();
        if (id <= 0) {
            throw new IllegalStateException("Registration ID generator returned a non-positive ID.");
        }
        return id;
    }
}
