package com.example.temperate.common.validation.device;

import java.util.regex.Pattern;

/**
 * 校验客户端设备安装标识是否为规范的小写 UUIDv4。
 *
 * <p>该校验只约束传输格式，设备与会话的实际绑定关系由认证会话服务负责。</p>
 */
public final class DeviceInstallationIdValidator {

    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private DeviceInstallationIdValidator() {
    }

    public static boolean isValid(String value) {
        return value != null && UUID_V4.matcher(value).matches();
    }
}
