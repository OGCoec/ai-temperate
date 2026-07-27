package com.example.temperate.web.auth.api;

/**
 * 表示 Web 边界已经确认由客户端输入造成、可以安全映射为统一 400 响应的受控异常。
 *
 * <p>该异常不携带原始输入或内部校验细节；服务层不应使用它表示状态不变量或基础设施故障。</p>
 */
public final class WebInvalidInputException extends RuntimeException {

    public WebInvalidInputException() {
        super("Web request input is invalid.");
    }
}
