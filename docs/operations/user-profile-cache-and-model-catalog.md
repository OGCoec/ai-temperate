# 普通用户资料缓存与模型目录

## 用户资料缓存

`GET /api/users/me` 只从 Access Token 建立的 `SessionPrincipal` 取得内部用户 ID。邮箱、手机号、
会员等级、额度和额度周期不进入 Access Token、Refresh Token 或认证 ThreadLocal。

资料读取采用 Cache-Aside：

```text
Redis String 命中
-> 反序列化并校验 schemaVersion
-> 按当前 UTC 时间计算展示额度

Redis 未命中、损坏或不可用
-> 一次联查 userloginidentity + user_profile + user_membership_quota
-> 返回数据库结果
-> 同步、尽力回填 Redis
```

Redis Key 固定为：

```text
ait:<env>:user:profile:v1:enc-id:<22字符Base64URL密文>
```

内部正数 Long ID 先按八字节大端序编码，再使用独立的 AES-256-KWP 密钥确定性加密。密钥由
`USER_PROFILE_CACHE_ID_AES_KEY_BASE64` 提供，解码后必须恰好为三十二字节。密钥轮换后不扫描
Redis；新请求回源并建立新 Key，旧 Key 由五至十五分钟 TTL 自动淘汰。

Value 是带 `schemaVersion=1` 的明文 JSON，不包含内部用户 ID、密码、Token 或设备信息，也不得
写入日志。单条 JSON 超过 10 KiB 告警，超过 64 KiB 拒绝写入。

头像、联系方式、会员等级、额度或周期边界发生数据库变更时，必须在 PostgreSQL 事务提交后调用统一
失效边界。删除使用最多三次 `UNLINK` 尝试，失败不回滚已提交事务，并由 TTL 兜底收敛。

## 额度展示

缓存保存数据库原始 `quota_balance_minor`、`quota_period_started_at` 和
`quota_period_ends_at`，不保存动态投影。

- 当 `quota_period_ends_at > now` 时，展示数据库余额和结束时间。
- 当结束时间为空或 `quota_period_ends_at <= now` 时，按当前会员等级投影满额，并把预计重置时间
  显示为当前 UTC 时间加七天；真正的余额和周期边界仍在首次模型调用的 PostgreSQL 事务中落库。
- `GET /api/users/me` 同时返回余额、总额、已用额度和限制在 0～100 的一位小数使用率，全部额度
  数值都使用十进制字符串，避免 JavaScript 整数精度与浮点舍入问题。

七档默认额度如下，周期统一为首次使用起滚动七天：

| 后端等级 | 前端名称 | 总额度 | 最小单位 |
| --- | --- | ---: | ---: |
| FREE | Free | 50 | 5,000 |
| GO | Go | 500 | 50,000 |
| EDU | Education | 800 | 80,000 |
| TEAM | Team | 1,800 | 180,000 |
| PLUS | Plus | 2,000 | 200,000 |
| PRO | Pro | 10,000 | 1,000,000 |
| MAX | Ultra | 50,000 | 5,000,000 |

部署环境可以通过 `MEMBERSHIP_QUOTA_PERIOD` 和
`MEMBERSHIP_QUOTA_<FREE|GO|EDU|TEAM|PLUS|PRO|MAX>_LIMIT_MINOR` 覆盖配置。周期必须仍为 `P7D`，
七档必须全部存在且额度为正数；缺档、负数或超出 Long 范围时应用拒绝启动。数据库中的 FREE/5000
默认值只作为非应用写入的安全兜底，新注册用户会显式写入当前配置中的 FREE 等级与额度。

该结果只用于个人中心展示。模型调用前的额度判断、预扣和最终结算必须在 PostgreSQL 事务中读取权威
数据，禁止使用资料缓存作为扣费依据。

## AI 会话额度换算与发布

预扣与实际结算统一使用固定换算基数：`80000` 个加权 Token 对应 `1.00` 额度，即 `100 minor`。
预扣计算使用完整估算输入，以及 `ceil(模型最大输出 Token ÷ 3)` 得到的预扣输出量；该三分之一规则
只降低预扣门槛，不修改模型目录中的最大输出，也不修改发给上游的 `maxCompletionTokens`。实际结算仍
分别计算全部非缓存输入、缓存输入和实际上游输出；真实费用低于预扣时退回差额，高于预扣时补扣差额。
两条路径都使用倍率快照和 `BigDecimal`，在完成 `加权 Token × 100 ÷ 80000` 后才向上取整到 minor。
零成本保持为零，任何正成本最低收取 `1 minor`。

额度不足发生在 SSE 建流前时，接口返回 `402 application/json`，错误码固定为
`AI_QUOTA_INSUFFICIENT`；只有已经成功建立事件流后的失败才使用 SSE `error` 事件。

换算基数没有写入 usage 快照。发布新算法前必须暂停新模型调用，并确认数据库中不存在旧算法创建的
`RESERVED` usage。旧预扣必须先完成结算或进入明确的人工对账状态，再按“后端 402 契约、前端双
Accept 与错误文案、恢复模型流量”的顺序发布，避免同一次调用跨越两套算法。

## 普通用户模型目录

普通用户只读接口为：

```text
GET /api/ai-models?pageNum=1&pageSize=20
GET /api/ai-models/{modelPublicId}
```

两个接口都受普通 Access Token 拦截器保护，并返回 `Cache-Control: private, no-store`。详情路由
使用统一十一字符 Base64URL 公共 ID；禁用或不存在模型统一返回 404。

模型目录读取现有 AES-256-GCM 聚合快照。缓存缺失时一次查询最多五百个启用模型，再一次批量查询全部
能力并回填快照；空列表同样写入空快照，避免穿透。分页只在该有界快照上按 `ai_model.id ASC` 切片。
`cachedInputRatio` 是厂商 usage 中 `cached_tokens` 对应的本地计费倍率，与 Redis 命中无关。

目录接口不负责模型调用和实时可用性判定。实际调用前仍必须通过模型可用性 Service 重新检查
PostgreSQL 权威状态。
