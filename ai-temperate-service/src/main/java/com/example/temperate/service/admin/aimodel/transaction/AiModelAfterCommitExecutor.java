package com.example.temperate.service.admin.aimodel.transaction;

/**
 * 定义 AI 模型数据库事务成功提交后的缓存刷新执行边界。
 *
 * <p>未提交或回滚事务不得触发缓存变更。</p>
 */
public interface AiModelAfterCommitExecutor {

    void execute(Runnable committedAction);
}
