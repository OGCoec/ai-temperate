package com.example.temperate.service.admin.aimodel.icon.image.svg;

/**
 * 定义可信官方 SVG 内嵌 data URI 栅格图片的完整解码验证边界。
 *
 * <p>实现只允许 PNG、JPEG 和 WebP 的 Base64 data URI，并复用现有格式策略执行真实像素解码。</p>
 */
public interface AiModelIconSvgEmbeddedRasterValidator {

    /**
     * 解码并验证单个受支持的 Base64 图片 Data URI。
     */
    AiModelIconSvgEmbeddedRasterMetadata validate(String dataUri);
}
