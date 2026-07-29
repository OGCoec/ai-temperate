package com.example.temperate.service.admin.mailinspection.domain;

import java.util.Objects;

/**
 * 承载不含原始凭证的HMAC请求指纹，只用于判断同一幂等键是否对应完全相同的创建请求。
 */
public record MailInspectionRequestFingerprint(String value) {

    public MailInspectionRequestFingerprint {
        Objects.requireNonNull(value, "value must not be null");
        if (!value.matches("^[A-Za-z0-9_-]{43}$")) {
            throw new IllegalArgumentException(
                    "mail inspection request fingerprint is invalid");
        }
    }

    @Override
    public String toString() {
        return "MailInspectionRequestFingerprint[protected]";
    }
}
