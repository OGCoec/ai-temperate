package com.example.temperate.mapper.user.profile;

import com.example.temperate.model.user.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 提供用户资料实体的 MyBatis 写入契约。
 *
 * <p>当前仅负责资料初始落库；身份存在性验证、事务编排和资料业务规则由 service 层负责。</p>
 */
@Mapper
public interface UserProfileMapper {

    int insert(UserProfile profile);
}
