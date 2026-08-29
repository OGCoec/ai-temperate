package com.example.temperate.service.user.membership;

import com.example.temperate.model.user.entity.UserMembershipQuota;
import java.time.OffsetDateTime;

/**
 * 该服务是来在已锁定额度行上统一判断并启动首次或已过期的额度周期，供所有模型预扣入口共享同一规则。
 *
 * <p>它只修改传入实体，不自行查询或写入数据库；调用方必须在同一事务中完成本次预扣和额度持久化。</p>
 */
public interface MembershipQuotaPeriodActivationService {

    void activateIfDue(
            UserMembershipQuota quota,
            OffsetDateTime firstUsageAt);
}
