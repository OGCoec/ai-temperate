package com.example.temperate.mapper.user.profile;

import com.example.temperate.model.user.entity.UserProfile;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供用户资料实体的 MyBatis 写入契约。
 *
 * <p>当前仅负责资料初始落库；身份存在性验证、事务编排和资料业务规则由 service 层负责。</p>
 */
@Mapper
public interface UserProfileMapper {

    int insert(UserProfile profile);

    /** 一次插入一批受控毫秒边界测试资料；批次上限由服务层固定为 500 条。 */
    int batchInsertBoundaryFixtures(@Param("profiles") List<UserProfile> profiles);

    /** 按固定测试身份 ID 批量读取资料，供模板完整性校验使用。 */
    List<UserProfile> findByLoginIdentityIds(
            @Param("loginIdentityIds") List<Long> loginIdentityIds);
}
