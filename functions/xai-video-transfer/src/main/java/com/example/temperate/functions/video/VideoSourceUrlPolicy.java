package com.example.temperate.functions.video;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 对来源 URL 执行协议、主机、端口和 DNS 地址双重校验，阻断 SSRF 与 DNS 指向私网的绕过。
 */
public final class VideoSourceUrlPolicy {

    private final Set<String> allowedHosts;

    public VideoSourceUrlPolicy(Set<String> allowedHosts) {
        this.allowedHosts = Set.copyOf(Objects.requireNonNull(allowedHosts))
                .stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public URI requireAllowed(String sourceUrl) {
        URI uri;
        try {
            uri = URI.create(sourceUrl);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Video source URL is invalid.", exception);
        }
        String host = uri.getHost() == null
                ? ""
                : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !allowedHosts.contains(host)
                || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Video source URL is not allowed.");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new IllegalArgumentException("Video source host has no address.");
            }
            for (InetAddress address : addresses) {
                if (!isPublic(address)) {
                    throw new IllegalArgumentException(
                            "Video source host resolves to a non-public address.");
                }
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException(
                    "Video source host cannot be resolved.", exception);
        }
        return uri;
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first != 0
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 198 && (second == 18 || second == 19))
                    && first < 224;
        }
        if (address instanceof Inet6Address) {
            return (Byte.toUnsignedInt(bytes[0]) & 0xFE) != 0xFC;
        }
        return false;
    }
}
