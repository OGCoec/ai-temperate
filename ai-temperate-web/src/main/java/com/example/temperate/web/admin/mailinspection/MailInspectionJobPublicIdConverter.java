package com.example.temperate.web.admin.mailinspection;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * 使用邮件任务专用 Hybrid Codec 校验 22 字符 PathVariable，并只向 Controller 提供规范文本。
 */
@Component
public final class MailInspectionJobPublicIdConverter
        implements Converter<String, MailInspectionJobPublicId> {

    private final HybridBase64UrlCodec jobIdCodec;

    public MailInspectionJobPublicIdConverter(
            HybridBase64UrlCodec jobIdCodec) {
        this.jobIdCodec = Objects.requireNonNull(jobIdCodec);
    }

    @Override
    public MailInspectionJobPublicId convert(String source) {
        jobIdCodec.decode(source);
        return new MailInspectionJobPublicId(source);
    }
}
