package com.example.temperate.mapper.user.avatar;

import com.example.temperate.model.user.domain.UserAvatarState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供当前用户头像状态的读取、行锁读取和精确更新数据库契约。
 *
 * <p>对象复制和删除不属于 Mapper 职责；调用方必须在 Service 事务中使用锁定查询后更新。</p>
 */
@Mapper
public interface UserAvatarMapper {

    UserAvatarState findByUserId(@Param("userId") long userId);

    UserAvatarState findByUserIdForUpdate(@Param("userId") long userId);

    int updateAvatar(
            @Param("userId") long userId,
            @Param("avatarUrl") String avatarUrl);
}
