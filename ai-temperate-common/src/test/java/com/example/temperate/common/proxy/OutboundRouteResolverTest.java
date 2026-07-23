package com.example.temperate.common.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 验证本地出站路由探测器能够区分可达直连目标与可用的 HTTP CONNECT 代理。
 */
class OutboundRouteResolverTest {

    @Test
    void directModeSelectsReachableDirectTarget() throws Exception {
        try (ServerSocket target = new ServerSocket(0)) {
            CompletableFuture<Void> accepted = CompletableFuture.runAsync(() -> {
                try (var ignored = target.accept()) {
                    // 直连探测只要求 TCP 建连成功，不向测试套接字发送业务数据。
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            OutboundRouteResolver resolver =
                    new OutboundRouteResolver(false, "127.0.0.1", "");

            OutboundRouteResolver.RouteSelection selected = resolver.selectRoute(
                    "test",
                    "127.0.0.1",
                    target.getLocalPort(),
                    "127.0.0.1",
                    -1,
                    "direct",
                    1000,
                    OutboundRouteResolver.ProxyProtocol.HTTP_CONNECT);

            accepted.get(2, TimeUnit.SECONDS);
            assertThat(selected.direct()).isTrue();
            assertThat(selected.reachable()).isTrue();
        }
    }

    @Test
    void proxyModeSelectsHttpConnectProxyAfterSuccessfulHandshake() throws Exception {
        try (ServerSocket proxy = new ServerSocket(0)) {
            CompletableFuture<Void> responded = CompletableFuture.runAsync(() -> {
                try (var socket = proxy.accept();
                        var reader = new BufferedReader(new InputStreamReader(
                                socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
                    while (true) {
                        String line = reader.readLine();
                        if (line == null || line.isEmpty()) {
                            break;
                        }
                    }
                    socket.getOutputStream().write(
                            "HTTP/1.1 200 Connection Established\r\n\r\n"
                                    .getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            OutboundRouteResolver resolver =
                    new OutboundRouteResolver(false, "127.0.0.1", "");

            OutboundRouteResolver.RouteSelection selected = resolver.selectRoute(
                    "test",
                    "unresolved.example",
                    443,
                    "127.0.0.1",
                    proxy.getLocalPort(),
                    "proxy",
                    1000,
                    OutboundRouteResolver.ProxyProtocol.HTTP_CONNECT);

            responded.get(2, TimeUnit.SECONDS);
            assertThat(selected.direct()).isFalse();
            assertThat(selected.reachable()).isTrue();
            assertThat(selected.port()).isEqualTo(proxy.getLocalPort());
        }
    }
}
