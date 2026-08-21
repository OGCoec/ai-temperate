package com.example.temperate.mapper.typehandler;

import com.example.temperate.model.auth.enums.MembershipTier;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/**
 * 该类型处理器是来把会员等级枚举与数据库 SMALLINT 稳定编码互转，订单表只允许持久化 GO 至 MAX。
 */
@MappedTypes(MembershipTier.class)
@MappedJdbcTypes(JdbcType.SMALLINT)
public final class MembershipTierTypeHandler extends BaseTypeHandler<MembershipTier> {

    @Override
    public void setNonNullParameter(
            PreparedStatement statement,
            int index,
            MembershipTier parameter,
            JdbcType jdbcType) throws SQLException {
        if (parameter == MembershipTier.FREE) {
            throw new SQLException("FREE cannot be persisted as a membership payment target.");
        }
        statement.setInt(index, parameter.ordinal());
    }

    @Override
    public MembershipTier getNullableResult(ResultSet resultSet, String columnName)
            throws SQLException {
        return read(resultSet.getInt(columnName), resultSet.wasNull());
    }

    @Override
    public MembershipTier getNullableResult(ResultSet resultSet, int columnIndex)
            throws SQLException {
        return read(resultSet.getInt(columnIndex), resultSet.wasNull());
    }

    @Override
    public MembershipTier getNullableResult(CallableStatement statement, int columnIndex)
            throws SQLException {
        return read(statement.getInt(columnIndex), statement.wasNull());
    }

    private static MembershipTier read(int code, boolean wasNull) throws SQLException {
        if (wasNull) {
            return null;
        }
        MembershipTier[] values = MembershipTier.values();
        if (code < 1 || code >= values.length) {
            throw new SQLException("Unsupported membership tier code: " + code);
        }
        return values[code];
    }
}
