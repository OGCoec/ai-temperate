# 会员订单创建 40K/80K 测试验收报告实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于六个已经完成的正式 Run，交付一份可审计、可复跑、可接管的 40K/80K 会员订单创建容量测试验收报告。

**Architecture:** 以区段级 JSON/CSV 为第一事实来源，先构建 40K/80K 八段证据矩阵，再编写统一验收结论、问题复盘和操作手册。报告必须把业务区段全部 PASS 与四段续跑触发的顶层聚合假阴性 FAIL 分层表达。

**Tech Stack:** Markdown、PowerShell 静态解析、Git、本地压测 JSON/CSV 证据。

---

## 文件结构

- 设计依据：`docs/superpowers/specs/2026-08-28-membership-40k-80k-acceptance-report-design.md`
- 实施计划：`docs/superpowers/plans/2026-08-28-membership-40k-80k-acceptance-report-plan.md`
- 最终报告：`docs/handoffs/2026-08-28-membership-order-create-40k-80k-capacity-acceptance-report.md`

## Task 1：核对正式证据和计算口径

- [x] 从六个正式 Run 的区段级 `verdict.json` 与 `golden-baseline-comparison.json` 提取请求数、回调数、墙钟、QPS、P50、P95、P99、合同裁决和黄金裁决。
- [x] 核对每个 `scenario-orders.csv` 的数据行数与 5K/10K 区段规模一致。
- [x] 重新计算 40K/80K 墙钟总和、有效 QPS、平均 QPS、最低 QPS、最高 QPS和扩容变化百分比。
- [x] 读取两个后四段续跑 Run 的顶层 `verdict.json`，保留失败码、来源阶段、脚本行和原始错误消息。
- [x] 记录 80K 最终数据库人工核验：80,000 订单、80,000 回调、无缺失、无重复，`APPLIED=49,963`、`REFUND_REQUIRED=30,037`。

## Task 2：编写最终验收正文

- [x] 在报告开头给出功能、性能、数据一致性、黄金能力和顶层聚合状态五层结论。
- [x] 写明 40K/80K 测试规模、八区段边界语义和固定基础设施参数。
- [x] 为 40K 与 80K 各写一张八段结果表，列出证据 Run ID。
- [x] 写一张横向对比表，解释墙钟增长 94.368% 和有效 QPS 增长 2.898%。
- [x] 分别说明 PostgreSQL、Redis、RabbitMQ、日志与 Navicat 查询上限的验收边界。

## Task 3：编写问题复盘

- [x] 按“现象、根因、修复/处置、验证、最终影响、遗留风险”统一模板记录业务状态机问题。
- [x] 记录 Redis Pipeline、调度器、索引、Hikari、RabbitMQ Channel/消费者等性能与容量调整。
- [x] 记录 JMeter 时间精度、合法延迟消息门禁、80K Token 页码、四段聚合报告器等测试基础设施问题。
- [x] 记录外部强制结束、旧心跳、消费者消失、只读查询与破坏性清理的运行接管问题。
- [x] 明确本轮没有 etcd 故障证据，不虚构不存在的问题。

## Task 4：编写命令和接管手册

- [x] 从已批准设计复制从 `E-P1` 启动完整八段的单行 PowerShell 命令。
- [x] 复制从 `E-AR` 续跑五段和从 `H-P1` 续跑四段的单行 PowerShell 命令。
- [x] 复制每两秒心跳监听和 RabbitMQ 会员队列只读查询命令。
- [x] 增加参数说明、执行前提、续跑证据规则和故障接管顺序。
- [x] 明确 `purge_queue` 不属于常规测试流程，禁止把查询与清理混淆。

## Task 5：静态验证与提交

- [x] 核对 16 个正式区段条目、120,000 个请求/回调总量和逐段 PASS。
- [x] 通过 PowerShell Parser 静态解析全部 `powershell` 代码块，不执行其中命令。
- [x] 检查错误转义、HTML 实体、占位符、Markdown 围栏和本地相对链接。
- [x] 核对最终报告同时保留“八段业务 PASS”和“顶层聚合假阴性 FAIL”。
- [x] 确认 Git diff 只包含本计划和最终报告，然后单独提交文档。

## 完成标准

1. 两个规模均有完整八段性能表和可追溯 Run ID。
2. 所有数字可由现有 JSON/CSV 复算，报告不依赖主观截图判断。
3. 五张命令卡可由 PowerShell Parser 成功解析，且没有执行副作用。
4. 报告能指导完整重跑、E-AR 续跑、H-P1 续跑、心跳监控和只读 RabbitMQ 排查。
5. 不修改、不暂存、不提交用户已有的业务代码与测试改动。
