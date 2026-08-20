package com.example.temperate.mapper.typehandler;

import com.example.temperate.model.auth.enums.RegistrationSource;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/**
 * 该处理器是来在 PostgreSQL SMALLINT 注册来源码与 {@link RegistrationSource} 之间执行稳定双向转换，避免通用枚举处理器依赖枚举名称字符串。
 */
@MappedTypes(RegistrationSource.class)
@MappedJdbcTypes(JdbcType.SMALLINT)
public final class RegistrationSourceTypeHandler
        extends BaseTypeHandler<RegistrationSource> {

    @Override
    public void setNonNullParameter(
            PreparedStatement statement,
            int index,
            RegistrationSource parameter,
            JdbcType jdbcType) throws SQLException {
        statement.setShort(index, parameter.databaseCode());
    }

    @Override
    public RegistrationSource getNullableResult(
            ResultSet resultSet,
            String columnName) throws SQLException {
        short value = resultSet.getShort(columnName);
        return resultSet.wasNull() ? null : fromDatabaseCode(value);
    }

    @Override
    public RegistrationSource getNullableResult(
            ResultSet resultSet,
            int columnIndex) throws SQLException {
        short value = resultSet.getShort(columnIndex);
        return resultSet.wasNull() ? null : fromDatabaseCode(value);
    }

    @Override
    public RegistrationSource getNullableResult(
            CallableStatement statement,
            int columnIndex) throws SQLException {
        short value = statement.getShort(columnIndex);
        return statement.wasNull() ? null : fromDatabaseCode(value);
    }

    /**
     * 非法数据库码表示Schema约束或历史数据已经失配，使用稳定SQLState报告转换失败且不暴露原始值。
     */
    private static RegistrationSource fromDatabaseCode(short value) throws SQLException {
        try {
            return RegistrationSource.fromDatabaseCode(value);
        } catch (IllegalArgumentException exception) {
            throw new SQLException(
                    "registration_source contains an unsupported database code",
                    "22023",
                    exception);
        }
    }
}
