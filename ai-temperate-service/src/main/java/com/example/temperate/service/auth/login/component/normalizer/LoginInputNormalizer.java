package com.example.temperate.service.auth.login.component.normalizer;

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
    private static final Pattern IPV6_LITERAL = Pattern.compile("^[0-9A-Fa-f:]{2,45}$");

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
        if (value == null || value.isBlank() || value.length() > 45 || !value.equals(value.trim())) {
            throw invalid("Client IP is invalid.");
        }
        // 限流和审计以 IP 为键，必须拒绝非规范 IPv4/IPv6 表示以避免同一地址产生多个风控维度。
        if (isCanonicalIpv4(value)) {
            return value;
        }
        int[] ipv6Groups = parseIpv6Literal(value);
        if (ipv6Groups != null && toRfc5952(ipv6Groups).equals(value)) {
            return value;
        }
        throw invalid("Client IP must be a canonical IPv4 or IPv6 literal.");
    }

    private static int[] parseIpv6Literal(String value) {
        if (value.indexOf(':') < 0 || !IPV6_LITERAL.matcher(value).matches()) {
            return null;
        }
        int compression = value.indexOf("::");
        if (compression >= 0 && compression != value.lastIndexOf("::")) {
            return null;
        }

        String[] left;
        String[] right;
        if (compression >= 0) {
            left = splitIpv6Side(value.substring(0, compression));
            right = splitIpv6Side(value.substring(compression + 2));
            if (left == null || right == null || left.length + right.length >= 8) {
                return null;
            }
        } else {
            left = splitIpv6Side(value);
            right = new String[0];
            if (left == null || left.length != 8) {
                return null;
            }
        }

        int[] groups = new int[8];
        int index = 0;
        for (String group : left) {
            groups[index++] = Integer.parseInt(group, 16);
        }
        index = 8 - right.length;
        for (String group : right) {
            groups[index++] = Integer.parseInt(group, 16);
        }
        return groups;
    }

    private static String[] splitIpv6Side(String side) {
        if (side.isEmpty()) {
            return new String[0];
        }
        String[] groups = side.split(":", -1);
        for (String group : groups) {
            if (group.isEmpty() || group.length() > 4 || !isHex(group)) {
                return null;
            }
        }
        return groups;
    }

    private static boolean isHex(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean hexadecimal = character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f'
                    || character >= 'A' && character <= 'F';
            if (!hexadecimal) {
                return false;
            }
        }
        return true;
    }

    private static String toRfc5952(int[] groups) {
        int bestStart = -1;
        int bestLength = 0;
        for (int index = 0; index < groups.length;) {
            if (groups[index] != 0) {
                index++;
                continue;
            }
            int end = index;
            while (end < groups.length && groups[end] == 0) {
                end++;
            }
            int length = end - index;
            if (length >= 2 && length > bestLength) {
                bestStart = index;
                bestLength = length;
            }
            index = end;
        }
        if (bestStart < 0) {
            return joinIpv6Groups(groups, 0, groups.length);
        }
        String prefix = joinIpv6Groups(groups, 0, bestStart);
        String suffix = joinIpv6Groups(groups, bestStart + bestLength, groups.length);
        return prefix + "::" + suffix;
    }

    private static String joinIpv6Groups(int[] groups, int start, int end) {
        StringBuilder result = new StringBuilder();
        for (int index = start; index < end; index++) {
            if (!result.isEmpty()) {
                result.append(':');
            }
            result.append(Integer.toHexString(groups[index]));
        }
        return result.toString();
    }

    private static boolean isCanonicalIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty()
                    || part.length() > 3
                    || (part.length() > 1 && part.charAt(0) == '0')
                    || !part.chars().allMatch(character -> character >= '0' && character <= '9')
                    || Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static LoginException invalid(String message) {
        return new LoginException(LoginErrorCode.INVALID_INPUT, message);
    }

    private record NormalizedIdentifier(LoginIdentifierType type, String value) {
    }
}
