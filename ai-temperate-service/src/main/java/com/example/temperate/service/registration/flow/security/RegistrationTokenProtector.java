package com.example.temperate.service.registration.flow.security;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.flow.domain.RegistrationActor;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.common.validation.device.DeviceInstallationIdValidator;
import java.util.Objects;

/**
 * 将注册流程的外部访问材料转换为可安全存储和比较的 HMAC 标识。
 *
 * <p>用途：集中执行设备标识边界校验，并将注册令牌、流程 CSRF、挑战句柄和验证码转换为受保护索引。</p>
 *
 * <p>安全原理：不同用途使用独立业务域参与 HMAC 计算，避免同一原始值在不同状态字段中被混用；Redis 中只保存
 * 摘要，不保存可由客户端直接使用的原始凭据。</p>
 */
public final class RegistrationTokenProtector {

    private static final String NUL = Character.toString(0);

    private final HmacSha256Identifier hmac;
    private final AuthSessionSecretProtector authProtector;

    public RegistrationTokenProtector(
            HmacSha256Identifier hmac, AuthSessionSecretProtector authProtector) {
        this.hmac = Objects.requireNonNull(hmac, "hmac must not be null");
        this.authProtector =
                Objects.requireNonNull(authProtector, "authProtector must not be null");
    }

    public ProtectedRegistrationAccess protect(RegistrationAccess access) {
        if (access == null || !DeviceInstallationIdValidator.isValid(
                access.deviceInstallationId())) {
            throw invalidAccess();
        }
        // 同一访问材料在不同状态字段使用不同业务域，防止跨字段复用同一个摘要。
        return new ProtectedRegistrationAccess(
                identify("register:flow", access.registerToken()),
                identify("register:csrf", access.flowCsrf()),
                identify("register:challenge", access.challengeHandle()),
                identify("register:device", access.deviceInstallationId()),
                authProtector.deviceBlock(access.deviceInstallationId()),
                identify("register:request-binding", access.deviceInstallationId()),
                identify("register:email-code", access.registerToken()),
                identify("register:phone-code", access.registerToken()));
    }

    public RegistrationActor protectActor(String deviceInstallationId, String canonicalIp) {
        if (!DeviceInstallationIdValidator.isValid(deviceInstallationId)) {
            throw invalidAccess();
        }
        return new RegistrationActor(
                identify("register:device", deviceInstallationId),
                authProtector.deviceBlock(deviceInstallationId));
    }

    public HmacIdentifier codeDigest(
            String registerToken, VerificationChannel channel, String code) {
        Objects.requireNonNull(channel, "channel must not be null");
        return identify("register:code", registerToken, channel.name(), code);
    }

    /**
     * 为一次性 Turnstile 响应生成仅供服务端关联诊断使用的不可逆标识。
     *
     * <p>该标识与流程和挑战使用不同 HMAC 业务域，只允许截断后写入日志，不参与 Cloudflare 校验或重放判断。</p>
     */
    public HmacIdentifier turnstileResponseDigest(String responseToken) {
        return identify("register:turnstile-response", responseToken);
    }

    /**
     * 为请求携带的规范 IP 生成仅供排障比较使用的不可逆标识。
     *
     * <p>该方法不改变现有注册流程的设备绑定规则；调用方只能记录截断指纹，用于判断隧道或代理解析结果是否切换。</p>
     */
    public HmacIdentifier clientIpDiagnosticDigest(String canonicalIp) {
        return identify("register:diagnostic-ip", canonicalIp);
    }

    public HmacIdentifier completionClaimDigest(String claim) {
        return identify("register:completion-claim", claim);
    }

    public HmacIdentifier deliveryOperationDigest(String operationId) {
        return identify("register:delivery-operation", operationId);
    }

    private HmacIdentifier identify(String... parts) {
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                throw invalidAccess();
            }
        }
        // 使用固定分隔符组装业务域和输入，保证 HMAC 输入在服务端具有一致的边界。
        return hmac.identify(String.join(NUL, parts));
    }

    private static RegistrationException invalidAccess() {
        return new RegistrationException(
                RegistrationErrorCode.REGISTRATION_FLOW_FORBIDDEN,
                "Registration flow credentials are invalid.");
    }
}
