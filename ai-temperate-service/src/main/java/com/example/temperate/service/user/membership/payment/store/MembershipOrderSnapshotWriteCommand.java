package com.example.temperate.service.user.membership.payment.store;

import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import java.util.Objects;

/**
 * 该内部命令是来把写入模式与 PostgreSQL 快照绑定，供双 lane 协调器构造严格有序的混合 Pipeline。
 */
public record MembershipOrderSnapshotWriteCommand(
        MembershipOrderSnapshotWriteMode mode,
        MembershipOrderSnapshot snapshot) {

    public MembershipOrderSnapshotWriteCommand {
        mode = Objects.requireNonNull(mode, "mode must not be null");
        snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
    }
}
