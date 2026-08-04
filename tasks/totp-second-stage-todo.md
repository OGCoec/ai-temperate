# TOTP 第二阶段安全测试清单

## 安全预检

- [ ] 确认测试配置排除真实 PostgreSQL、Redis、RabbitMQ。
- [ ] 记录 Java、Maven、Node、npm 和 Docker 可用性。
- [ ] 确认所有容器测试只使用随机端口和临时数据。

## 聚焦自动化测试

- [ ] 运行 RFC 6238、Base32 和 AES-256-GCM 测试。
- [ ] 运行登录完成边界和 TOTP 登录挑战测试。
- [ ] 运行开启、轮换、关闭以及陈旧 CAS 测试。
- [ ] 新增并运行登录挑战 Redis Lua 集成测试。
- [ ] 新增并运行待确认设置 Redis Lua 集成测试。
- [ ] 新增并运行 step-up proof Redis Lua 集成测试。
- [ ] 扩展并运行 PostgreSQL Mapper TOTP 集成测试。

## Web 与前端

- [ ] 验证 H5 HttpOnly TOTP flow Cookie，不在 JSON 暴露原始挑战。
- [ ] 验证 Android flow token 响应体/请求头边界。
- [ ] 验证第一因子后未提前签发 AT、RT、CSRF。
- [ ] 验证管理接口只使用当前用户身份且响应 `private, no-store`。
- [ ] 运行 `npm run test:auth-totp`。
- [ ] 运行 `npm run test:auth`。
- [ ] 验证二维码由前端本地生成且不调用远程服务。

## 完整回归

- [ ] 在聚焦测试通过后运行 `mvn clean verify`。
- [ ] 检查所有 Testcontainers 已清理。
- [ ] 运行 `mvn dependency:tree`。

## 需要单独批准的项目

- [ ] 用户批准后运行只读 `npm audit --package-lock-only --audit-level=high`。
- [ ] 用户批准隔离测试数据和外部 Chrome 后执行人工端到端流程。
- [ ] 浏览器操作前确认类型为 `extension`；不可用时停止，禁止回退到内置浏览器。

## 报告

- [ ] 分别报告通过、失败、跳过和未执行项目。
- [ ] 报告实际写入并清理的容器、测试数据和构建产物。
- [ ] 确认日志和报告不包含密钥、动态码、Token 或完整个人信息。
