# Membership Loadtest Tomcat 4096 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将会员支付 `loadtest-realtime` Profile 的 Tomcat 接收队列、最大连接数和最大工作线程数默认上限统一为 4096。

**Architecture:** 只修改压测专用 YAML 的三个环境变量默认值及其中文说明，不改变环境变量名，也不影响普通或生产 Profile。数据库、Redis、RabbitMQ 和 JMeter 参数不在本次变更范围。

**Tech Stack:** Spring Boot 3.5、Embedded Tomcat、YAML

---

### Task 1: 更新压测 Tomcat 上限

**Files:**
- Modify: `ai-temperate-web/src/main/resources/application-loadtest-realtime.yml:15-24`

- [ ] **Step 1: 修改三项默认值和中文注释**

```yaml
    # 压测连接接收队列与统一四千零九十六并发上限一致，只影响该受控压测 Profile。
    accept-count: ${MEMBERSHIP_LOADTEST_TOMCAT_ACCEPT_COUNT:4096}
    # 压测同时连接上限统一为四千零九十六，不改变普通及生产 Profile 的连接配置。
    max-connections: ${MEMBERSHIP_LOADTEST_TOMCAT_MAX_CONNECTIONS:4096}
    # Tomcat 工作线程上限按本轮明确的压测合同统一为四千零九十六，仅用于受控本机压测。
    threads:
      # 高线程上限只定义容量边界，正式运行仍必须监控线程、内存、CPU 和请求超时。
      max: ${MEMBERSHIP_LOADTEST_TOMCAT_MAX_THREADS:4096}
```

- [ ] **Step 2: 检查变更范围**

运行只读差异检查：

```powershell
git diff -- ai-temperate-web/src/main/resources/application-loadtest-realtime.yml
```

预期：只有三个默认值和紧邻中文注释发生变化；环境变量名保持不变。

- [ ] **Step 3: 记录未执行验证**

依据项目第一阶段规范，本次不自动运行测试、编译、打包或重启 `6655` 应用；交付时明确说明新配置需要重启后生效。
