package com.example.temperate.web.apichat;

/**
 * 该响应是来稳定输出 OpenAI 兼容错误包络，字段级参数错误可在 param 中指出白名单字段但不得包含请求值。
 */
public record ApiChatErrorResponse(Error error) {

    /** 该记录只承载受控消息和稳定机器码，不允许装入上游正文或异常堆栈。 */
    public record Error(String message, String type, String param, String code) {
    }
}
