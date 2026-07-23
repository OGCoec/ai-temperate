package com.example.temperate.common.proxy;

import cn.hutool.core.util.StrUtil;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 为需要显式代理的第三方调用选择可达出站路径，并通过有限 TCP 或 HTTP CONNECT 探测避免盲目使用失效端口。
 *
 * <p>该组件只决定本次调用使用直连还是本地代理，不修改系统路由、DNS 或 Mihomo 配置；探测结果也不代表后续 TLS
 * 与业务协议一定成功。</p>
 */
@Component
public class OutboundRouteResolver {

    private static final Logger log = LoggerFactory.getLogger(OutboundRouteResolver.class);

    private final boolean localProxyEnabled;
    private final String localProxyHost;
    private final String candidatePorts;

    public OutboundRouteResolver(
            @Value("${local-proxy.enabled:true}") boolean localProxyEnabled,
            @Value("${local-proxy.host:127.0.0.1}") String localProxyHost,
            @Value("${local-proxy.ports:7897,7892}") String candidatePorts) {
        this.localProxyEnabled = localProxyEnabled;
        this.localProxyHost = StrUtil.blankToDefault(localProxyHost, "127.0.0.1").trim();
        this.candidatePorts = StrUtil.blankToDefault(candidatePorts, "7897,7892");
    }

    public RouteSelection selectRoute(
            String scene,
            String targetHost,
            int targetPort,
            String configuredProxyHost,
            int configuredProxyPort,
            String routeMode,
            int probeTimeoutMs,
            ProxyProtocol proxyProtocol) {
        String normalizedMode = normalizeRouteMode(routeMode);
        ProxyProtocol protocol = proxyProtocol == null ? ProxyProtocol.SOCKS : proxyProtocol;
        int timeoutMs = Math.max(300, probeTimeoutMs);
        List<RouteTarget> targets = buildTargets(
                normalizedMode, configuredProxyHost, configuredProxyPort);
        if (targets.isEmpty()) {
            targets = List.of(RouteTarget.directRoute());
        }

        // auto 模式只比较有限候选路径的可达性与耗时；全部失败时仍返回首选路径，让真实调用产生可分类异常。
        List<RouteProbeResult> probeResults = new ArrayList<>();
        for (int index = 0; index < targets.size(); index += 1) {
            RouteProbeResult result = probeRoute(
                    index,
                    targets.get(index),
                    targetHost,
                    targetPort,
                    timeoutMs,
                    protocol);
            probeResults.add(result);
            log.info(
                    "{} route probe result, mode={}, protocol={}, target={}:{}, route={}, "
                            + "reachable={}, elapsedMillis={}, reason={}",
                    scene,
                    normalizedMode,
                    protocol,
                    targetHost,
                    targetPort,
                    result.target().label(protocol),
                    result.reachable(),
                    result.elapsedMillis(),
                    result.reason());
        }

        RouteProbeResult selected = probeResults.stream()
                .sorted(routeProbeComparator())
                .findFirst()
                .orElseGet(() -> new RouteProbeResult(
                        0, RouteTarget.directRoute(), false, 0L, "no_route_candidate"));
        log.info(
                "{} route selected, mode={}, protocol={}, target={}:{}, route={}, reachable={}, reason={}",
                scene,
                normalizedMode,
                protocol,
                targetHost,
                targetPort,
                selected.target().label(protocol),
                selected.reachable(),
                selected.reason());
        return new RouteSelection(
                selected.target(),
                selected.reachable(),
                selected.reason(),
                selected.elapsedMillis());
    }

    private List<RouteTarget> buildTargets(
            String routeMode, String configuredProxyHost, int configuredProxyPort) {
        Set<String> seen = new LinkedHashSet<>();
        List<RouteTarget> targets = new ArrayList<>();
        if ("direct".equals(routeMode) || "auto".equals(routeMode)) {
            addTarget(targets, seen, RouteTarget.directRoute());
        }
        if (!"direct".equals(routeMode)) {
            addProxyTarget(targets, seen, configuredProxyHost, configuredProxyPort);
            if (localProxyEnabled) {
                for (String rawPort : candidatePorts.split(",")) {
                    addProxyTarget(targets, seen, localProxyHost, parsePort(rawPort));
                }
            }
        }
        return targets;
    }

    private void addProxyTarget(
            List<RouteTarget> targets, Set<String> seen, String host, int port) {
        if (StrUtil.isBlank(host) || port <= 0 || port > 65535) {
            return;
        }
        addTarget(targets, seen, RouteTarget.proxyRoute(host.trim(), port));
    }

    private void addTarget(
            List<RouteTarget> targets, Set<String> seen, RouteTarget target) {
        if (target != null && seen.add(target.key())) {
            targets.add(target);
        }
    }

    private RouteProbeResult probeRoute(
            int index,
            RouteTarget target,
            String targetHost,
            int targetPort,
            int timeoutMs,
            ProxyProtocol protocol) {
        long startedNanos = System.nanoTime();
        try {
            if (target.direct()) {
                probeDirect(targetHost, targetPort, timeoutMs);
            } else if (protocol == ProxyProtocol.HTTP_CONNECT) {
                probeHttpConnect(target, targetHost, targetPort, timeoutMs);
            } else {
                probeSocks(target, targetHost, targetPort, timeoutMs);
            }
            return new RouteProbeResult(
                    index, target, true, elapsedMillis(startedNanos), "ok");
        } catch (IOException | RuntimeException exception) {
            return new RouteProbeResult(
                    index,
                    target,
                    false,
                    elapsedMillis(startedNanos),
                    exception.getClass().getSimpleName());
        }
    }

    private void probeDirect(String targetHost, int targetPort, int timeoutMs)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(targetHost, targetPort), timeoutMs);
        }
    }

    private void probeSocks(
            RouteTarget target, String targetHost, int targetPort, int timeoutMs)
            throws IOException {
        Proxy proxy = new Proxy(
                Proxy.Type.SOCKS, new InetSocketAddress(target.host(), target.port()));
        try (Socket socket = new Socket(proxy)) {
            socket.connect(new InetSocketAddress(targetHost, targetPort), timeoutMs);
        }
    }

    private void probeHttpConnect(
            RouteTarget target, String targetHost, int targetPort, int timeoutMs)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.host(), target.port()), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream outputStream = socket.getOutputStream();
            String authority = targetHost + ":" + targetPort;
            String request = "CONNECT " + authority + " HTTP/1.1\r\n"
                    + "Host: " + authority + "\r\n"
                    + "Proxy-Connection: Keep-Alive\r\n"
                    + "\r\n";
            outputStream.write(request.getBytes(StandardCharsets.US_ASCII));
            outputStream.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = reader.readLine();
            if (statusLine == null
                    || !statusLine.startsWith("HTTP/")
                    || !statusLine.contains(" 200 ")) {
                throw new IOException(
                        "HTTP CONNECT failed: "
                                + StrUtil.blankToDefault(statusLine, "no_status"));
            }
        }
    }

    private Comparator<RouteProbeResult> routeProbeComparator() {
        return (left, right) -> {
            int reachableCompare = Boolean.compare(right.reachable(), left.reachable());
            if (reachableCompare != 0) {
                return reachableCompare;
            }
            if (left.reachable() && right.reachable()) {
                int elapsedCompare = Long.compare(left.elapsedMillis(), right.elapsedMillis());
                if (elapsedCompare != 0) {
                    return elapsedCompare;
                }
            }
            return Integer.compare(left.index(), right.index());
        };
    }

    private String normalizeRouteMode(String routeMode) {
        String normalized = StrUtil.blankToDefault(routeMode, "auto")
                .trim()
                .toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "direct", "proxy", "auto" -> normalized;
            default -> "auto";
        };
    }

    private int parsePort(String rawPort) {
        if (StrUtil.isBlank(rawPort)) {
            return -1;
        }
        try {
            return Integer.parseInt(rawPort.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    public enum ProxyProtocol {
        SOCKS,
        HTTP_CONNECT
    }

    public record RouteSelection(
            RouteTarget target, boolean reachable, String reason, long elapsedMillis) {

        public boolean direct() {
            return target == null || target.direct();
        }

        public String host() {
            return target == null ? "" : target.host();
        }

        public int port() {
            return target == null ? -1 : target.port();
        }

        public InetSocketAddress address() {
            return direct() ? null : new InetSocketAddress(host(), port());
        }
    }

    public record RouteTarget(String host, int port) {

        public static RouteTarget directRoute() {
            return new RouteTarget("", -1);
        }

        public static RouteTarget proxyRoute(String host, int port) {
            return new RouteTarget(host, port);
        }

        public boolean direct() {
            return StrUtil.isBlank(host) || port <= 0;
        }

        public String key() {
            return direct() ? "DIRECT" : host + ":" + port;
        }

        public String label(ProxyProtocol protocol) {
            if (direct()) {
                return "DIRECT";
            }
            return protocol + " " + host + ":" + port;
        }
    }

    private record RouteProbeResult(
            int index,
            RouteTarget target,
            boolean reachable,
            long elapsedMillis,
            String reason) {
    }
}
