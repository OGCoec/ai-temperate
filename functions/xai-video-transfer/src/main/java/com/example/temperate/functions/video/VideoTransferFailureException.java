package com.example.temperate.functions.video;

/**
 * 这个异常携带 FC 内部已经脱敏的阶段码，供 NDJSON 边界输出稳定错误，不携带可供客户端读取的底层消息。
 */
final class VideoTransferFailureException extends RuntimeException {

    private final VideoTransferFailureCode code;

    VideoTransferFailureException(
            VideoTransferFailureCode code,
            Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    VideoTransferFailureCode code() {
        return code;
    }
}
