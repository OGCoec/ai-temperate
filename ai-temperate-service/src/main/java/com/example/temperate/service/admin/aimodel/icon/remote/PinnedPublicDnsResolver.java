package com.example.temperate.service.admin.aimodel.icon.remote;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

/**
 * 为远程图标 HTTP 客户端提供只返回公网地址的 DNS 解析结果。
 *
 * <p>校验发生在连接管理器实际解析主机的时刻，而不是只在请求前预查一次，从而避免
 * DNS 重绑定把已校验域名切换到内网地址。</p>
 */
public final class PinnedPublicDnsResolver implements DnsResolver {

    private final DnsResolver delegate;

    public PinnedPublicDnsResolver() {
        this(SystemDefaultDnsResolver.INSTANCE);
    }

    PinnedPublicDnsResolver(DnsResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        try {
            return PublicNetworkAddressPolicy.requirePublic(delegate.resolve(host));
        } catch (IllegalArgumentException exception) {
            UnknownHostException failure =
                    new UnknownHostException("host is not allowed for remote image validation");
            failure.initCause(exception);
            throw failure;
        }
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }
}
