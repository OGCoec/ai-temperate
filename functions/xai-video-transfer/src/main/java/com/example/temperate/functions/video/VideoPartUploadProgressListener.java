package com.example.temperate.functions.video;

/**
 * 表示当前 OSS Multipart 分片内部已传输的字节数，由 Relay 汇总为整个视频的全局进度。
 */
@FunctionalInterface
interface VideoPartUploadProgressListener {

    void onProgress(long transferredBytes);
}
