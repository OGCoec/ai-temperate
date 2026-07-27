package com.example.temperate.service.auth.login.component.normalizer;

import com.example.temperate.common.net.ip.IpAddressIdentity;
import com.example.temperate.common.validation.email.EmailAddressNormalizer;
import com.example.temperate.common.validation.device.DeviceInstallationIdValidator;
import com.example.temperate.service.auth.login.dto.command.LoginCommand;
import com.example.temperate.service.auth.login.dto.internal.NormalizedLoginInput;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.enums.LoginIdentifierType;
import com.example.temperate.service.auth.login.exception.LoginException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 将登录边界输入校验并转换为可参与认证、限流和会话绑定的规范化数据。
 *
 * <p>该组件统一邮箱、手机号、密码字节长度、设备 ID 和客户端 IP 的表示，防止同一主体以多种文本形式
 * 绕过身份查询或风控键；它不执行用户查询、密码比对或令牌签发。</p>
 */
@Component
public final class LoginInputNormalizer {

    private static final int MINIMUM_PASSWORD_BYTES = 7;
    private static final int MAXIMUM_PASSWORD_BYTES = 72;
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

    public NormalizedLoginInput normalize(LoginCommand command) {
        if (command == null) {
            throw invalid("Login command is required.");
        }
        NormalizedIdentifier identifier = normalizeIdentifier(command.getIdentifier());
        String password = requirePassword(command.getRawPassword());
        String deviceInstallationId = normalizeDeviceInstallationId(
                command.getDeviceInstallationId());
        String canonicalClientIp = requireCanonicalIp(command.getClientIp());
        return new NormalizedLoginInput(
                identifier.type(),
                identifier.value(),
                password,
                deviceInstallationId,
                canonicalClientIp);
    }

    public String normalizeDeviceInstallationId(String value) {
        if (!DeviceInstallationIdValidator.isValid(value)) {
            throw invalid(
                    "Device installation ID must be a canonical UUID v4.");
        }
        return value;
    }

    private static NormalizedIdentifier normalizeIdentifier(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("Login identifier is required.");
        }
        if (value.indexOf('@') >= 0) {
            try {
                return new NormalizedIdentifier(
                        LoginIdentifierType.EMAIL,
                        EmailAddressNormalizer.normalize(value));
            } catch (IllegalArgumentException exception) {
                throw invalid("Login identifier is invalid.");
            }
        }
        if (!E164.matcher(value).matches()) {
            throw invalid("Login identifier is invalid.");
        }
        return new NormalizedIdentifier(LoginIdentifierType.PHONE, value);
    }

    private static String requirePassword(String value) {
        if (value == null) {
            throw invalid("Password is required.");
        }
        int utf8Bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Bytes < MINIMUM_PASSWORD_BYTES || utf8Bytes > MAXIMUM_PASSWORD_BYTES) {
            throw invalid("Password must contain between 7 and 72 UTF-8 bytes.");
        }
        return value;
    }

    private static String requireCanonicalIp(String value) {
        // IP 来自受信代理边界而非请求体；格式异常属于服务端网络上下文故障，不能伪装成客户端参数 400。
        return IpAddressIdentity.parse(value).canonicalText();
    }

    private static LoginException invalid(String message) {
        return new LoginException(LoginErrorCode.INVALID_INPUT, message);
    }

    private record NormalizedIdentifier(LoginIdentifierType type, String value) {
    }
}
