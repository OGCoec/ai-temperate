package com.example.temperate.service.auth.protection.component;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.auth.login.limit.dto.LoginAttempt;
import com.example.temperate.service.auth.login.limit.dto.ProtectedLoginAttempt;
import com.example.temperate.common.validation.device.DeviceInstallationIdValidator;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 将认证流程中的原始令牌、验证码、设备和身份数据校验后转换为带域隔离的 HMAC 标识。
 *
 * <p>该组件是原始认证材料进入 Redis、风控和会话状态前的唯一保护边界；不同业务域加入前缀和分隔符，
 * 防止相同文本在不同用途下产生可混用的标识。</p>
 */
public final class AuthSessionSecretProtector {

    private static final String NUL = Character.toString(0);
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private static final Pattern NANO_ID = Pattern.compile("^[A-Za-z0-9_-]{38}$");
    private static final Pattern IPV6_LITERAL = Pattern.compile("^[0-9A-Fa-f:]{2,45}$");
    private static final Pattern CSRF = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder BASE64_URL_ENCODER =
            Base64.getUrlEncoder().withoutPadding();

    private final HmacSha256Identifier hmac;

    public AuthSessionSecretProtector(HmacSha256Identifier hmac) {
        this.hmac = Objects.requireNonNull(hmac, "hmac must not be null");
    }

    public ProtectedLoginAttempt protect(LoginAttempt attempt) {
        LoginAttempt valid = Objects.requireNonNull(attempt, "attempt must not be null");
        return new ProtectedLoginAttempt(
                loginSubject(valid.getNormalizedIdentifier()),
                loginActor(valid.getDeviceInstallationId(), valid.getCanonicalClientIp()),
                loginNetwork(valid.getCanonicalClientIp()),
                deviceBlock(valid.getDeviceInstallationId()));
    }

    public HmacIdentifier loginSubject(String normalizedIdentifier) {
        String identifier = requireText("normalized login identifier", normalizedIdentifier, 254);
        if (!isNormalizedEmail(identifier) && !E164.matcher(identifier).matches()) {
            throw invalid("Normalized login identifier must be a lower-case email or E.164 phone.");
        }
        return identify("auth:login:subject", identifier);
    }

    public HmacIdentifier loginActor(
            String deviceInstallationId, String canonicalClientIp) {
        String device = requireDevice(deviceInstallationId);
        return identify("auth:login:device", device);
    }

    public HmacIdentifier loginNetwork(String canonicalClientIp) {
        return identify("auth:login:network", requireCanonicalIp(canonicalClientIp));
    }

    public HmacIdentifier loginFlowToken(String rawFlowToken) {
        return identify("auth:login:flow", requireNanoId("login flow token", rawFlowToken));
    }

    public HmacIdentifier loginChallenge(String challengeHandle) {
        return identify("auth:login:challenge",
                requireNanoId("login challenge", challengeHandle));
    }

    public HmacIdentifier loginCodeKey(String rawFlowToken) {
        return identify("auth:login:code-key",
                requireNanoId("login flow token", rawFlowToken));
    }

    public HmacIdentifier loginCodeDigest(String rawFlowToken, String code) {
        if (code == null || !code.matches("^[0-9]{6}$")) {
            throw invalid("Login verification code must contain six digits.");
        }
        return identify("auth:login:code",
                requireNanoId("login flow token", rawFlowToken), code);
    }

    public HmacIdentifier loginDeliveryOperation(String operationId) {
        return identify("auth:login:delivery", requireText("delivery operation", operationId, 64));
    }

    public HmacIdentifier passwordResetFlowToken(String rawFlowToken) {
        return identify("auth:password-reset:flow",
                requireNanoId("password reset flow token", rawFlowToken));
    }

    public HmacIdentifier passwordResetForgetToken(String rawForgetToken) {
        return identify("auth:password-reset:forget",
                requireNanoId("forget token", rawForgetToken));
    }

    public HmacIdentifier passwordResetChallenge(String challengeHandle) {
        return identify("auth:password-reset:challenge",
                requireNanoId("password reset challenge", challengeHandle));
    }

    public HmacIdentifier passwordResetCodeKey(String rawFlowToken) {
        return identify("auth:password-reset:code-key",
                requireNanoId("password reset flow token", rawFlowToken));
    }

    public HmacIdentifier passwordResetCodeDigest(String rawFlowToken, String code) {
        if (code == null || !code.matches("^[0-9]{6}$")) {
            throw invalid("Password reset verification code must contain six digits.");
        }
        return identify("auth:password-reset:code",
                requireNanoId("password reset flow token", rawFlowToken), code);
    }

    public HmacIdentifier passwordResetTarget(String normalizedTarget) {
        String target = requireText("password reset target", normalizedTarget, 254);
        return identify("auth:password-reset:target", target);
    }

    public HmacIdentifier passwordResetDeliveryOperation(String operationId) {
        return identify("auth:password-reset:delivery",
                requireText("password reset delivery operation", operationId, 64));
    }

    public HmacIdentifier passwordResetClaim(String claimId) {
        return identify("auth:password-reset:claim",
                requireText("password reset claim", claimId, 64));
    }

    public HmacIdentifier refreshToken(String rawRefreshToken) {
        return identify("auth:session:refresh", requireNanoId("refresh token", rawRefreshToken));
    }

    public HmacIdentifier device(String deviceInstallationId) {
        return identify("auth:session:device", requireDevice(deviceInstallationId));
    }

    public HmacIdentifier deviceBlock(String deviceInstallationId) {
        return identify("auth:device:block", requireDevice(deviceInstallationId));
    }

    public HmacIdentifier csrf(String rawCsrfToken) {
        String token = requireText("CSRF token", rawCsrfToken, 43);
        if (!CSRF.matcher(token).matches()) {
            throw invalid("CSRF token must be 32-byte Base64URL without padding.");
        }
        byte[] decoded;
        try {
            decoded = BASE64_URL_DECODER.decode(token);
        } catch (IllegalArgumentException exception) {
            throw invalid("CSRF token must be 32-byte Base64URL without padding.");
        }
        if (decoded.length != 32 || !BASE64_URL_ENCODER.encodeToString(decoded).equals(token)) {
            throw invalid("CSRF token must be 32-byte Base64URL without padding.");
        }
        return identify("auth:session:csrf", token);
    }

    private HmacIdentifier identify(String domain, String... values) {
        // 域前缀与不可出现在受校验字段中的 NUL 分隔符共同定义 HMAC 输入边界，避免字段拼接歧义。
        String[] parts = new String[values.length + 1];
        parts[0] = domain;
        System.arraycopy(values, 0, parts, 1, values.length);
        return hmac.identify(String.join(NUL, parts));
    }

    private static String requireNanoId(String name, String value) {
        String valid = requireText(name, value, 38);
        if (!NANO_ID.matcher(valid).matches()) {
            throw invalid(name + " must be a 38-character NanoID.");
        }
        return valid;
    }

    private static String requireDevice(String value) {
        if (!DeviceInstallationIdValidator.isValid(value)) {
            throw invalid("Device installation ID must be a canonical UUID v4.");
        }
        return value;
    }

    private static String requireCanonicalIp(String value) {
        String valid = requireText("canonical client IP", value, 45);
        if (isCanonicalIpv4(valid)) {
            return valid;
        }
        int[] ipv6Groups = parseIpv6Literal(valid);
        if (ipv6Groups != null && toRfc5952(ipv6Groups).equals(valid)) {
            return valid;
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
            groups[index++] = parseIpv6Group(group);
        }
        index = 8 - right.length;
        for (String group : right) {
            groups[index++] = parseIpv6Group(group);
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

    private static int parseIpv6Group(String group) {
        return Integer.parseInt(group, 16);
    }

    private static boolean isHex(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean hex = character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f'
                    || character >= 'A' && character <= 'F';
            if (!hex) {
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

    private static boolean isNormalizedEmail(String value) {
        int at = value.indexOf('@');
        return at > 0
                && at == value.lastIndexOf('@')
                && at < value.length() - 1
                && value.equals(value.toLowerCase(Locale.ROOT))
                && value.chars().noneMatch(Character::isWhitespace);
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
                    || !part.chars().allMatch(character -> character >= '0' && character <= '9')) {
                return false;
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static String requireText(String name, String value, int maximumLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || !value.equals(value.trim())) {
            throw invalid(name + " is invalid.");
        }
        return value;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
