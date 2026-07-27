package com.example.temperate.service.admin.security;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.common.validation.device.DeviceInstallationIdValidator;
import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.service.admin.login.AdminLoginAccess;
import com.example.temperate.service.admin.login.ProtectedAdminLoginAccess;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 使用管理员专用 HMAC Secret 保护登录 Flow、CSRF、Challenge、设备和会话 Token。
 *
 * <p>不同用途带独立业务域，防止同一原始值在 Redis Key、Hash Field 和设备绑定之间被混用。</p>
 */
@Component
public final class AdminSecretProtector {

    private static final String NUL = Character.toString(0);
    private static final Pattern NANO_ID = Pattern.compile("^[A-Za-z0-9_-]{38}$");
    private static final Pattern BASE64_URL_32 = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final HmacSha256Identifier hmac;

    public AdminSecretProtector(AdminProperties properties) {
        String configured = Objects.requireNonNull(properties).sessionHmacSecretBase64();
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Admin session HMAC secret must be canonical Base64.", exception);
        }
        if (secret.length < 32
                || !Base64.getEncoder().encodeToString(secret).equals(configured)) {
            throw new IllegalStateException(
                    "Admin session HMAC secret must decode to at least 32 bytes.");
        }
        this.hmac = new HmacSha256Identifier(secret);
    }

    public HmacIdentifier loginFlow(String rawFlowToken) {
        return identify("admin:login:flow", requireLoginNanoId(rawFlowToken));
    }

    public HmacIdentifier loginChallenge(String rawChallenge) {
        return identify("admin:login:challenge", requireLoginBase64Url32(rawChallenge));
    }

    public HmacIdentifier loginCsrf(String rawCsrf) {
        return identify("admin:login:csrf", requireLoginBase64Url32(rawCsrf));
    }

    public HmacIdentifier sessionToken(String rawAdminToken) {
        if (rawAdminToken == null || !NANO_ID.matcher(rawAdminToken).matches()) {
            throw invalidSession();
        }
        return identify("admin:session:token", rawAdminToken);
    }

    public HmacIdentifier sessionDevice(String deviceInstallationId) {
        if (!DeviceInstallationIdValidator.isValid(deviceInstallationId)) {
            throw invalidSession();
        }
        return identify("admin:session:device", deviceInstallationId);
    }

    /**
     * 将完整管理员登录 Flow 访问材料转换为四个用途隔离的 HMAC 标识。
     */
    public ProtectedAdminLoginAccess protectLogin(AdminLoginAccess access) {
        if (access == null
                || !DeviceInstallationIdValidator.isValid(access.deviceInstallationId())) {
            throw invalidFlow();
        }
        return new ProtectedAdminLoginAccess(
                loginFlow(access.flowToken()),
                loginCsrf(access.flowCsrf()),
                loginChallenge(access.challengeId()),
                identify("admin:login:device", access.deviceInstallationId()));
    }

    private HmacIdentifier identify(String domain, String value) {
        return hmac.identify(String.join(NUL, domain, value));
    }

    private static String requireLoginNanoId(String value) {
        if (value == null || !NANO_ID.matcher(value).matches()) {
            throw invalidFlow();
        }
        return value;
    }

    private static String requireLoginBase64Url32(String value) {
        if (value == null || !BASE64_URL_32.matcher(value).matches()) {
            throw invalidFlow();
        }
        return value;
    }

    private static AdminException invalidFlow() {
        return new AdminException(
                AdminErrorCode.ADMIN_FLOW_INVALID,
                "Administrator flow credentials are invalid.",
                null,
                true,
                false);
    }

    private static AdminException invalidSession() {
        return new AdminException(
                AdminErrorCode.ADMIN_SESSION_INVALID,
                "Administrator session is invalid.",
                null,
                false,
                true);
    }
}
