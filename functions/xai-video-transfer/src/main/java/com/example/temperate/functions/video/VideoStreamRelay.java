package com.example.temperate.functions.video;

import com.example.temperate.functions.video.dto.VideoProbeResponse;
import com.example.temperate.functions.video.dto.VideoTransferResponse;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * 用固定 8 MiB 缓冲区顺序读取一次来源流并逐分片写 OSS，同时计算摘要和解析有界 MP4 元数据。
 */
public final class VideoStreamRelay {

    public static final int PART_BYTES = 8 * 1024 * 1024;

    public VideoTransferResponse relay(
            VideoSourceStream.OpenedVideo source,
            VideoPartUploader uploader,
            long maximumBytes) throws IOException {
        Objects.requireNonNull(source);
        Objects.requireNonNull(uploader);
        byte[] buffer = new byte[PART_BYTES];
        MessageDigest digest = sha256();
        VideoMetadataProbe probe = new VideoMetadataProbe();
        long total = 0L;
        long partNumber = 1L;
        try {
            while (true) {
                int length = readPart(source.stream(), buffer);
                if (length == 0) {
                    break;
                }
                if (partNumber == 1L) {
                    requireMp4Ftyp(buffer, length);
                }
                total = Math.addExact(total, length);
                if (total > maximumBytes) {
                    throw new IOException("Video source exceeds the configured limit.");
                }
                digest.update(buffer, 0, length);
                probe.accept(buffer, length);
                // SDK 调用返回后才复用缓冲区；每个 part 固定一次上传且 OperationOptions 禁止重试。
                uploader.uploadPart(partNumber++, buffer, length);
            }
            if (total <= 0L) {
                throw new IOException("Video source is empty.");
            }
            VideoProbeResponse metadata = probe.finish();
            VideoPartUploader.StoredObject stored = uploader.complete(total);
            return new VideoTransferResponse(
                    stored.objectKey(),
                    stored.byteSize(),
                    stored.contentType(),
                    metadata.durationMillis(),
                    metadata.width(),
                    metadata.height(),
                    metadata.videoCodec(),
                    stored.eTag(),
                    toLowerHex(digest.digest()));
        } catch (RuntimeException | IOException failure) {
            try {
                uploader.compensate();
            } catch (RuntimeException compensationFailure) {
                failure.addSuppressed(compensationFailure);
            }
            throw failure;
        }
    }

    public VideoProbeResponse probe(
            InputStream stream,
            long maximumBytes) throws IOException {
        byte[] buffer = new byte[PART_BYTES];
        VideoMetadataProbe probe = new VideoMetadataProbe();
        long total = 0L;
        boolean first = true;
        while (true) {
            int length = readPart(stream, buffer);
            if (length == 0) {
                break;
            }
            if (first) {
                requireMp4Ftyp(buffer, length);
                first = false;
            }
            total = Math.addExact(total, length);
            if (total > maximumBytes) {
                throw new IOException("Video source exceeds the configured limit.");
            }
            probe.accept(buffer, length);
        }
        if (total <= 0L) {
            throw new IOException("Video source is empty.");
        }
        return probe.finish();
    }

    private static int readPart(InputStream stream, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = stream.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            offset += read;
        }
        return offset;
    }

    private static void requireMp4Ftyp(byte[] bytes, int length) throws IOException {
        if (length < 12
                || bytes[4] != 'f'
                || bytes[5] != 't'
                || bytes[6] != 'y'
                || bytes[7] != 'p') {
            throw new IOException("Video source is not an MP4 ftyp stream.");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String toLowerHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] encoded = new char[Math.multiplyExact(bytes.length, 2)];
        for (int index = 0; index < bytes.length; index++) {
            int value = Byte.toUnsignedInt(bytes[index]);
            encoded[index * 2] = digits[value >>> 4];
            encoded[index * 2 + 1] = digits[value & 0x0F];
        }
        return new String(encoded);
    }
}
