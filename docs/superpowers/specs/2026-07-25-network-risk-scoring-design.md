# 网络风险评分与 PreAuth 请求拦截设计

## 1. 文档状态

- 设计日期：2026-07-25。
- 当前状态：第一阶段代码与测试代码已交付，尚未执行验证。
- 当前阶段：只交付代码，不执行测试、构建、外部服务连接或生产部署。

## 2. 目标

本设计为普通用户和管理员 API 建立一套共用算法、作用域隔离的网络风险门。风险门依赖 Cloudflare Worker 提供并由后端验签的可信网络信息，以及 Redis 中的短期 PreAuth 状态，不新增关系型数据库风控画像。

设计目标：

1. 登录前和登录后的业务 API 请求都必须验证对应作用域的 PreAuth 风险令牌。
2. 当前 IP 与上一次可信 IP 相同时走快速放行，不重复查询 IP 信誉，也不计算不可能旅行。
3. 当前 IP 发生变化时，实时计算本次最终分数，并立即作出 `ALLOW`、`CHALLENGE` 或 `BLOCK` 决策。
4. 最终分数只存在于当前请求的内存上下文中，不写入 PreAuth Redis Hash。
5. 不可能旅行的基础扣分是本次事件的布尔惩罚，不得按历史次数重复累加。
6. Cloudflare Challenge 成功后必须建立有限期可信结果，防止同一网络切换反复进入人机验证。

## 3. 适用范围

### 3.1 普通用户作用域

- 外部 Host：`niko000o.site`。
- API 路径：普通用户 `/api/**`，但不包含 `/api/admin/**`。
- H5 PreAuth Cookie：使用普通用户专属的 Host-only、Secure、HttpOnly Cookie。
- Redis 命名空间：普通用户专属 PreAuth 命名空间。

### 3.2 管理员作用域

- 外部 Host：`admin.niko000o.site`。
- API 路径：`/api/admin/**`。
- H5 PreAuth Cookie：使用管理员专属的 Host-only、Secure、HttpOnly Cookie。
- Redis 命名空间：管理员专属 PreAuth 命名空间。

普通用户和管理员共用评分算法，但令牌、Cookie、Redis Key 和 Challenge 状态必须完全隔离。

## 4. 拦截器位置

网络风险门使用 Spring MVC `HandlerInterceptor`，不作为新的业务 Servlet Filter。

请求顺序固定为：

```text
Cloudflare WAF
→ Cloudflare Worker
→ EdgeProxySignatureFilter
→ Spring Security Filter Chain
→ NetworkRiskInterceptor
→ 其他 MVC HandlerInterceptor
→ Controller
```

`NetworkRiskInterceptor` 必须设置为所有 MVC 拦截器中的最高优先级。现有同为最高优先级的 MVC 拦截器必须后移，确保实际顺序稳定，不能依靠相同 Order 值下的注册偶然顺序。

网络风险拦截器不得运行在 `EdgeProxySignatureFilter` 之前。IP、国家、ASN、经纬度和外部 Host 只有在边缘签名验证成功后才可信，禁止直接信任客户端自行传入的代理头。

## 5. 每次请求的验证范围

登录前和登录后的业务 API 请求都要验证 PreAuth 风险令牌，包括 Access Token 尚未过期的普通业务请求。

每次请求至少执行：

1. 解析普通用户或管理员风险作用域。
2. 读取对应的 PreAuth 风险令牌。
3. 验证令牌摘要对应的 Redis 状态存在且未过期。
4. 验证 `deviceDigest` 与当前设备安装标识一致。
5. 读取可信边缘提供的当前 IP，并计算 `currentIpDigest`。
6. 比较 `currentIpDigest` 与 `lastTrustedIpDigest`。

以下端点必须采用专门处理，避免创建风险令牌与校验风险令牌形成循环依赖：

- `OPTIONS` 预检请求。
- 健康检查。
- Cookie Scope 迁移端点。
- PreAuth 风险令牌首次 Bootstrap 端点。
- Challenge 展示和验证端点。

这些端点不是无条件绕过安全校验。Bootstrap 服务仍需验证设备标识和可信边缘上下文；Challenge
端点仍需经过 Cloudflare WAF、Worker v2 边缘签名，并由后端原子校验一次性引用、设备、作用域、
当前 IP 和决策上下文。应用不把 Cloudflare Challenge 当成 Turnstile，也不调用 Siteverify。

## 6. 同 IP 快速路径

满足以下条件时，当前请求不进行 IP 信誉查询和不可能旅行计算：

```text
PreAuth风险令牌有效
且设备绑定有效
且作用域有效
且currentIpDigest == lastTrustedIpDigest
且不存在当前有效的临时阻断
```

此时网络变化风险结果为：

```text
ALLOW
```

同 IP 快速放行只表示跳过“IP变化评分”，不表示绕过 Spring Security、CSRF、Access Token、管理员会话、业务授权、限流或 Cloudflare WAF。

## 7. IP 发生变化时的实时评分

### 7.1 基础 IP 信誉分

内部统一使用 `0～100` 分，分数越高表示 IP 越可信。

第三方供应商返回的是风险分时，统一转换为内部信誉分：

```text
ipTrustScore =
100 - clamp(providerRiskScore, 0, 100)
```

供应商缺少风险分时不得伪造第三方分数，而是继续降级。IP2Location 只返回部分地理信息时先保留
这些字段，再由 iPing 补充分数；仍然缺失的地理字段由本地 IP2Location LITE DB11 BIN 补充，
最终没有风险分时使用短 TTL 的默认信誉分 60。

`networkType` 与 `scoreIncludesNetworkRisk` 一起保留。供应商分数已经包含代理、VPN、TOR、
数据中心或 ASN 风险时，第一版不再按网络类型重复扣分；网络类型只用于受控审计和后续算法版本。

### 7.2 基础 IP 硬阻断

基础 IP 信誉分是独立硬门槛：

```text
ipTrustScore < 40
→ BLOCK
```

边界要求：

```text
ipTrustScore == 40
→ 不直接BLOCK，继续计算本次最终分数
```

基础 IP 信誉分低于 40 时，不再依靠不可能旅行扣分决定是否阻断，因为当前 IP 本身已经达到硬阻断条件。

## 8. 不可能旅行判定

不可能旅行必须由上一次可信位置与当前可信边缘位置之间的距离和时间共同决定，不能只根据国家或 ASN 变化判断。

```text
distanceKm =
Haversine(
    lastTrustedLatitude,
    lastTrustedLongitude,
    currentLatitude,
    currentLongitude
)

elapsedHours =
currentObservedAt - lastTrustedObservedAt

speedKmh =
distanceKm / elapsedHours
```

第一版判定条件：

```text
当前IP确实变化
且距离不少于200公里
且有效时间差不超过24小时
且估算速度不少于340米/秒（1224公里/小时）
→ impossibleTravel = true
```

坐标、可信观察时间或其他必要数据缺失时：

```text
impossibleTravel = UNKNOWN
```

`UNKNOWN` 不得伪造为命中，也不得扣除不可能旅行分数；此时只根据当前 IP 信誉及已存在的时间窗口信号决策。

## 9. 不可能旅行扣分不变量

### 9.1 本次命中只扣一次 30 分

不可能旅行基础惩罚只取决于本次 IP 切换是否命中：

```text
impossibleTravelPenalty =
impossibleTravel == true ? 30 : 0
```

该 30 分是布尔惩罚，不是历史累计惩罚。

禁止以下计算：

```text
impossibleTravelPenalty =
impossibleTravelCount * 30
```

因此：

- 第一次命中，本次扣 30 分。
- 第二次命中，本次仍然只扣 30 分。
- 第十次命中，本次仍然只扣 30 分。
- 历史已经扣过的 30 分不得永久写入状态，也不得在后续请求继续累加。

### 9.2 短时间超过五次额外统一扣 20 分

使用固定 30 分钟时间窗口，统计同一个 PreAuth 风险主体发生的不同不可能旅行事件。

同一事件使用以下上下文摘要去重：

```text
decisionContextDigest =
HMAC(
    riskScope
    + deviceDigest
    + lastTrustedIpDigest
    + currentIpDigest
)
```

同一个 `decisionContextDigest` 因前端重试、并发请求或 Challenge 页面跳转重复到达时，只能计数一次。

次数附加惩罚：

```text
frequentImpossibleTravelPenalty =
impossibleTravelCountWithin30Minutes > 5 ? 20 : 0
```

边界要求：

- 30 分钟内第 1～5 个不同事件：不产生次数附加扣分。
- 第 6 个不同事件开始：本次额外扣 20 分。
- 第 7、第 8 或更多事件：次数附加扣分仍然只有 20 分。
- 禁止使用 `(count - 5) * 20` 继续叠加。
- HTTP 请求次数、Challenge 页面刷新次数和同一请求重试次数都不能直接作为该计数。

该规则最终表达为：

```text
本次不可能旅行命中
→ 固定扣30分

30分钟内不同不可能旅行事件超过5次
→ 再固定扣20分

无论历史次数多大
→ 本次由不可能旅行产生的总扣分最多50分
```

## 10. 最终分数与决策

在基础 IP 信誉分不低于 40 的前提下，本次最终分数实时计算：

```text
finalScore =
clamp(
    ipTrustScore
    - impossibleTravelPenalty
    - frequentImpossibleTravelPenalty,
    0,
    100
)
```

决策阈值：

| 条件 | 决策 |
| --- | --- |
| `finalScore >= 60` | `ALLOW` |
| `20 <= finalScore < 60` | `CHALLENGE` |
| `finalScore < 20` | `BLOCK` |

边界要求：

```text
finalScore == 60
→ ALLOW

finalScore == 20
→ CHALLENGE

finalScore == 19
→ BLOCK
```

典型示例：

| 场景 | 计算 | 决策 |
| --- | --- | --- |
| 干净 IP，本次不可能旅行 | `90 - 30 = 60` | `ALLOW` |
| 一般 IP，本次不可能旅行 | `70 - 30 = 40` | `CHALLENGE` |
| 较低但未命中基础硬阻断，本次不可能旅行 | `60 - 30 = 30` | `CHALLENGE` |
| 多次异常切换 | `60 - 30 - 20 = 10` | `BLOCK` |
| 基础 IP 信誉过低 | `39 < 40` | 直接 `BLOCK` |

## 11. 实时计算与 Redis 边界

以下结果只存在于当前请求内存，不写入 PreAuth Redis Hash：

- `ipTrustScore`
- `impossibleTravelPenalty`
- `frequentImpossibleTravelPenalty`
- `finalScore`

PreAuth Redis Hash只保存后续判断需要的事实：

```text
schemaVersion
sessionType
sessionRefDigest
deviceDigest
lastSeenAt
lastTrustedIpDigest
lastTrustedCountry
lastTrustedAsn
lastTrustedLatitude
lastTrustedLongitude
lastTrustedObservedAt
lastDecision
lastDecisionAt
lastDecisionContextDigest
challengeVerifiedUntil
```

第三方 IP 信誉结果可以使用独立、短 TTL 的 IP 摘要缓存，防止每次相同新 IP 到达都调用外部 API。该缓存不是 PreAuth 主体的最终分数，也不能替代每次请求的实时最终分数计算。

30 分钟不可能旅行计数必须存放在独立的短期 Redis 窗口 Key 中，并通过原子操作保证：

1. 同一 `decisionContextDigest` 只登记一次。
2. 首次登记时计数加一。
3. 去重标记和计数窗口具有明确 TTL。
4. 并发请求不能把同一事件重复计数。

## 12. 可信 IP 的更新规则

`lastTrustedIpDigest` 以及对应国家、ASN、经纬度和时间只能在以下情况下更新：

1. 新 IP 的实时决策为 `ALLOW`。
2. 新 IP 的实时决策为 `CHALLENGE`，并且 Cloudflare WAF Challenge 成功放行后，后端原子消费
   与该作用域、PreAuth、设备、当前 IP 和决策上下文绑定的一次性引用。

以下结果不得更新可信 IP：

- `BLOCK`
- 尚未完成的 `CHALLENGE`
- Challenge失败
- Challenge超时
- IP信誉服务异常
- 可信边缘上下文缺失或验签失败

不满足该约束会导致被阻断的新 IP 被错误晋升为可信 IP，从而在下一次请求命中同 IP 快速放行。

## 13. 防止 Cloudflare Challenge 无限循环

Challenge流程：

```text
新IP实时决策为CHALLENGE
→ 创建5分钟一次性Challenge引用
→ 前端保存不含敏感数据的当前路由
→ 使用window.location.assign进行顶层导航
→ 完成Cloudflare WAF Managed/Interactive Challenge
→ 请求继续进入Worker和后端
→ 同一个Redis Lua原子消费引用并将新IP更新为可信IP
→ 写入有限期challengeVerifiedUntil
→ 后端303跳转到同域固定完成页
→ 完成页用location.replace返回原path、query和hash
```

Challenge成功后的下一次请求满足：

```text
currentIpDigest == lastTrustedIpDigest
```

因此进入同 IP 快速路径，不会再次计算同一段不可能旅行。

前端必须遵守：

- 同一页面风险上下文最多自动进入一次 Challenge，防止配置错误形成导航循环。
- 修改密码、支付、验证码提交等非幂等请求不得自动重放。
- 不在 `sessionStorage`、URL或日志中保存密码、验证码、Token或完整请求体。
- 保存页面位置时只保存经过白名单校验的站内路由、Query和Fragment。

Cloudflare `cf_clearance` 用于避免边缘 WAF 重复挑战；Redis中的可信 IP和 `challengeVerifiedUntil` 用于避免应用风险门重复挑战。两者职责不同，不能互相替代。

## 14. PreAuth 风险令牌生命周期

### 14.1 登录前

- 页面在认证或会话恢复请求前获取 PreAuth 风险令牌。
- 匿名状态 TTL 为 30 分钟。
- 有效的登录前业务操作可以刷新匿名 TTL。

### 14.2 登录成功

- 登录成功时旋转 PreAuth 风险令牌，禁止沿用登录前原值。
- `sessionType` 更新为普通用户 Refresh Session 或管理员 Session。
- `sessionRefDigest` 只保存会话令牌的 HMAC 摘要。
- 登录状态目标 TTL 为 6 小时。

### 14.3 登录后

- Access Token未过期时，每次业务 API 请求仍验证 PreAuth 风险令牌并比较 IP。
- 普通 Refresh Session 续期时，Refresh Session、用户索引与绑定的 PreAuth TTL 必须由同一个
  Redis Lua 校验和续期；管理员 Session 字段 TTL 与管理员 PreAuth Key TTL 同样由一个 Lua
  原子续期。
- 本项目的 Refresh Token 原文不在每次刷新时轮换，因此 `sessionRefDigest` 保持绑定同一个固定
  Refresh Token；登录成功时旋转的是 PreAuth Token。
- 浏览器因 428 恢复流程重新创建匿名 PreAuth 时，只有同设备的现有 Refresh Session 或管理员
  Session 先在同一个 Redis Lua 中验证成功，才允许把匿名状态晋升为已认证并恢复六小时 TTL；
  客户端不能单独声明 `sessionType` 或 `sessionRefDigest`。
- 原始 Access Token、Refresh Token和管理员 Session Token不得写入风险 Hash。

### 14.4 部署模式

- `DISABLED` 的 Bootstrap 只返回关闭状态，不创建依赖临时进程密钥的 Redis 状态。
- `OBSERVE` 在上下文完整时计算和记录，但缺少 PreAuth、v2 网络上下文或计算基础设施时放行旧协议。
- `ENFORCE` 才对缺少 PreAuth、风险 Challenge 与动态 Block 返回 428/403；任意模式均不能接受伪造的
  边缘签名头。

## 15. 响应语义

### 15.1 ALLOW

- 继续执行后续 MVC 拦截器和 Controller。
- 不向前端暴露内部评分和第三方信誉细节。

### 15.2 CHALLENGE

- 返回稳定业务错误码，例如 `RISK_CHALLENGE_REQUIRED`。
- 返回一次性、短 TTL、绑定设备和风险上下文的 Challenge ID。
- 不返回 `ipTrustScore`、扣分项或阈值，防止攻击者调试绕过。

### 15.3 BLOCK

- 返回 HTTP 403 和稳定业务错误码，例如 `RISK_BLOCKED`。
- 临时阻断存在明确到期时间时可以返回 `Retry-After`。
- 不创建新的身份 Cookie、会话或业务 Token。

## 16. 基础设施异常策略

1. 可信边缘验签失败：拒绝请求。
2. PreAuth 风险令牌不存在或绑定错误：ENFORCE 拒绝或要求重新 Bootstrap；OBSERVE 只记录迁移缺口，
   不静默创建匿名可信状态。
3. Redis不可用：认证和高敏感操作失败关闭。
4. IP信誉服务不可用：
   - 同一已可信 IP仍按快速路径处理。
   - 新 IP 按 `IP2Location → iPing → 本地 BIN → 默认信誉分60` 的固定链路降级。
   - 本地 BIN 或默认60分只缓存10分钟，允许较快重新尝试第三方。
5. 地理坐标缺失：不计算不可能旅行，不伪造速度。

## 17. 非目标

本阶段明确不实现：

- 持久化设备信誉分。
- 持久化用户风险画像。
- PostgreSQL 风控事件表。
- 按不可能旅行历史次数重复累计每次 30 分。
- 根据每一个HTTP重试请求累计风险次数。
- 自动将后端Redis决策同步为当前请求的Cloudflare WAF Managed Challenge。
- 在浏览器中暴露原始IP、完整Token、完整设备标识或详细扣分原因。

## 18. 验收标准

1. 同一可信 IP 的连续请求不调用不可能旅行计算。
2. 单次不可能旅行无论历史发生过多少次，本次基础扣分始终是30分。
3. 30分钟内五个不同不可能旅行事件没有次数附加扣分。
4. 第六个不同事件开始额外扣20分，但第七个以后不会继续叠加20分。
5. 同一 IP 切换上下文并发请求十次，只登记一个不可能旅行事件。
6. `ipTrustScore < 40` 时直接 `BLOCK`。
7. `finalScore == 20` 时为 `CHALLENGE`，`finalScore < 20` 时才为 `BLOCK`。
8. `finalScore` 不出现在 PreAuth Redis Hash、Cookie、响应或日志中。
9. Challenge成功后新 IP晋升为可信 IP，后续请求不会重复Challenge。
10. Challenge失败或Block不会覆盖上一次可信 IP。
11. 普通用户和管理员使用独立令牌、Cookie、Redis Key和Challenge上下文。
12. 所有业务 API在登录前和登录后均执行风险令牌校验，明确例外端点除外。
