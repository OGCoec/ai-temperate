# 验证码供应商安全诊断日志实施计划

> **执行约束：** 按项目 `AGENTS.md` 仅由根代理实施；第一阶段只写生产代码和测试源码，不运行测试、编译或外部服务检查。

**目标：** 在不记录验证码、完整地址、令牌、原始响应或异常消息的前提下，让 AOP 日志输出供应商调用阶段、失败分类、安全提示和建议动作。

**架构：** 供应商适配器把第三方状态和错误码转换为受控的 `VerificationDeliveryProviderMetadata`；Microsoft Graph 使用独立分类器完成映射；`VerificationDeliveryLoggingAspect` 只读取通用元数据并统一输出，不依赖任何供应商 SDK。

**技术栈：** Java、Spring AOP、Project Reactor、Microsoft Graph Java SDK、SLF4J、JUnit 5、AssertJ。

---

### 任务 1：先定义安全元数据行为测试

**文件：**

- 新建：`ai-temperate-service/src/test/java/com/example/temperate/service/registration/verification/delivery/logging/VerificationDeliveryProviderMetadataTest.java`
- 修改：`ai-temperate-service/src/test/java/com/example/temperate/service/registration/verification/delivery/logging/aspect/VerificationDeliveryLoggingAspectTest.java`

- [ ] 覆盖新增枚举字段、六参数兼容构造器、有界 `Retry-After` 和默认 `unavailable` 行为。
- [ ] 覆盖 AOP 输出新增安全字段且不输出验证码、完整邮箱、Token、原始异常消息。
- [ ] 第一阶段不运行测试；第二阶段获批后执行指定测试观察 RED/GREEN。

### 任务 2：扩展统一安全元数据

**文件：**

- 修改：`ai-temperate-service/src/main/java/com/example/temperate/service/registration/verification/delivery/logging/VerificationDeliveryProviderMetadata.java`

- [ ] 新增受控的 operation、endpoint、failureStage、failureCategory、failureHint、recommendedAction。
- [ ] 新增 explicitFrom、authRefreshAttempted 和有界 retryAfterSeconds。
- [ ] 保留现有六参数构造器，确保 Gmail、Twilio、阿里云和 OAuth 现有调用保持兼容。

### 任务 3：先定义 Microsoft Graph 分类测试

**文件：**

- 新建：`ai-temperate-service/src/test/java/com/example/temperate/service/registration/verification/delivery/util/microsoft/MicrosoftGraphFailureClassifierTest.java`
- 修改：`ai-temperate-service/src/test/java/com/example/temperate/service/registration/verification/delivery/util/microsoft/MicrosoftGraphApiMailUtilTest.java`

- [ ] 覆盖 ErrorInvalidUser、ErrorSendAsDenied、权限、认证、邮箱不可用、429、408、5xx、网络、超时和未知错误。
- [ ] 覆盖 request-id、Retry-After 和 401 刷新一次标识。
- [ ] 明确验证 Graph message/details/响应体不会进入元数据。

### 任务 4：实现 Microsoft Graph 安全分类

**文件：**

- 新建：`ai-temperate-service/src/main/java/com/example/temperate/service/registration/verification/delivery/util/microsoft/MicrosoftGraphFailureClassifier.java`
- 修改：`ai-temperate-service/src/main/java/com/example/temperate/service/registration/verification/delivery/util/microsoft/MicrosoftGraphApiMailUtil.java`

- [ ] 仅依据 HTTP 状态、Graph error.code 和异常类型生成受控分类。
- [ ] 固定记录 send_mail、me_send_mail、显式 From 和最终调用是否进行过一次认证刷新。
- [ ] 只读取第一个 request-id 和有界数字 Retry-After；禁止读取原始错误消息和响应体。
- [ ] 保持 401 内部刷新一次、RabbitMQ 重试分类和业务结果语义不变。

### 任务 5：扩展 AOP 通用输出

**文件：**

- 修改：`ai-temperate-service/src/main/java/com/example/temperate/service/registration/verification/delivery/logging/aspect/VerificationDeliveryLoggingAspect.java`

- [ ] 在 provider_response 事件中输出新增通用字段。
- [ ] 保持 provider_completed 简洁，避免重复诊断内容。
- [ ] 保持 Reactor Context、traceId/messageId 和每次调用三个事件的现有语义。

### 任务 6：第二阶段验证候选命令

- [ ] 获得用户明确授权并说明无外部连接后，运行：

```powershell
mvn -pl ai-temperate-service -am -Dtest=VerificationDeliveryProviderMetadataTest,MicrosoftGraphFailureClassifierTest,VerificationDeliveryLoggingAspectTest,MicrosoftGraphApiMailUtilTest,MicrosoftGraphOAuthTokenUtilTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] 测试仅使用本地 Mock，写入 Maven `target`，不连接 Microsoft Graph、RabbitMQ、Redis、数据库或任何生产服务。
