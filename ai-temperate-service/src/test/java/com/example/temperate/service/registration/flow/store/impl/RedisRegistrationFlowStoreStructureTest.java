package com.example.temperate.service.registration.flow.store.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.registration.flow.store.RegistrationFlowStore;
import java.lang.reflect.Modifier;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 验证 Redis 注册流程存储的脚本、Key 与字段结构约束的测试。
 */
class RedisRegistrationFlowStoreStructureTest {

    @Test
    void providesFinalConstructorInjectedSpringService() throws Exception {
        Class<?> type = Class.forName(
                "com.example.temperate.service.registration.flow.store.impl.RedisRegistrationFlowStore");

        assertThat(Modifier.isFinal(type.getModifiers())).isTrue();
        assertThat(type.isAnnotationPresent(Service.class)).isTrue();
        assertThat(RegistrationFlowStore.class.isAssignableFrom(type)).isTrue();
        assertThat(type.getConstructor(
                        StringRedisTemplate.class,
                        RedisKeyFactory.class,
                        Duration.class,
                        Duration.class,
                        Duration.class,
                        Duration.class,
                        int.class,
                        int.class))
                .isNotNull();
    }
}
