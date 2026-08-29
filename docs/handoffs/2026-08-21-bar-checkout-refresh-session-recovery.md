# BAR 支付页刷新后“令牌缺失”问题交接与修复计划

> **已被取代（2026-08-22）：** 本文记录的是 BAR 旧 `pay_url + #token + /session` 协议下的历史故障分析。BAR 已硬切为浏览器签名 `POST /api/pay/submit`、HttpOnly Checkout Cookie 和干净 `/pay/{tradeNo}`，旧协议不再可用。禁止继续实施本文第 6～11 节的旧修复方案；请改用 [`2026-08-22-bar-checkout-submit-protocol-migration.md`](./2026-08-22-bar-checkout-submit-protocol-migration.md)。本文仅作为历史证据保留。

## 1. 交接结论

本问题发生在只读对照项目：

```text
C:\Users\damn\Desktop\ai-temperate-recall-test
```

主项目：

```text
C:\Users\damn\Desktop\ai-temperate-main
```

已经正确接收 BAR `POST /api/pay/create` 返回的完整 `pay_url`，校验其中的 HTTPS Origin、支付路径、有效期和 `#token=...` Fragment 后，通过浏览器顶层导航原样进入 BAR 页面。首次页面能够成功调用 BAR Checkout Session 接口，足以证明主项目没有在跳转前丢失 Fragment token。

已确认的直接缺陷位于 BAR 前端 `frontend/src/payment.ts`：页面首次兑换 token 成功后主动删除 Fragment，但刷新时仍把 Fragment token 当作唯一启动凭据，没有使用已经建立的 HttpOnly Checkout Cookie 恢复订单。因此本问题不需要修改 `ai-temperate-main` 的创建支付、支付跳转、异步回调或主动查询链路。

## 2. 用户可见现象

首次从商户页面点击“立即购买”时：

1. 浏览器进入 `https://ihaveagoddamnplan.com/pay/{tradeNo}#token=...`。
2. BAR 页面显示金额、二维码、订单信息和“SSE 在线”。
3. DevTools 可以看到 `bar_checkout_session` HttpOnly Cookie。

在同一标签页按刷新按钮或 `Ctrl+R` 后：

1. 地址保持为 `https://ihaveagoddamnplan.com/pay/{tradeNo}`，Fragment 已不存在。
2. 页面立即显示“支付页令牌缺失”。
3. 页面提示“请从商户下单接口返回的完整 pay_url 进入”。
4. 刷新启动过程没有先调用 `GET /api/checkout/orders/{tradeNo}` 尝试恢复已有 Cookie 会话。

本问题与管理员是否登录、商户异步回调是否成功、SSE 是否曾连接成功无直接关系。

## 3. 已确认的出现规律

### 3.1 必现条件

满足以下条件时可以稳定复现：

1. 使用完整 `pay_url` 第一次进入页面。
2. `POST /api/checkout/orders/{tradeNo}/session` 返回成功。
3. 前端执行 `history.replaceState`，从地址栏删除 `#token=...`。
4. 当前文档重新加载，包括刷新、复制清理后的 URL 再打开、关闭后通过历史记录重新进入。

重新加载后 `location.hash` 为空，当前实现会在任何 API 恢复请求之前直接渲染错误页。

### 3.2 不触发条件

首次使用完整、未过期的 `pay_url` 进入且页面没有重新加载时，页面可以正常运行。SSE、降级轮询和倒计时都在已经挂载的内存状态中工作，不会重新执行启动阶段的 Fragment 强制检查。

### 3.3 与回调链路的关系

BAR 向商户发送的 `GET /api/payment/bar/notify` 是服务端到服务端的 HMAC 回调，不使用浏览器 Checkout Cookie。即使支付页刷新后报错，BAR 后端仍可能正常发送回调，主项目也仍可能验签、主动查询并接受支付事实。

因此“真实回调成功”和“支付页刷新恢复失败”可以同时发生，二者并不矛盾。

## 4. 证据链

### 4.1 主项目保留并原样跳转完整支付地址

主项目前端：

```text
C:\Users\damn\Desktop\ai-temperate-main\fornted\common\user\membership-payment-state.js
C:\Users\damn\Desktop\ai-temperate-main\fornted\pages\account\membership-plans.vue
```

`validateBarPaymentTarget` 明确要求 `#token=<Base64URL>`，验证通过后 `window.location.assign(target)` 使用完整字符串导航。首次 BAR Session 请求成功也从运行结果证明 Fragment 到达了 BAR 页面。

### 4.2 BAR 前端删除 Fragment 后无法刷新恢复

BAR 前端：

```text
C:\Users\damn\Desktop\ai-temperate-recall-test\frontend\src\payment.ts
```

当前启动逻辑等价于：

```typescript
const token = new URLSearchParams(location.hash.replace(/^#/, '')).get('token');
if (!token) {
  renderPayError(...);
  return;
}
const order = await api.post(`/api/checkout/orders/${tradeNo}/session`, { token });
history.replaceState(null, '', `/pay/${tradeNo}`);
await mountPayment(root, order);
```

关键矛盾是：

- 首次成功后主动删除 Fragment 是正确的安全措施，可以减少令牌停留在地址栏、截图和复制链接中的风险。
- 刷新时仍强制要求已经被自己删除的 Fragment 是错误的恢复逻辑。
- `if (!token) return` 使浏览器即使仍持有 HttpOnly Cookie，也没有机会调用已有的订单快照接口。

### 4.3 BAR 后端已经提供 Cookie 恢复能力

BAR 后端：

```text
C:\Users\damn\Desktop\ai-temperate-recall-test\src\main\java\com\ihaveagoddamnplan\bar\payment\controller\CheckoutController.java
C:\Users\damn\Desktop\ai-temperate-recall-test\src\main\java\com\ihaveagoddamnplan\bar\payment\service\CheckoutSessionService.java
```

首次兑换成功后，后端设置：

```text
bar_checkout_session=<token>
HttpOnly=true
Secure=true
SameSite=Strict
Path=/api/checkout/orders/{tradeNo}
Max-Age=订单剩余有效时间
```

后端同时已经提供：

```http
GET /api/checkout/orders/{tradeNo}
GET /api/checkout/orders/{tradeNo}/events
```

这两个接口都会通过 `CheckoutSessionService.authenticate(...)` 从 Cookie 恢复并认证订单会话。

接口文档也明确写明“一次性 token 首次兑换；已有页面应使用已建立的 Cookie 继续”。当前前端没有落实这条合同。

### 4.4 访问日志证明首次兑换成功、刷新后没有恢复请求

针对平台订单 `7637840909438976`，Nginx 访问日志显示：

```text
20:50:04 GET  /pay/7637840909438976                         200
20:50:04 POST /api/checkout/orders/7637840909438976/session 200
20:50:29 GET  /pay/7637840909438976                         200
```

第二次文档请求是刷新，但紧随其后没有用于刷新恢复的 `GET /api/checkout/orders/7637840909438976`。这与 `payment.ts` 在缺少 Fragment 时提前返回完全一致。

长连接的 Nginx access log 可能在连接关闭时才写入，因此刷新之后出现一条 `/events` 日志不能证明刷新后的错误页重新建立了 SSE；它可能是刷新前连接结束时的延迟记录。

## 5. Cookie 消失现象的第二层排查

用户截图显示刷新前 DevTools 中存在 `bar_checkout_session`，刷新后列表中不再显示该 Cookie。当前已读取的前后端源码没有主动删除该 Cookie，因此应把它作为独立的待确认项，不应拿它替代已经确认的前端恢复缺陷。

接手方需要在一次全新订单中记录以下非敏感元数据，严禁复制或记录 Cookie/token 的完整值：

1. `POST /session` 响应 `Set-Cookie` 是否实际包含预期的 `Path`、`Max-Age`、`Expires`、`Secure`、`HttpOnly` 和 `SameSite=Strict`。
2. `Expires` 是否晚于浏览器当前时间，`Max-Age` 是否接近订单剩余有效秒数。
3. 刷新前 Cookie 的完整 Path 是否严格等于当前订单的 `/api/checkout/orders/{tradeNo}`。
4. 刷新文档响应是否存在清理同名 Cookie 的 `Set-Cookie` 或 `Clear-Site-Data`。
5. DevTools 是否启用了过滤条件，或者只显示与当前文档 Path 匹配的 Cookie。
6. 在刷新后直接调用快照接口时，浏览器是否携带 Checkout Cookie；只记录“携带/未携带”，不要记录值。

无论 Cookie 是否真的被浏览器删除，前端都必须先实现“无 Fragment 时尝试 Cookie 恢复”。如果 Cookie 确实消失，恢复请求会得到受控 401，从而暴露并单独定位 Cookie 生命周期问题，而不是在请求前被“令牌缺失”遮蔽。

## 6. 推荐修复设计

只修改 BAR 项目，不修改主项目。

### 6.1 页面启动状态机

将 `renderPayment` 调整为以下顺序：

```text
读取 Fragment token
  ├─ token 存在
  │    ├─ POST /session 兑换一次性 token
  │    ├─ 成功后删除 Fragment
  │    └─ mountPayment(order)
  │
  └─ token 不存在
       ├─ GET /api/checkout/orders/{tradeNo}
       ├─ Cookie 有效：mountPayment(order)
       └─ Cookie 缺失/无效：显示“支付页面会话不存在或已过期”
```

建议再处理一个竞态恢复分支：如果 Fragment 仍存在，但 `POST /session` 返回“token 已使用”，随后尝试一次 Cookie 快照恢复。这样可以覆盖浏览器在后端已设置 Cookie、前端尚未来得及执行 `replaceState` 或挂载页面时发生的中断。

### 6.2 安全边界

- 不把 checkout token 写入 `localStorage`、`sessionStorage`、日志、错误提示或监控标签。
- 不把 Fragment token 改成 Query 参数。
- 仅在 `/session` 成功后清除 Fragment；失败时不自动记录或复制令牌。
- 刷新恢复依赖现有 HttpOnly Cookie，前端 JavaScript不读取 Cookie 值。
- 快照恢复成功后继续复用现有 SSE 和轮询逻辑。
- 401 只表示当前 Checkout 会话不可恢复，不应跳转管理员登录页。

### 6.3 错误文案

区分以下情况：

- 首次 URL 没有 Fragment，且 Cookie 恢复失败：`支付页面会话不存在或已过期，请重新从商户发起支付。`
- Fragment token 非法或过期：保留后端受控错误，但不显示 token。
- 网络暂时失败：显示重新加载按钮，不误报“令牌缺失”。
- 订单终态：正常显示订单终态和返回商户按钮，不要求重新兑换 token。

## 7. 实施任务

### 任务 1：先添加刷新恢复失败用例

**涉及文件：** BAR 前端测试文件，以及必要的最小测试辅助代码。

**验收标准：**

- 无 Fragment、Cookie 快照接口成功时，测试期望页面正常挂载。
- 当前实现应因提前显示“令牌缺失”而失败，证明测试捕获的是实际缺陷。
- 测试不得包含真实 checkout token 或 Cookie。

### 任务 2：实现 Cookie 快照恢复

**涉及文件：**

```text
frontend/src/payment.ts
```

**验收标准：**

- 有 token 时仍先兑换，并在成功后清理 Fragment。
- 无 token 时调用 `GET /api/checkout/orders/{tradeNo}`。
- Cookie 恢复成功后重新挂载 SSE、轮询和倒计时。
- 不新增任何浏览器持久化 token。

### 任务 3：覆盖已兑换 token 的中断恢复

**验收标准：**

- `/session` 明确返回 token 已使用时，前端只尝试一次 Cookie 快照恢复。
- Cookie 有效则继续；Cookie 无效则显示受控会话错误。
- 网络超时和未知 5xx 不得伪装成“token 已使用”。

### 任务 4：核对 Cookie 生命周期

**验收标准：**

- `Set-Cookie` 的 Path 精确限定当前订单 API 路径。
- `Max-Age/Expires` 与订单剩余有效期一致且不会立即过期。
- 刷新 `/pay/{tradeNo}` 不会主动清理 Checkout Cookie。
- Cookie 到期或订单过期后，快照恢复按合同返回受控 401。

### 任务 5：更新合同文档和回归测试

**验收标准：**

- 文档明确写出首次 Fragment 兑换与刷新 Cookie 恢复两条路径。
- 测试覆盖刷新、复制清理后的 URL、Cookie 过期、错误订单 Path 和订单终态。
- 文档不包含真实 Cookie、API Key、签名或 checkout token。

## 8. 验证矩阵

| 场景 | Fragment | Cookie | 预期结果 |
|---|---|---|---|
| 首次正常进入 | 有效 | 无 | `/session` 成功，清理 Fragment，显示订单 |
| 同标签页刷新 | 无 | 有效 | 快照 GET 成功，恢复订单与 SSE |
| 复制清理后的 URL 到同一浏览器 | 无 | 有效且 Path 匹配 | 恢复订单 |
| 复制 URL 到另一个浏览器 | 无 | 无 | 显示会话不存在/已过期 |
| token 已兑换但 Fragment 仍保留 | 已使用 | 有效 | `/session` 冲突后回退快照 GET |
| Cookie 已过期 | 无 | 过期/无 | 受控错误，不展示订单 |
| Cookie 属于另一订单 Path | 无 | 不匹配 | 受控错误，不跨订单访问 |
| 订单进入 PAID | 无 | 有效 | 恢复终态并显示返回商户入口 |
| BAR 服务暂时不可用 | 任意 | 任意 | 显示网络错误/重试，不误报令牌缺失 |

## 9. 完整验收标准

- 从 `ai-temperate-main` 发起的新订单首次进入 BAR 页面正常。
- 首次兑换后地址栏不保留 checkout token。
- 在订单和 Checkout Cookie 有效期内连续刷新三次，页面每次都能恢复相同订单。
- 刷新恢复不重复消费一次性 token。
- 刷新恢复后 SSE 可以重新连接；失败时仍按原逻辑降级轮询。
- Cookie 失效后页面返回明确受控错误，不泄露订单详情。
- 商户 HMAC 回调、主动查询、关闭、退款链路不因前端修复发生变化。
- `ai-temperate-main` 无需为该问题修改支付跳转或保存 BAR token。

## 10. 非目标与修改边界

- 不修改 `ai-temperate-main`。
- 不把 BAR checkout token 持久化到商户数据库、Redis 或浏览器 Storage。
- 不修改 BAR HMAC 回调协议。
- 不新增数据库表或字段。
- 不改变管理员手动确认支付的模拟资金边界。
- 未经项目所有者明确批准，不执行 BAR 项目写入、构建、部署或外部联调。

## 11. 给接手任务的简短指令

```text
请只修改 C:\Users\damn\Desktop\ai-temperate-recall-test。

先为 frontend/src/payment.ts 添加一个会失败的刷新恢复测试：首次兑换已完成、URL 无 Fragment、浏览器持有有效 Checkout Cookie 时，页面必须通过 GET /api/checkout/orders/{tradeNo} 恢复订单。

随后实现：有 Fragment 时 POST /session；无 Fragment 时 GET 快照；已使用 token 时允许一次 Cookie 快照恢复。成功兑换后继续删除 Fragment，不得把 token 写入任何 Storage、日志或 Query。核对 Set-Cookie 的 Max-Age/Expires/Path，并按照本交接文档的验证矩阵完成测试。不要修改 ai-temperate-main、回调协议、数据库结构或模拟资金边界。
```
