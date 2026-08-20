package com.example.temperate.mapper.typehandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.auth.enums.RegistrationSource;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;

/**
 * 验证注册来源处理器直接按照数据库 SMALLINT 码读取枚举，不再依赖枚举名称字符串。
 */
class RegistrationSourceTypeHandlerTest {

    private final RegistrationSourceTypeHandler handler =
            new RegistrationSourceTypeHandler();

    @Test
    void readsEverySupportedDatabaseCodeDirectlyFromSmallInt() throws Exception {
        assertThat(handler.getNullableResult(resultSet((short) 0, false), "registration_source"))
                .isEqualTo(RegistrationSource.STANDARD);
        assertThat(handler.getNullableResult(resultSet((short) 1, false), "registration_source"))
                .isEqualTo(RegistrationSource.GITHUB);
        assertThat(handler.getNullableResult(resultSet((short) 2, false), "registration_source"))
                .isEqualTo(RegistrationSource.GOOGLE);
    }

    @Test
    void preservesSqlNullInsteadOfInventingARegistrationSource() throws Exception {
        assertThat(handler.getNullableResult(resultSet((short) 0, true), "registration_source"))
                .isNull();
    }

    @Test
    void rejectsUnsupportedDatabaseCodeWithStableSqlState() {
        assertThatThrownBy(() -> handler.getNullableResult(
                resultSet((short) 3, false), "registration_source"))
                .isInstanceOf(java.sql.SQLException.class)
                .extracting("SQLState")
                .isEqualTo("22023");
    }

    private static ResultSet resultSet(short value, boolean wasNull) {
        return (ResultSet) Proxy.newProxyInstance(
                RegistrationSourceTypeHandlerTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getShort" -> value;
                    case "wasNull" -> wasNull;
                    case "isWrapperFor" -> false;
                    case "unwrap" -> null;
                    case "toString" -> "RegistrationSourceResultSetStub";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
