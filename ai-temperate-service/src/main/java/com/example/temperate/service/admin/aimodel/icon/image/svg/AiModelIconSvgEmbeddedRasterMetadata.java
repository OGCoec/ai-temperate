package com.example.temperate.service.admin.aimodel.icon.image.svg;

import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;

/**
 * 描述可信官方 SVG 内嵌栅格图片完成真实解码后的安全计量结果。
 *
 * <p>该结果只用于累计数量、字节和像素预算，不保留或再次复制 Base64 内容。</p>
 */
public record AiModelIconSvgEmbeddedRasterMetadata(
        AiModelIconImageFormat format,
        int width,
        int height,
        int decodedBytes) {
}
