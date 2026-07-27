package com.example.temperate.service.admin.aimodel.icon.image;

import com.example.temperate.service.admin.aimodel.icon.remote.config.AiModelIconVendor;
import java.util.Objects;

/**
 * 携带一次模型图标验证所允许的 SVG 策略档位和可信厂商来源。
 *
 * <p>调用方不能只传入一个布尔值放宽验证；可信档位必须同时具有服务端解析出的厂商身份，
 * 从而避免把管理员输入的 URL 或请求参数直接当作安全授权。</p>
 */
public record AiModelIconImageValidationContext(
        AiModelIconSvgPolicyProfile svgPolicyProfile,
        AiModelIconVendor trustedVendor) {

    public AiModelIconImageValidationContext {
        Objects.requireNonNull(svgPolicyProfile, "svgPolicyProfile");
        if (svgPolicyProfile == AiModelIconSvgPolicyProfile.TRUSTED_OFFICIAL
                && trustedVendor == null) {
            throw new IllegalArgumentException(
                    "Trusted official SVG validation requires a vendor.");
        }
        if (svgPolicyProfile == AiModelIconSvgPolicyProfile.STRICT
                && trustedVendor != null) {
            throw new IllegalArgumentException(
                    "Strict SVG validation cannot carry a trusted vendor.");
        }
    }

    /**
     * 为本地上传和普通外链创建不允许主动 SVG 内容的严格上下文。
     */
    public static AiModelIconImageValidationContext strict() {
        return new AiModelIconImageValidationContext(
                AiModelIconSvgPolicyProfile.STRICT,
                null);
    }

    /**
     * 为已经由最终主机 Registry 识别的厂商创建受限官方兼容上下文。
     */
    public static AiModelIconImageValidationContext trustedOfficial(
            AiModelIconVendor vendor) {
        return new AiModelIconImageValidationContext(
                AiModelIconSvgPolicyProfile.TRUSTED_OFFICIAL,
                Objects.requireNonNull(vendor, "vendor"));
    }
}
