package com.example.temperate.web.edgeproxy;

/**
 * 定义浏览器请求对可信 Cloudflare Worker 签名的强制程度。
 *
 * <p>{@link #OPTIONAL} 只用于切换窗口，生产稳定态必须使用 {@link #REQUIRED}；本地开发使用
 * {@link #DISABLED}，避免本机 H5 依赖外部边缘服务。</p>
 */
public enum EdgeProxyMode {
    DISABLED,
    OPTIONAL,
    REQUIRED
}
