package com.example.temperate.functions.video;

import com.example.temperate.functions.video.dto.VideoProbeResponse;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 在视频流经过 FC 时只捕获有界 moov Box，并从中解析时长、视频轨尺寸和编码，避免临时文件或完整内存副本。
 */
public final class VideoMetadataProbe {

    private static final int MAXIMUM_MOOV_BYTES = 32 * 1024 * 1024;
    private final byte[] header = new byte[16];
    private int headerCount;
    private int headerLength = 8;
    private long remaining;
    private ByteArrayOutputStream moov;
    private byte[] completedMoov;

    public void accept(byte[] bytes, int length) {
        int offset = 0;
        while (offset < length) {
            if (remaining > 0L) {
                int count = (int) Math.min(remaining, length - offset);
                if (moov != null) {
                    moov.write(bytes, offset, count);
                }
                remaining -= count;
                offset += count;
                if (remaining == 0L) {
                    finishBox();
                }
                continue;
            }
            int needed = headerLength - headerCount;
            int count = Math.min(needed, length - offset);
            System.arraycopy(bytes, offset, header, headerCount, count);
            headerCount += count;
            offset += count;
            if (headerCount == 8 && unsignedInt(header, 0) == 1L) {
                headerLength = 16;
            }
            if (headerCount == headerLength) {
                startBox();
            }
        }
    }

    public VideoProbeResponse finish() {
        if (completedMoov == null) {
            throw new IllegalArgumentException("MP4 moov metadata was not found.");
        }
        return parseMoov(completedMoov);
    }

    private void startBox() {
        long size = unsignedInt(header, 0);
        String type = new String(header, 4, 4, StandardCharsets.US_ASCII);
        if (size == 0L) {
            if ("moov".equals(type)) {
                throw new IllegalArgumentException(
                        "Unbounded MP4 moov metadata is not allowed.");
            }
            // 顶层 size=0 表示该 Box 延伸到文件尾；只允许跳过，已捕获的 fast-start moov 仍可用于探测。
            remaining = Long.MAX_VALUE;
            return;
        }
        if (size == 1L) {
            size = ByteBuffer.wrap(header, 8, 8)
                    .order(ByteOrder.BIG_ENDIAN)
                    .getLong();
        }
        if (size < headerLength) {
            throw new IllegalArgumentException("MP4 top-level box size is invalid.");
        }
        if ("moov".equals(type)) {
            if (size > MAXIMUM_MOOV_BYTES) {
                throw new IllegalArgumentException("MP4 moov metadata is too large.");
            }
            moov = new ByteArrayOutputStream(Math.toIntExact(size));
            moov.write(header, 0, headerLength);
        }
        remaining = size - headerLength;
        if (remaining == 0L) {
            finishBox();
        }
    }

    private void finishBox() {
        if (moov != null) {
            completedMoov = moov.toByteArray();
            moov = null;
        }
        headerCount = 0;
        headerLength = 8;
    }

    private static VideoProbeResponse parseMoov(byte[] bytes) {
        Box moovBox = boxAt(bytes, 0, bytes.length);
        Box mvhd = requiredChild(bytes, moovBox, "mvhd");
        long durationMillis = durationMillis(bytes, mvhd);
        for (Box trak : children(bytes, moovBox)) {
            if (!"trak".equals(trak.type())) {
                continue;
            }
            Box mdia = child(bytes, trak, "mdia");
            Box hdlr = mdia == null ? null : child(bytes, mdia, "hdlr");
            if (hdlr == null || !"vide".equals(ascii(bytes, hdlr.payloadStart() + 8))) {
                continue;
            }
            Box tkhd = requiredChild(bytes, trak, "tkhd");
            int width = fixedPointDimension(bytes, tkhd.end() - 8);
            int height = fixedPointDimension(bytes, tkhd.end() - 4);
            String codec = videoCodec(bytes, mdia);
            return new VideoProbeResponse(
                    durationMillis, width, height, codec.toLowerCase(java.util.Locale.ROOT));
        }
        throw new IllegalArgumentException("MP4 video track metadata was not found.");
    }

    private static long durationMillis(byte[] bytes, Box mvhd) {
        int payload = mvhd.payloadStart();
        int version = Byte.toUnsignedInt(bytes[payload]);
        long timescale;
        long duration;
        if (version == 1) {
            timescale = unsignedInt(bytes, payload + 20);
            duration = ByteBuffer.wrap(bytes, payload + 24, 8)
                    .order(ByteOrder.BIG_ENDIAN)
                    .getLong();
        } else {
            timescale = unsignedInt(bytes, payload + 12);
            duration = unsignedInt(bytes, payload + 16);
        }
        if (timescale <= 0L || duration <= 0L) {
            throw new IllegalArgumentException("MP4 duration metadata is invalid.");
        }
        return Math.max(1L, Math.floorDiv(
                Math.multiplyExact(duration, 1_000L), timescale));
    }

    private static String videoCodec(byte[] bytes, Box mdia) {
        Box minf = requiredChild(bytes, mdia, "minf");
        Box stbl = requiredChild(bytes, minf, "stbl");
        Box stsd = requiredChild(bytes, stbl, "stsd");
        int firstEntry = stsd.payloadStart() + 8;
        if (firstEntry + 8 > stsd.end()) {
            throw new IllegalArgumentException("MP4 sample description is missing.");
        }
        return ascii(bytes, firstEntry + 4);
    }

    private static int fixedPointDimension(byte[] bytes, int offset) {
        long raw = unsignedInt(bytes, offset);
        int value = (int) (raw >>> 16);
        if (value <= 0) {
            throw new IllegalArgumentException("MP4 track dimension is invalid.");
        }
        return value;
    }

    private static Box requiredChild(byte[] bytes, Box parent, String type) {
        Box value = child(bytes, parent, type);
        if (value == null) {
            throw new IllegalArgumentException("MP4 " + type + " box is missing.");
        }
        return value;
    }

    private static Box child(byte[] bytes, Box parent, String type) {
        for (Box value : children(bytes, parent)) {
            if (type.equals(value.type())) {
                return value;
            }
        }
        return null;
    }

    private static java.util.List<Box> children(byte[] bytes, Box parent) {
        java.util.ArrayList<Box> values = new java.util.ArrayList<>();
        int offset = parent.payloadStart();
        while (offset + 8 <= parent.end()) {
            Box value = boxAt(bytes, offset, parent.end());
            values.add(value);
            offset = value.end();
        }
        return values;
    }

    private static Box boxAt(byte[] bytes, int offset, int limit) {
        long size = unsignedInt(bytes, offset);
        int headerSize = 8;
        if (size == 1L) {
            if (offset + 16 > limit) {
                throw new IllegalArgumentException("MP4 extended box header is truncated.");
            }
            size = ByteBuffer.wrap(bytes, offset + 8, 8)
                    .order(ByteOrder.BIG_ENDIAN)
                    .getLong();
            headerSize = 16;
        } else if (size == 0L) {
            size = limit - offset;
        }
        if (size < headerSize || size > limit - offset) {
            throw new IllegalArgumentException("MP4 child box is invalid.");
        }
        return new Box(
                ascii(bytes, offset + 4),
                offset,
                offset + headerSize,
                Math.toIntExact(offset + size));
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) {
            throw new IllegalArgumentException("MP4 metadata is truncated.");
        }
        return Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt());
    }

    private static String ascii(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) {
            throw new IllegalArgumentException("MP4 metadata is truncated.");
        }
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }

    /**
     * 表示内存中一个已完成边界校验的 MP4 Box 范围。
     */
    private static final class Box {

        private final String type;
        private final int start;
        private final int payloadStart;
        private final int end;

        private Box(String type, int start, int payloadStart, int end) {
            this.type = type;
            this.start = start;
            this.payloadStart = payloadStart;
            this.end = end;
        }

        private String type() {
            return type;
        }

        private int start() {
            return start;
        }

        private int payloadStart() {
            return payloadStart;
        }

        private int end() {
            return end;
        }
    }
}
