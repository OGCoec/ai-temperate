package com.example.temperate.web.edgeproxy;

/**
 * 定义 H5 与 Android 请求对可信 Cloudflare Worker 签名的强制程度。
 *
 * <p>{@link #OPTIONAL} 只允许完全不带边缘头的切换期请求，生产稳定态必须使用
 * {@link #REQUIRED} 强制所有受保护路径验签；本地开发使用 {@link #DISABLED}，避免本机客户端
 * 依赖外部边缘服务。</p>
 */
public enum EdgeProxyMode {
    DISABLED,
    OPTIONAL,
    REQUIRED
}
