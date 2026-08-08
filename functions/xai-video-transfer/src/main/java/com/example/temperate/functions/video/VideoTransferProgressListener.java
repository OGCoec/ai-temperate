package com.example.temperate.functions.video;

/**
 * 接收视频分片写入 OSS 的真实字节变化；总长度未知时以空值表达，禁止由调用方猜测百分比。
 */
interface VideoTransferProgressListener {

    void uploading(long transferredBytes, Long totalBytes);

    void verifying(long transferredBytes, Long totalBytes);

    static VideoTransferProgressListener noOp() {
        return new VideoTransferProgressListener() {
            @Override
            public void uploading(long transferredBytes, Long totalBytes) {
            }

            @Override
            public void verifying(long transferredBytes, Long totalBytes) {
            }
        };
    }
}
