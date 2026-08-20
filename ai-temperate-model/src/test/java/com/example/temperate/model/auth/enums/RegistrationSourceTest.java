package com.example.temperate.model.auth.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 验证账号首次注册来源与 PostgreSQL SMALLINT 编码之间的稳定映射。
 */
class RegistrationSourceTest {

    @Test
    void shouldMapEveryPersistentCode() {
        assertEquals(RegistrationSource.STANDARD, RegistrationSource.fromDatabaseCode((short) 0));
        assertEquals(RegistrationSource.GITHUB, RegistrationSource.fromDatabaseCode((short) 1));
        assertEquals(RegistrationSource.GOOGLE, RegistrationSource.fromDatabaseCode((short) 2));
    }

    @Test
    void shouldRejectUnknownPersistentCode() {
        assertThrows(IllegalArgumentException.class,
                () -> RegistrationSource.fromDatabaseCode((short) 3));
    }
}
