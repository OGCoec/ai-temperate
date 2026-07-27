package com.example.temperate.service.admin.aimodel.icon.image;

/**
 * 定义模型图标允许使用的真实格式、固定扩展名和标准 Content-Type。
 *
 * <p>枚举值同时作为格式策略注册表的稳定业务键，新增或删除格式必须同步调整策略实现。</p>
 */
public enum AiModelIconImageFormat {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
    GIF("gif", "image/gif"),
    ICO("ico", "image/x-icon"),
    AVIF("avif", "image/avif"),
    SVG("svg", "image/svg+xml");

    private final String extension;
    private final String contentType;

    AiModelIconImageFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    public boolean matchesContentType(String actual) {
        if (actual == null) {
            return false;
        }
        String normalized = actual.split(";", 2)[0].trim();
        return contentType.equalsIgnoreCase(normalized)
                || (this == JPEG && "image/jpg".equalsIgnoreCase(normalized))
                || (this == ICO
                        && "image/vnd.microsoft.icon".equalsIgnoreCase(normalized));
    }
}
