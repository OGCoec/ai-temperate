package com.example.temperate.service.user.avatar.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.avatar.AvatarActivation;
import com.example.temperate.service.user.avatar.AvatarImageFormat;
import com.example.temperate.service.user.avatar.AvatarImageValidator;
import com.example.temperate.service.user.avatar.AvatarObjectKeyFactory;
import com.example.temperate.service.user.avatar.AvatarObjectMetadata;
import com.example.temperate.service.user.avatar.UserAvatarErrorCode;
import com.example.temperate.service.user.avatar.UserAvatarException;
import com.example.temperate.service.user.avatar.UserAvatarObjectStorage;
import com.example.temperate.service.user.avatar.UserAvatarPersistenceService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证头像确认编排的幂等、串行持久化与 OSS 补偿边界，并确保流程不依赖 Redis。
 */
class UserAvatarServiceImplTest {

    private static final long USER_ID = 10001L;
    private static final String PUBLIC_ID = "AAAAAAAAJxE";
    private static final String IMAGE_ID = "0123456789_abcdefghijklm";
    private static final String TEMP_KEY =
            "ai-temperate/user/temp/AAAAAAAAJxE/" + IMAGE_ID + ".webp";
    private static final String TEMP_PNG_KEY =
            "ai-temperate/user/temp/AAAAAAAAJxE/" + IMAGE_ID + ".png";
    private static final String FINAL_PNG_KEY =
            "ai-temperate/user/AAAAAAAAJxE/" + IMAGE_ID + ".png";
    private static final String FINAL_PNG_URL =
            "https://ihaveaplan.oss-us-west-1.aliyuncs.com/" + FINAL_PNG_KEY;

    private UserAvatarObjectStorage storage;
    private UserAvatarPersistenceService persistence;
    private UserAvatarServiceImpl service;

    @BeforeEach
    void setUp() {
        storage = mock(UserAvatarObjectStorage.class);
        persistence = mock(UserAvatarPersistenceService.class);
        service = new UserAvatarServiceImpl(
                storage,
                persistence,
                new AvatarImageValidator(),
                new AvatarObjectKeyFactory(),
                Clock.fixed(Instant.parse("2026-07-26T12:00:00Z"), ZoneOffset.UTC),
                () -> IMAGE_ID);
    }

    @Test
    void createsPrivateUserBoundPresignedPut() {
        when(storage.generatePresignedPutUrl(TEMP_KEY, "image/webp"))
                .thenReturn(new UserAvatarObjectStorage.PresignedPut(
                        "https://signed.example/upload",
                        Instant.parse("2026-07-26T12:10:00Z"),
                        Map.of(
                                "Content-Type", "image/webp",
                                "x-oss-object-acl", "private",
                                "x-oss-forbid-overwrite", "true")));

        var result = service.createPreupload(USER_ID, PUBLIC_ID, AvatarImageFormat.WEBP, 428716L);

        assertThat(result.preuploadId()).isEqualTo(IMAGE_ID);
        assertThat(result.uploadHeaders()).containsEntry("x-oss-object-acl", "private");
        assertThat(result.expiresAt())
                .isEqualTo(Instant.parse("2026-07-26T12:10:00Z"));
        verify(storage).generatePresignedPutUrl(TEMP_KEY, "image/webp");
    }

    @Test
    void acceptsExactFiveMegabyteDeclarationAndRejectsOneByteMore() {
        when(storage.generatePresignedPutUrl(TEMP_KEY, "image/webp"))
                .thenReturn(new UserAvatarObjectStorage.PresignedPut(
                        "https://signed.example/upload",
                        Instant.parse("2026-07-26T12:10:00Z"),
                        Map.of()));

        assertThat(service.createPreupload(
                        USER_ID,
                        PUBLIC_ID,
                        AvatarImageFormat.WEBP,
                        UserAvatarServiceImpl.MAX_FILE_BYTES))
                .isNotNull();
        assertThatThrownBy(() -> service.createPreupload(
                        USER_ID,
                        PUBLIC_ID,
                        AvatarImageFormat.WEBP,
                        UserAvatarServiceImpl.MAX_FILE_BYTES + 1L))
                .isInstanceOf(UserAvatarException.class)
                .extracting("code")
                .isEqualTo(UserAvatarErrorCode.INVALID_INPUT);
    }

    @Test
    void rejectsConfirmationWhenUserProfileIsUnavailable() {
        assertThatThrownBy(() -> service.confirm(
                        USER_ID, PUBLIC_ID, IMAGE_ID, AvatarImageFormat.WEBP))
                .isInstanceOf(UserAvatarException.class)
                .extracting("code")
                .isEqualTo(UserAvatarErrorCode.PROFILE_UNAVAILABLE);
        verify(storage, never()).headObject(any());
        verify(storage, never()).copyObjectToPublic(any(), any(), any());
    }

    @Test
    void cancelDeletesOnlyTheAuthenticatedUsersExactTemporaryObject() {
        service.cancel(USER_ID, PUBLIC_ID, IMAGE_ID, AvatarImageFormat.JPEG);

        verify(storage).deleteObject(
                "ai-temperate/user/temp/AAAAAAAAJxE/" + IMAGE_ID + ".jpg");
    }

    @Test
    void cancelIsIdempotentWhenTemporaryObjectAlreadyDisappeared() {
        doThrow(new UserAvatarObjectStorage.ObjectNotFoundException(
                        new IllegalStateException()))
                .when(storage)
                .deleteObject(TEMP_KEY);

        assertThatCode(() -> service.cancel(
                        USER_ID,
                        PUBLIC_ID,
                        IMAGE_ID,
                        AvatarImageFormat.WEBP))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsOversizedHeadAndDeletesTemporaryObject() {
        when(persistence.findCurrent(USER_ID))
                .thenReturn(new AvatarActivation(USER_ID, null));
        when(storage.headObject(TEMP_KEY))
                .thenReturn(new AvatarObjectMetadata(
                        UserAvatarServiceImpl.MAX_FILE_BYTES + 1L,
                        "image/webp"));

        assertThatThrownBy(() -> service.confirm(
                        USER_ID, PUBLIC_ID, IMAGE_ID, AvatarImageFormat.WEBP))
                .isInstanceOf(UserAvatarException.class)
                .extracting("code")
                .isEqualTo(UserAvatarErrorCode.INVALID_IMAGE);

        verify(storage).deleteObject(TEMP_KEY);
        verify(storage, never()).copyObjectToPublic(any(), any(), any());
    }

    @Test
    void deletesNewFinalObjectWhenDatabaseActivationFails() throws Exception {
        byte[] image = validPngBytes();
        when(persistence.findCurrent(USER_ID))
                .thenReturn(new AvatarActivation(USER_ID, null));
        when(storage.headObject(TEMP_PNG_KEY))
                .thenReturn(new AvatarObjectMetadata((long) image.length, "image/png"));
        when(storage.downloadObjectBytesBounded(
                        TEMP_PNG_KEY, UserAvatarServiceImpl.MAX_FILE_BYTES))
                .thenReturn(image);
        when(storage.copyObjectToPublic(TEMP_PNG_KEY, FINAL_PNG_KEY, "image/png"))
                .thenReturn(FINAL_PNG_URL);
        doThrow(new IllegalStateException("simulated database failure"))
                .when(persistence)
                .activate(USER_ID, FINAL_PNG_URL);

        assertThatThrownBy(() -> service.confirm(
                        USER_ID, PUBLIC_ID, IMAGE_ID, AvatarImageFormat.PNG))
                .isInstanceOf(UserAvatarException.class)
                .extracting("code")
                .isEqualTo(UserAvatarErrorCode.PERSISTENCE_FAILED);

        verify(storage).deleteObject(FINAL_PNG_KEY);
    }

    private static byte[] validPngBytes() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
