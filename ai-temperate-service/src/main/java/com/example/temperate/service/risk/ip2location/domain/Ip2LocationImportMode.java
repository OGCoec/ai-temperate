package com.example.temperate.service.risk.ip2location.domain;

/**
 * 定义批量导入遇到已存在 Key ID 时是保持原值还是显式覆盖。
 */
public enum Ip2LocationImportMode {
    CREATE_ONLY,
    UPSERT
}
