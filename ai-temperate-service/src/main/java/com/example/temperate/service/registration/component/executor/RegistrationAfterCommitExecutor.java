package com.example.temperate.service.registration.component.executor;

/**
 * 定义注册数据库事务完成后执行外部清理或回滚补偿动作的协调器。
 */
public interface RegistrationAfterCommitExecutor {

    void execute(Runnable committedAction, Runnable notCommittedAction);
}
