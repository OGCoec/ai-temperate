package com.example.temperate.web.admin.mailinspection;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import java.util.Locale;
import java.util.UUID;

/**
 * 校验并封装创建任务使用的规范小写 UUIDv4 幂等键，避免非规范编码产生多个逻辑身份。
 */
public record MailInspectionClientRequestId(String value) {

    public MailInspectionClientRequestId {
        if (value == null || !isCanonicalV4(value)) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_MAIL_INSPECTION_IDEMPOTENCY_KEY_INVALID,
                    "mail inspection idempotency key is invalid");
        }
    }

    public static MailInspectionClientRequestId parse(String value) {
        return new MailInspectionClientRequestId(value);
    }

    private static boolean isCanonicalV4(String value) {
        if (value.length() != 36
                || !value.equals(value.toLowerCase(Locale.ROOT))) {
            return false;
        }
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.version() == 4 && parsed.toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
