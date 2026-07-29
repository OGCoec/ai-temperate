package com.example.temperate.service.registration.component.executor;

/**
 * 定义注册数据库事务完成后执行流程清理、身份 Bloom 更新或回滚补偿动作的协调器。
 */
public interface RegistrationAfterCommitExecutor {

    /**
     * 在当前 PostgreSQL 事务完成后执行互斥分支。
     *
     * <p>提交分支负责幂等派生状态更新和流程清理，未提交分支只释放注册完成领取权；调用方必须在数据库
     * 写入前登记回调，避免回滚时遗失补偿动作。</p>
     */
    void execute(Runnable committedAction, Runnable notCommittedAction);
}
