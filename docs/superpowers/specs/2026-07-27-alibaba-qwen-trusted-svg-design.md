# 通义千问可信官方 SVG 厂商扩展设计

## 目标

在现有七家可信官方 SVG 厂商基础上新增通义千问（Qwen），使最终响应主机为
`qwen.ai` 或其真实 DNS 子域的远程 SVG 可以使用现有 `TRUSTED_OFFICIAL`
安全策略。

## 信任边界

- 新增稳定厂商枚举值 `ALIBABA_QWEN`。
- 默认可信根主机仅为 `qwen.ai`。
- 点边界匹配允许 `qwen.ai` 和 `assets.qwen.ai` 等真实子域。
- `qwen.ai.attacker.test`、`evil-qwen.ai`、IP 字面量、协议、端口、路径和通配符
  继续被拒绝。
- 不信任整个 `aliyun.com`、`alicdn.com` 或其他阿里共享 CDN。
- 重定向后仍以最终响应主机选择策略，初始 URL 主机不能提前获得信任。

## 代码与配置

- `AiModelIconVendor` 增加 `ALIBABA_QWEN`。
- `AiModelIconRemoteSvgProperties.TrustedHosts` 增加非空 `qwen` 主机列表。
- `AiModelIconTrustedOriginRegistry` 注册 Qwen 主机集合，并继续执行跨厂商重叠检查。
- 主 YAML 增加 `AI_MODEL_ICON_TRUSTED_QWEN_HOSTS`，默认值为 `qwen.ai`。
- 测试 YAML 使用相同的隔离主机配置，不执行真实网络请求。
- 同步现有可信厂商设计文档，将厂商数量从七家更新为八家。

## 数据流

```text
远程图片最终响应主机
→ qwen.ai 点边界匹配
→ ALIBABA_QWEN
→ TRUSTED_OFFICIAL SVG 策略
→ 通过安全验证后仅保存最终 URL
```

该流程不复制外部图片到 OSS，数据库中的 `object_key` 仍为 `NULL`。

## 测试

- 先增加失败测试，证明当前 Registry 无法识别 `qwen.ai`。
- 验证 `qwen.ai` 和 `assets.qwen.ai` 映射为 `ALIBABA_QWEN`。
- 验证伪造后缀和相似域名仍返回严格策略。
- 验证 Qwen 与其他厂商配置重叠时启动失败。
- 定向测试通过后，再运行相关 Spring 测试配置绑定验证。

## 不在本次范围

- 不新增数据库迁移、API 字段、缓存结构或 OSS 路径。
- 不预置通义千问图标记录。
- 不访问真实 Qwen URL，也不修改 Clash、Cloudflare 或前端 CSP。
- 不为阿里共享 CDN 建立宽泛信任规则。
