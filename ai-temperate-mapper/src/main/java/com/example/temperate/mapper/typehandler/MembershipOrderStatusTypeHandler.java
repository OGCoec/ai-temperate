package com.example.temperate.mapper.typehandler;

import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/**
 * 该类型处理器是来在 MyBatis 边界把会员订单状态的稳定 SMALLINT 编码与领域枚举相互转换。
 */
@MappedTypes(MembershipOrderStatus.class)
@MappedJdbcTypes(JdbcType.SMALLINT)
public final class MembershipOrderStatusTypeHandler
        extends BaseTypeHandler<MembershipOrderStatus> {

    @Override
    public void setNonNullParameter(
            PreparedStatement statement,
            int index,
            MembershipOrderStatus parameter,
            JdbcType jdbcType) throws SQLException {
        statement.setInt(index, parameter.code());
    }

    @Override
    public MembershipOrderStatus getNullableResult(ResultSet resultSet, String columnName)
            throws SQLException {
        return read(resultSet.getInt(columnName), resultSet.wasNull());
    }

    @Override
    public MembershipOrderStatus getNullableResult(ResultSet resultSet, int columnIndex)
            throws SQLException {
        return read(resultSet.getInt(columnIndex), resultSet.wasNull());
    }

    @Override
    public MembershipOrderStatus getNullableResult(
            CallableStatement statement,
            int columnIndex) throws SQLException {
        return read(statement.getInt(columnIndex), statement.wasNull());
    }

    private static MembershipOrderStatus read(int code, boolean wasNull) {
        return wasNull ? null : MembershipOrderStatus.fromCode(code);
    }
}
