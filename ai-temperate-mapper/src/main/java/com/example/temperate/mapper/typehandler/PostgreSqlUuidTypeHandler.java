package com.example.temperate.mapper.typehandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * 该处理器是来在 Java UUID 与 PostgreSQL UUID（JDBC OTHER）之间执行双向转换，解决 MyBatis 3.5.14 没有内置 UUID TypeHandler 导致 Mapper XML 无法解析的问题。
 */
public final class PostgreSqlUuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(
            PreparedStatement statement,
            int index,
            UUID parameter,
            JdbcType jdbcType) throws SQLException {
        statement.setObject(index, parameter, Types.OTHER);
    }

    @Override
    public UUID getNullableResult(
            ResultSet resultSet,
            String columnName) throws SQLException {
        return toUuid(resultSet.getObject(columnName));
    }

    @Override
    public UUID getNullableResult(
            ResultSet resultSet,
            int columnIndex) throws SQLException {
        return toUuid(resultSet.getObject(columnIndex));
    }

    @Override
    public UUID getNullableResult(
            CallableStatement statement,
            int columnIndex) throws SQLException {
        return toUuid(statement.getObject(columnIndex));
    }

    /**
     * PostgreSQL 驱动通常直接返回 UUID；字符串分支只兼容代理驱动或测试替身，非法值统一转换为不含原始数据的 JDBC 异常。
     */
    private static UUID toUuid(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException exception) {
            throw new SQLException("PostgreSQL UUID column contains an invalid value", exception);
        }
    }
}
