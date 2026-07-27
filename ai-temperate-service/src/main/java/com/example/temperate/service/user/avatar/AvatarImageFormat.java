package com.example.temperate.service.user.avatar;

/**
 * 定义普通用户头像允许使用的图片格式及其固定扩展名和 Content-Type。
 */
public enum AvatarImageFormat {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp");

    private final String extension;
    private final String contentType;

    AvatarImageFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    public boolean matchesContentType(String actualContentType) {
        if (actualContentType == null) {
            return false;
        }
        String normalized = actualContentType.split(";", 2)[0].trim();
        return contentType.equalsIgnoreCase(normalized)
                || (this == JPEG && "image/jpg".equalsIgnoreCase(normalized));
    }
}
