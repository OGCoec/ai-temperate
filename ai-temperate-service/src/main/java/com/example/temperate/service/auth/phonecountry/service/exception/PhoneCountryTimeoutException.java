package com.example.temperate.service.auth.phonecountry.service.exception;

/**
 * 表示单次手机号国家识别超过服务端允许执行期限的受控异常。
 *
 * <p>用途：只携带稳定的超时分类供 Web 层映射，不包含客户端 IP、文件路径或底层提供者信息。</p>
 */
public final class PhoneCountryTimeoutException extends RuntimeException {

    public PhoneCountryTimeoutException() {
        super("Phone country lookup exceeded the configured deadline.");
    }
}
