package com.example.temperate.service.user.aiconversation.video.impl;

import java.util.Set;

/**
 * 承载 FC 视频搬运边界允许公开的稳定失败码，并把未知上游文本收敛为通用 OSS 搬运失败。
 */
final class AliyunFcVideoTransferFailureException extends RuntimeException {

    private static final String FALLBACK_ERROR_CODE = "OSS_TRANSFER_FAILED";
    private static final Set<String> SAFE_ERROR_CODES = Set.of(
            "SOURCE_OPEN_FAILED",
            "OSS_CREDENTIALS_UNAVAILABLE",
            "OSS_MULTIPART_INIT_FAILED",
            "OSS_PART_UPLOAD_FAILED",
            "OSS_COMPLETE_FAILED",
            "OSS_HEAD_VERIFY_FAILED",
            FALLBACK_ERROR_CODE);

    private final String errorCode;

    private AliyunFcVideoTransferFailureException(String errorCode) {
        super("FC video transfer reported a safe stage failure.");
        this.errorCode = errorCode;
    }

    /**
     * 在不可信 FC 响应进入业务异常链之前执行白名单收敛，禁止透传任意远端异常文本。
     */
    static AliyunFcVideoTransferFailureException from(String untrustedErrorCode) {
        String safeErrorCode = untrustedErrorCode != null
                && SAFE_ERROR_CODES.contains(untrustedErrorCode)
                ? untrustedErrorCode
                : FALLBACK_ERROR_CODE;
        return new AliyunFcVideoTransferFailureException(safeErrorCode);
    }

    String errorCode() {
        return errorCode;
    }
}
