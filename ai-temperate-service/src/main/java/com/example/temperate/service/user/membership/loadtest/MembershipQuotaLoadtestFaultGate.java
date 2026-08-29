package com.example.temperate.service.user.membership.loadtest;

/**
 * 该服务是来为 W16 武装一次会员额度预扣事务回滚故障，并以单调计数证明故障确实在事务内触发。
 *
 * <p>它不负责修改额度或订单事实；未武装时是无副作用快速返回，只有受控回环入口可以武装。</p>
 */
public interface MembershipQuotaLoadtestFaultGate {

    long armReservationRollback(long userId);

    void failAfterReservationIfArmed(long userId);

    boolean reservationRollbackArmed();

    long reservationRollbackFailureCount();

    /**
     * 为普通 Profile 和既有直接构造测试提供关闭实现，禁止测试故障改变生产控制流。
     */
    static MembershipQuotaLoadtestFaultGate disabled() {
        return new MembershipQuotaLoadtestFaultGate() {
            @Override
            public long armReservationRollback(long userId) {
                throw new IllegalStateException("Membership quota loadtest is disabled.");
            }

            @Override
            public void failAfterReservationIfArmed(long userId) {
                // 关闭实现不得参与额度事务。
            }

            @Override
            public boolean reservationRollbackArmed() {
                return false;
            }

            @Override
            public long reservationRollbackFailureCount() {
                return 0L;
            }
        };
    }
}
