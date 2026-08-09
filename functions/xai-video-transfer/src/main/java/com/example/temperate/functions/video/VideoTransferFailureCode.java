package com.example.temperate.functions.video;

/**
 * 这个枚举负责定义 FC 视频搬运可返回的阶段错误码，隔离底层异常，避免源地址、凭据和 SDK 消息跨函数边界泄露。
 */
enum VideoTransferFailureCode {
    SOURCE_OPEN_FAILED,
    OSS_CREDENTIALS_UNAVAILABLE,
    OSS_MULTIPART_INIT_FAILED,
    OSS_PART_UPLOAD_FAILED,
    OSS_COMPLETE_FAILED,
    OSS_HEAD_VERIFY_FAILED,
    OSS_TRANSFER_FAILED;

    /**
     * 只允许把内部受控异常转换为稳定错误码；未知异常统一降级到通用码，防止把异常文本写入 NDJSON。
     */
    static String safeCode(Throwable failure) {
        if (failure instanceof VideoTransferFailureException) {
            return ((VideoTransferFailureException) failure).code().name();
        }
        return OSS_TRANSFER_FAILED.name();
    }

    /**
     * 写入响应前再次校验字符串白名单，防止未来调用方误把异常消息当作错误码。
     */
    static String safeCode(String candidate) {
        if (candidate == null) {
            return OSS_TRANSFER_FAILED.name();
        }
        try {
            return valueOf(candidate).name();
        } catch (IllegalArgumentException ignored) {
            return OSS_TRANSFER_FAILED.name();
        }
    }
}
