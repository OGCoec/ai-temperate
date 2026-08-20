package com.example.temperate.model.auth.enums;

import java.util.Arrays;

/**
 * 表示账号第一次在本站建立时使用的注册来源，并提供与数据库 SMALLINT 的稳定转换。
 *
 * <p>该枚举只记录历史来源，不代表当前允许的登录方式；同一身份后续可以同时绑定 GitHub、Google
 * 并设置本地密码，绑定时不得修改该值。</p>
 */
public enum RegistrationSource {
    STANDARD((short) 0),
    GITHUB((short) 1),
    GOOGLE((short) 2);

    private final short databaseCode;

    RegistrationSource(short databaseCode) {
        this.databaseCode = databaseCode;
    }

    public short databaseCode() {
        return databaseCode;
    }

    /**
     * 为 MyBatis 属性解析提供稳定的 JavaBean 读取入口。
     *
     * @return 数据库存储码
     */
    public short getDatabaseCode() {
        return databaseCode;
    }

    public static RegistrationSource fromDatabaseCode(short databaseCode) {
        return Arrays.stream(values())
                .filter(source -> source.databaseCode == databaseCode)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported registration source code: " + databaseCode));
    }
}
