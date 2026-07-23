package com.example.temperate.common.validation.device;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 验证设备安装标识只接受规范小写 UUIDv4 格式。
 */
class DeviceInstallationIdValidatorTest {

    @Test
    void acceptsOnlyCanonicalUuidVersionFourInstallationIds() {
        assertTrue(DeviceInstallationIdValidator.isValid(
                "550e8400-e29b-41d4-a716-446655440000"));

        assertFalse(DeviceInstallationIdValidator.isValid(null));
        assertFalse(DeviceInstallationIdValidator.isValid(""));
        assertFalse(DeviceInstallationIdValidator.isValid(
                "550E8400-E29B-41D4-A716-446655440000"));
        assertFalse(DeviceInstallationIdValidator.isValid(
                "550e8400-e29b-11d4-a716-446655440000"));
        assertFalse(DeviceInstallationIdValidator.isValid(
                "550e8400-e29b-41d4-c716-446655440000"));
        assertFalse(DeviceInstallationIdValidator.isValid("device-installation"));
    }
}
