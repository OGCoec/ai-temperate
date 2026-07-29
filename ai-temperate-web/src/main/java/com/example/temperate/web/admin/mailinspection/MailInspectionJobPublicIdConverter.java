package com.example.temperate.web.admin.mailinspection;

import com.example.temperate.common.codec.id.PublicIdCodec;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * 使用统一 PublicIdCodec 校验邮箱检查任务 PathVariable 并向 Controller 提供内部 ID。
 */
@Component
public final class MailInspectionJobPublicIdConverter
        implements Converter<String, MailInspectionJobPublicId> {

    private final PublicIdCodec publicIdCodec;

    public MailInspectionJobPublicIdConverter(PublicIdCodec publicIdCodec) {
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
    }

    @Override
    public MailInspectionJobPublicId convert(String source) {
        return new MailInspectionJobPublicId(
                source, publicIdCodec.decode(source));
    }
}
