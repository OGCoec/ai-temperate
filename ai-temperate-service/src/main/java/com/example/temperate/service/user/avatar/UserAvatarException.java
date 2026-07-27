package com.example.temperate.service.user.avatar;

/**
 * 表示头像预上传、确认或取消过程中可控且不泄露 OSS 内部细节的业务异常。
 */
public final class UserAvatarException extends RuntimeException {

    private final UserAvatarErrorCode code;

    public UserAvatarException(UserAvatarErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public UserAvatarException(UserAvatarErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public UserAvatarErrorCode code() {
        return code;
    }
}
