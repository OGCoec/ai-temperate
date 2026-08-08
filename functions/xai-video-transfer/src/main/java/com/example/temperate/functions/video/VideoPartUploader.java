package com.example.temperate.functions.video;

/**
 * 定义流式搬运器逐分片写入、完成校验与失败补偿的最小端口，使媒体读取不依赖具体 OSS SDK。
 */
public interface VideoPartUploader {

    void uploadPart(long partNumber, byte[] bytes, int length);

    /**
     * 默认兼容旧实现；生产 OSS 上传器覆写该方法以交付分片内真实字节回调。
     */
    default void uploadPart(
            long partNumber,
            byte[] bytes,
            int length,
            VideoPartUploadProgressListener progressListener) {
        uploadPart(partNumber, bytes, length);
    }

    StoredObject complete(long expectedBytes);

    void compensate();

    /**
     * 表示对象存储完成后经 HEAD 校验的非敏感对象元数据。
     */
    final class StoredObject {

        private final String objectKey;
        private final long byteSize;
        private final String contentType;
        private final String eTag;

        public StoredObject(
                String objectKey,
                long byteSize,
                String contentType,
                String eTag) {
            this.objectKey = objectKey;
            this.byteSize = byteSize;
            this.contentType = contentType;
            this.eTag = eTag;
        }

        public String objectKey() {
            return objectKey;
        }

        public long byteSize() {
            return byteSize;
        }

        public String contentType() {
            return contentType;
        }

        public String eTag() {
            return eTag;
        }
    }
}
