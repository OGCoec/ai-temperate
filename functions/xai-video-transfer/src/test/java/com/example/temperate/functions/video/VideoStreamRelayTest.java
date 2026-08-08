package com.example.temperate.functions.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 FC 搬运器固定使用 8 MiB 分片，并在格式或大小失败时调用对象存储补偿端口。
 */
final class VideoStreamRelayTest {

    @Test
    void rejectsNonMp4BeforeUploadingFirstPart() {
        FakeUploader uploader = new FakeUploader();
        VideoStreamRelay relay = new VideoStreamRelay();

        assertThrows(IOException.class, () -> relay.relay(
                source(new byte[32]), uploader, 1_024L));

        assertTrue(uploader.partLengths.isEmpty());
        assertTrue(uploader.compensated);
    }

    @Test
    void uploadsEachFixedBufferOnceAndCompensatesWhenMetadataIsMissing() {
        byte[] bytes = new byte[VideoStreamRelay.PART_BYTES + 12];
        putFtyp(bytes);
        FakeUploader uploader = new FakeUploader();
        VideoStreamRelay relay = new VideoStreamRelay();

        assertThrows(IllegalArgumentException.class, () -> relay.relay(
                source(bytes), uploader, bytes.length));

        assertEquals(
                List.of(VideoStreamRelay.PART_BYTES, 12),
                uploader.partLengths);
        assertTrue(uploader.compensated);
    }

    @Test
    void stopsBeforeUploadingPartThatWouldExceedMaximumBytes() {
        byte[] bytes = new byte[VideoStreamRelay.PART_BYTES + 12];
        putFtyp(bytes);
        FakeUploader uploader = new FakeUploader();
        VideoStreamRelay relay = new VideoStreamRelay();

        assertThrows(IOException.class, () -> relay.relay(
                source(bytes), uploader, VideoStreamRelay.PART_BYTES));

        assertEquals(List.of(VideoStreamRelay.PART_BYTES), uploader.partLengths);
        assertTrue(uploader.compensated);
    }

    private static VideoSourceStream.OpenedVideo source(byte[] bytes) {
        return new VideoSourceStream.OpenedVideo(
                new ByteArrayInputStream(bytes),
                "video/mp4",
                bytes.length);
    }

    private static void putFtyp(byte[] bytes) {
        bytes[0] = 0;
        bytes[1] = 0;
        bytes[2] = 0;
        bytes[3] = 12;
        bytes[4] = 'f';
        bytes[5] = 't';
        bytes[6] = 'y';
        bytes[7] = 'p';
        bytes[8] = 'i';
        bytes[9] = 's';
        bytes[10] = 'o';
        bytes[11] = 'm';
    }

    private static final class FakeUploader implements VideoPartUploader {
        private final List<Integer> partLengths = new ArrayList<>();
        private boolean compensated;

        @Override
        public void uploadPart(long partNumber, byte[] bytes, int length) {
            assertEquals(partLengths.size() + 1L, partNumber);
            partLengths.add(length);
        }

        @Override
        public StoredObject complete(long expectedBytes) {
            return new StoredObject(
                    "ai/video/test.mp4",
                    expectedBytes,
                    "video/mp4",
                    "etag");
        }

        @Override
        public void compensate() {
            compensated = true;
        }
    }
}
