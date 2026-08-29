# H-PRE Terminal Provider Trade Verdict Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 H-P1/H-PR 的退款兜底终态按正确的流水归属通过验收，同时保留严格分组和应用终态的完整性检查。

**Architecture:** 只调整现有 PostgreSQL 临时裁决视图的 `CASE` 顺序与分支条件，不修改业务代码或运行参数。契约测试继续通过静态片段约束生成 SQL 的安全边界。

**Tech Stack:** PowerShell 7、PostgreSQL SQL、现有静态契约测试

---

### Task 1: 固化退款终态流水契约

**Files:**
- Modify: `loadtest/scripts/tests/Test-MembershipMillisecondBoundaryContract.ps1`
- Modify: `loadtest/sql/verify-membership-millisecond-boundary-wave.sql`

- [ ] **Step 1: 写入失败契约**

在 SQL 片段断言中加入以下约束：

```powershell
'callback_provider_trade_no IS NULL OR length(callback_provider_trade_no) > 128'
"boundary_verdict_mode = 'TERMINAL_OUTCOME'"
"callback_resolution = 'REFUND_REQUIRED'"
'order_provider_trade_no IS NOT NULL'
'REFUND_PROVIDER_TRADE_NOT_CLEARED'
```

- [ ] **Step 2: 运行契约测试确认旧实现失败**

按项目规范，本轮不自动执行。用户确认第二阶段验证后运行：

```powershell
& 'C:\Users\damn\Desktop\ai-temperate-main\loadtest\scripts\tests\Test-MembershipMillisecondBoundaryContract.ps1'
```

预期旧实现因缺少退款流水清空裁决而失败。

- [ ] **Step 3: 实现最小 SQL 修复**

将统一的订单流水前缀校验拆为回调流水前缀校验，并按模式与最终回调裁决检查订单流水：

```sql
WHEN callback_provider_trade_no IS NULL OR length(callback_provider_trade_no) > 128
     OR callback_provider_trade_no NOT LIKE group_code || '-MMB-%'
    THEN 'INVALID_PROVIDER_TRADE_PREFIX'
WHEN callback_resolution = 'REFUND_REQUIRED'
     AND order_provider_trade_no IS NOT NULL
    THEN 'REFUND_PROVIDER_TRADE_NOT_CLEARED'
WHEN callback_resolution = 'APPLIED'
     AND order_provider_trade_no IS DISTINCT FROM callback_provider_trade_no
    THEN 'PROVIDER_TRADE_MISMATCH'
```

- [ ] **Step 4: 第二阶段验证**

按项目规范，本轮不自动执行。用户确认后先运行契约测试，再从 H-P1 续跑验证 5000 个回调全部收敛且退款终态不再被误判。
