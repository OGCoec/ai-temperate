package com.example.temperate.web.edgeproxy;

import com.example.temperate.common.net.ip.IpAddressIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 验证 Worker 对浏览器外部主机和完整 HTTP 请求边界生成的 HMAC-SHA256 签名。
 *
 * <p>v2 签名绑定版本、方法、原始路径与查询、外部主机、时间戳、Cloudflare Ray、IP、国家、
 * ASN 和坐标；v1 仅保留在兼容窗口。只有验签成功的属性才能进入 Cookie 与网络风险边界。</p>
 */
public final class EdgeProxySignatureVerifier {

    public static final String EXTERNAL_HOST_HEADER = "X-AIT-Edge-Host";
    public static final String VERSION_HEADER = "X-AIT-Edge-Version";
    public static final String TIMESTAMP_HEADER = "X-AIT-Edge-Timestamp";
    public static final String RAY_HEADER = "X-AIT-Edge-Ray";
    public static final String SIGNATURE_HEADER = "X-AIT-Edge-Signature";
    public static final String CLIENT_IP_HEADER = "X-AIT-Edge-IP";
    public static final String COUNTRY_HEADER = "X-AIT-Edge-Country";
    public static final String ASN_HEADER = "X-AIT-Edge-ASN";
    public static final String LATITUDE_HEADER = "X-AIT-Edge-Latitude";
    public static final String LONGITUDE_HEADER = "X-AIT-Edge-Longitude";

    private static final String VERSION_V1 = "v1";
    private static final String VERSION_V2 = "v2";
    private static final Set<String> ALLOWED_EXTERNAL_HOSTS =
            Set.of("niko000o.site", "admin.niko000o.site");

    private final EdgeProxyProperties properties;
    private final Clock clock;
    private final byte[] secret;

    /**
     * 创建验签器；启用模式下密钥已由配置绑定阶段保证为规范 Base64。
     *
     * @param properties 边缘代理安全配置
     * @param clock 用于限制签名重放窗口的时钟
     */
    public EdgeProxySignatureVerifier(
            EdgeProxyProperties properties,
            Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.secret = decodeSecret(properties);
    }

    /**
     * 验证当前请求并返回可供后续安全与诊断边界使用的外部 Host、Worker Ray 和网络上下文。
     *
     * @param request 当前 API 请求
     * @return 已通过签名和白名单校验的边缘请求结果
     * @throws EdgeProxyVerificationException 签名契约任何部分无效时抛出
     */
    public EdgeProxyVerificationResult verify(HttpServletRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String versionHeader = request.getHeader(VERSION_HEADER);
        String version = versionHeader == null || versionHeader.isBlank()
                ? VERSION_V1
                : versionHeader;
        if (!VERSION_V1.equals(version) && !VERSION_V2.equals(version)) {
            throw invalid();
        }
        String externalHost = requiredHeader(request, EXTERNAL_HOST_HEADER);
        if (!ALLOWED_EXTERNAL_HOSTS.contains(externalHost)
                || !externalHost.equals(
                        externalHost.trim().toLowerCase(Locale.ROOT))) {
            throw invalid();
        }

        long timestamp = parseTimestamp(requiredHeader(request, TIMESTAMP_HEADER));
        Duration age = Duration.between(
                Instant.ofEpochSecond(timestamp),
                clock.instant()).abs();
        if (age.compareTo(properties.maxClockSkew()) > 0) {
            throw invalid();
        }

        String ray = requiredHeader(request, RAY_HEADER);
        if (!ray.matches("^[A-Za-z0-9-]{1,128}$")) {
            throw invalid();
        }
        String suppliedSignature = requiredHeader(request, SIGNATURE_HEADER);
        TrustedEdgeNetworkContext networkContext = VERSION_V2.equals(version)
                ? networkContext(request, ray)
                : null;
        byte[] supplied = decodeCanonicalUrlSignature(suppliedSignature);
        byte[] expected = sign(canonicalRequest(
                request,
                version,
                externalHost,
                timestamp,
                ray,
                networkContext));
        // 常量时间比较避免根据响应耗时逐步推断签名内容。
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw invalid();
        }
        return new EdgeProxyVerificationResult(
                version,
                externalHost,
                ray,
                networkContext);
    }

    /**
     * 判断请求是否携带任意一个专用边缘头，用于拒绝不完整或伪造的部分签名。
     *
     * @param request 当前 API 请求
     * @return 任一专用边缘头存在时返回 true
     */
    public boolean hasAnyEdgeHeader(HttpServletRequest request) {
        return request.getHeader(VERSION_HEADER) != null
                || request.getHeader(EXTERNAL_HOST_HEADER) != null
                || request.getHeader(TIMESTAMP_HEADER) != null
                || request.getHeader(RAY_HEADER) != null
                || request.getHeader(SIGNATURE_HEADER) != null
                || request.getHeader(CLIENT_IP_HEADER) != null
                || request.getHeader(COUNTRY_HEADER) != null
                || request.getHeader(ASN_HEADER) != null
                || request.getHeader(LATITUDE_HEADER) != null
                || request.getHeader(LONGITUDE_HEADER) != null;
    }

    private String canonicalRequest(
            HttpServletRequest request,
            String version,
            String externalHost,
            long timestamp,
            String ray,
            TrustedEdgeNetworkContext networkContext) {
        String query = request.getQueryString();
        String pathAndQuery = query == null
                ? request.getRequestURI()
                : request.getRequestURI() + "?" + query;
        String base = String.join(
                "\n",
                version,
                request.getMethod().toUpperCase(Locale.ROOT),
                pathAndQuery,
                externalHost,
                Long.toString(timestamp),
                ray);
        if (VERSION_V1.equals(version)) {
            return base;
        }
        return String.join(
                "\n",
                base,
                request.getHeader(CLIENT_IP_HEADER),
                request.getHeader(COUNTRY_HEADER),
                request.getHeader(ASN_HEADER),
                request.getHeader(LATITUDE_HEADER),
                request.getHeader(LONGITUDE_HEADER));
    }

    private static TrustedEdgeNetworkContext networkContext(
            HttpServletRequest request,
            String ray) {
        String ip = canonicalIp(requiredHeader(request, CLIENT_IP_HEADER));
        String country = optionalHeader(request, COUNTRY_HEADER);
        if (!country.isEmpty() && !country.matches("^[A-Z]{2}$")) {
            throw invalid();
        }
        String asnText = optionalHeader(request, ASN_HEADER);
        Long asn = asnText.isEmpty() ? null : parseAsn(asnText);
        String latitudeText = optionalHeader(request, LATITUDE_HEADER);
        String longitudeText = optionalHeader(request, LONGITUDE_HEADER);
        if (latitudeText.isEmpty() != longitudeText.isEmpty()) {
            throw invalid();
        }
        BigDecimal latitude = latitudeText.isEmpty()
                ? null
                : coordinate(latitudeText, -90, 90);
        BigDecimal longitude = longitudeText.isEmpty()
                ? null
                : coordinate(longitudeText, -180, 180);
        return new TrustedEdgeNetworkContext(
                ip,
                country.isEmpty() ? null : country,
                asn,
                latitude,
                longitude,
                ray);
    }

    private static String canonicalIp(String value) {
        try {
            // 签名始终绑定原始请求头；只有验签上下文构造阶段才统一展示格式，不能改变 Worker 线上的签名协议。
            return IpAddressIdentity.parse(value).canonicalText();
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static long parseAsn(String value) {
        try {
            if (!value.matches("^[0-9]{1,10}$")) {
                throw invalid();
            }
            long parsed = Long.parseLong(value);
            if (parsed < 0 || parsed > 4_294_967_295L) {
                throw invalid();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private static BigDecimal coordinate(String value, int minimum, int maximum) {
        try {
            BigDecimal parsed = new BigDecimal(value);
            if (parsed.compareTo(BigDecimal.valueOf(minimum)) < 0
                    || parsed.compareTo(BigDecimal.valueOf(maximum)) > 0) {
                throw invalid();
            }
            // Worker 和后端必须对同一十进制文本签名，拒绝非规范科学计数或尾随零形式。
            if (!coordinate(parsed).equals(value)) {
                throw invalid();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private static String coordinate(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String optionalHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.length() > 128 || !value.equals(value.trim())) {
            throw invalid();
        }
        return value;
    }

    private byte[] sign(String canonicalRequest) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static byte[] decodeSecret(EdgeProxyProperties properties) {
        // 本地 DISABLED 模式完全不参与边缘验签，避免遗留的无效密钥值阻断开发环境启动。
        if (properties.mode() == EdgeProxyMode.DISABLED) {
            return new byte[0];
        }
        if (properties.hmacSecretBase64() == null
                || properties.hmacSecretBase64().isBlank()) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(properties.hmacSecretBase64());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Edge proxy secret is invalid", exception);
        }
    }

    private static byte[] decodeCanonicalUrlSignature(String value) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            String canonical = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(decoded);
            if (!canonical.equals(value) || decoded.length != 32) {
                throw invalid();
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static long parseTimestamp(String value) {
        try {
            if (!value.matches("^[0-9]{1,12}$")) {
                throw invalid();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private static String requiredHeader(
            HttpServletRequest request,
            String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        return value;
    }

    private static EdgeProxyVerificationException invalid() {
        return new EdgeProxyVerificationException("Edge proxy signature is invalid.");
    }
}
