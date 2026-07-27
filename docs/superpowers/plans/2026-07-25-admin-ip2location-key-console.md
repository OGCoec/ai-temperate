# 管理员 IP2Location 凭据控制台实施计划

> **执行约束：** 本项目明确禁止本任务使用子智能体。实现阶段由当前主智能体按 `incremental-implementation` 分批完成；第一阶段只写代码与测试代码，不运行测试、构建、部署或外部连接。

**目标：** 在 `myuniappadmin` 中新增同时适配 H5 与 Android 的 IP2Location API Key 管理页面，支持批量粘贴、TXT 文件导入、脱敏查看、前端排序、每页 20 条分页和批量删除。

**架构：** Redis 继续只维护 `secret Hash` 与 `quota Hash`，不增加 ZSet。管理员前端通过现有有界 HSCAN API 拉取最多 100 条脱敏元数据，在内存中按绝对过期时间稳定排序，再完成 20 条一页的客户端分页；所有写操作仍通过受管理员会话、PreAuth、设备绑定、网络风险和管理员 CSRF 保护的 `/api/admin/**` 接口。

**技术栈：** UniApp、Vue Options API、SCSS、`uni.request`、现有 `adminRequest` 安全请求链、Spring Boot 管理员接口、Redis 7.4 Hash Field TTL。

---

## 一、已确认的设计决策

### 1. 数据结构

- 继续使用现有两个 Redis Hash：
  - `ait:<env>:risk:ip2location:v1:secret`
  - `ait:<env>:risk:ip2location:v1:quota`
- 两个 Hash 使用相同的 HMAC `keyId` 字段。
- 两侧 Hash Field TTL 保持相同的绝对 `expiresAt`。
- 不新增 ZSet，因此不存在 Hash 已过期而 ZSet 索引仍残留的问题。
- 前端只接收 `keyId`、`maskedKey`、`planType`、`remainingQuota`、`expiresAt`、`createdAt`；绝不接收明文 API Key 或密文。

### 2. 查询、排序和分页

- 现有后端查询是 Redis HSCAN 游标接口：

  ```http
  GET /api/admin/risk/ip2location/keys?cursor=0&size=100
  ```

- 新增前端 `listAll()` 适配器，持续读取 `nextCursor`，直到游标回到 `0`。
- 适配器使用 `Set` 对游标和 `keyId` 去重，拒绝游标循环和超过 100 条的异常响应。
- 后端不负责排序；前端完成全局排序后再分页，禁止先分页再只排序当前页。
- 默认按 `expiresAt` 升序排列，最先过期的凭据显示在最前面。
- 相同过期时间按完整 `keyId` 的 JavaScript 码位顺序升序排列，不使用受语言环境影响的 `localeCompare()`。
- 每页固定 20 条，最多 5 页。
- 界面显示“几天 / 几小时 / 几分钟”，但排序始终使用 `Date.parse(expiresAt)` 得到的绝对时间戳。

稳定排序函数约束：

```js
export function compareIp2LocationKeys(left, right) {
	const expiryDifference = Date.parse(left.expiresAt) - Date.parse(right.expiresAt)
	if (expiryDifference !== 0) return expiryDifference
	if (left.keyId === right.keyId) return 0
	return left.keyId < right.keyId ? -1 : 1
}
```

### 3. 100 条上限的职责边界

- 前端在导入前根据当前有效数量限制本批最多可提交数量，并展示 `当前数量 / 100`。
- 前端限制只负责用户体验，不能作为安全边界。
- 当前后端 `@Size(max = 500)` 只是单批上限，并没有证明全局最多 100 条。
- 在最终验收前，后端必须增加原子的全局 100 条限制；否则直接绕过前端调用接口仍可超过 100 条。
- 本计划的前端阶段不擅自重写 Redis Lua、Pipeline 或 `MULTI/EXEC` 实现；后端硬上限作为单独的后端配套改动，在实施前再次确认范围。

### 4. 导入渠道

- H5 与 Android 都必须支持多行粘贴，这是主路径。
- 输入规则：每行一个 API Key；去除首尾空白、忽略空行、前端先去重。
- H5 支持选择 UTF-8 `.txt` 文件，客户端读取并转换成相同的 `apiKeys` 数组，再调用 JSON 批量接口。
- 当前项目不能无条件假设 Android 运行时支持 `uni.chooseFile`：
  - 运行时存在受支持的文件选择 API时启用 TXT 导入。
  - 不支持时隐藏文件按钮并明确提示“Android 当前版本请使用多行粘贴”。
  - 不为了文件选择引入来源不明的原生插件。
- 文件最大 256 KB；有效非空行受“剩余容量”和 100 条总上限共同约束。

### 5. 套餐与有效期

- 套餐选项与后端枚举完全一致：`FREE`、`STARTER`、`PLUS`、`SECURITY`、`SECURITY_TRIAL`、`CUSTOM`。
- `FREE` 由 Service 按服务端 UTC 当前时间计算七天有效期；其余套餐按一个自然月计算。
- 管理员只提交 `initialQuota`，Controller、公开请求 DTO 和前端请求均不得接收 `expiresAt`、TTL 或日期时间字段。
- 页面只显示“导入后有效 7 天”或“导入后有效 1 个月”的只读说明，截止时间以 Service 计算结果为准。
- Service 将统一的绝对截止时间写入加密元数据，并由 Redis Lua 对凭据与额度 Hash Field 设置相同的 `HPEXPIREAT`。
- 写入模式默认 `CREATE_ONLY`；`UPSERT` 放在“高级选项”内，并清楚说明会覆盖已有 Key 的额度和有效期。

---

## 二、页面信息架构

### 1. 路由与入口

- 新增页面路由：

  ```text
  pages/risk/ip2location-keys
  ```

- 在管理员认证后的控制台区域新增“IP 信誉凭据”入口。
- 未认证、管理员会话失效或设备绑定失败时，不显示管理内容；交给现有 `adminRequest` 安全链返回受控错误并引导回管理员入口。

### 2. 桌面 H5 布局

- 延续现有 `#080b0d` 背景、青色品牌强调和管理端字体层级，不创建第二套视觉系统。
- 页面使用操作台结构，不使用同尺寸卡片网格：
  1. 顶部上下文栏：返回、页面标题、`n / 100`、刷新、主操作“导入凭据”。
  2. 状态条：有效、即将过期、额度耗尽数量；只作筛查辅助，不使用夸张大数字模板。
  3. 主体表格：凭据、套餐、剩余额度、有效期、状态、操作。
  4. 底部分页：上一页、页码、下一页和 `20 条/页` 固定说明。
- 导入操作使用从触发按钮方向展开的右侧任务面板；不使用全屏模态框。

### 3. Android / 窄屏布局

- 使用顶部应用栏显示返回、标题和刷新。
- 表格转换为紧凑分组列表，每条记录包含：
  - 第一行：`maskedKey` 与套餐标签。
  - 第二行：剩余额度与相对过期时间。
  - 第三行：精确过期时间和删除操作。
- 底部固定“导入凭据”主按钮，并添加 `safe-area-inset-bottom`，不能遮挡最后一条数据。
- 导入任务使用底部 Sheet；系统返回键优先关闭 Sheet，再返回上一页。
- 所有触控区域 Android 至少 48dp，H5 至少 44px；相邻操作保持至少 8px 间距。

### 4. 视觉与动效

- 视觉策略：Restrained，深色安全操作台，一种青色交互强调，加上现有 lime 主提交色。
- 不使用渐变文字、装饰网格、emoji 图标或嵌套卡片。
- 数据数字采用 tabular figures，防止额度和倒计时变化时发生水平抖动。
- 面板进入、分页切换和成功状态只使用 150～250ms 的状态动效。
- 动画只使用 `transform` 与 `opacity`，并为 `prefers-reduced-motion` 提供静态或淡入替代。

---

## 三、交互流程

### 1. 页面加载

1. 页面进入后调用 `adminIp2LocationKeyApi.listAll()`。
2. 加载超过 300ms 时显示保持行高的 Skeleton，避免内容跳动。
3. 收齐最多 100 条后去重、排序，生成前端分页数据。
4. 空集合显示说明型空状态：“还没有可用凭据”，主操作直接打开导入面板。
5. 网络失败显示原位错误和“重新加载”，不清空管理员已填写但未提交的导入内容。

### 2. 多行粘贴

1. 管理员粘贴一行一个 Key。
2. 页面本地计算非空行数、重复数和可提交数，不把完整 Key 放入日志或持久存储。
3. 管理员选择套餐、初始额度和写入模式；页面只读显示由后端套餐规则决定的有效期。
4. 字段失焦时进行校验；提交时聚焦第一个错误字段。
5. 提交期间按钮进入 loading 且不可重复点击。
6. 成功后只展示 `acceptedCount`、`createdCount`、`updatedCount`、`duplicateCount`。
7. 成功后立即清空原始文本、文件对象和组件内存，再重新加载脱敏列表。

### 3. TXT 文件

1. 只接受 UTF-8 `.txt`，选择后显示文件名、大小和解析出的有效行数。
2. 文件内容只保留到本次导入结束或面板关闭，不写入 localStorage、sessionStorage 或日志。
3. 文件解析后的数组走与多行粘贴相同的验证和 JSON `/batch` 接口，避免维护两套前端业务规则。
4. Android 文件选择不可用时展示可操作的粘贴替代方案，而不是禁用后无解释。

### 4. 删除

- 单条删除与批量选择最多 100 个 `keyId`，永不提交明文 Key。
- 删除前显示明确确认，内容只包含脱敏凭据和数量。
- 成功后更新本地集合并重新请求服务端校正状态。
- 删除失败保留选择状态，允许重试。

---

## 四、实施文件清单

### Task 1：前端纯函数和契约测试

**新增：**

- `myuniappadmin/common/admin/ip2location-key-presenter.js`
- `myuniappadmin/common/admin/ip2location-key-presenter.test.cjs`

职责：

- 拆分多行输入、过滤空行、稳定去重。
- 校验最多 100 条和当前剩余容量。
- 按 `expiresAt + keyId` 稳定排序。
- 每页 20 条切片，页码上限 5。
- 生成绝对时间与“天/小时/分钟”展示文本。
- 计算 `ACTIVE`、`EXPIRING`、`EXHAUSTED`、`EXPIRED` 状态。

测试代码必须覆盖：

- 0、1、20、21、100、101 条边界。
- 相同时间的 `keyId` 排序。
- 非法日期、过期时间和额度为 0。
- 输入空行、首尾空白和重复 Key。
- 排序后再分页，而不是分页后排序。

### Task 2：管理员 API 适配器

**新增：**

- `myuniappadmin/common/admin/admin-ip2location-key-api.js`
- `myuniappadmin/common/admin/admin-ip2location-key-api.test.cjs`

职责：

- `listAll()`：有界消费现有 `nextCursor`，去重并限制最多 100 条。
- `importBatch(command)`：调用 `/api/admin/risk/ip2location/keys/batch`。
- `deleteBatch(keyIds)`：调用 `/api/admin/risk/ip2location/keys/delete`。
- 所有方法复用 `adminRequest`，继承管理员会话、Android Bearer Token、管理员 CSRF、PreAuth 和风险 Challenge。
- 错误对象不得附带原始 `apiKeys`。

前端方法签名：

```js
export const adminIp2LocationKeyApi = {
	listAll(),
	importBatch({ planType, initialQuota, mode, apiKeys }),
	deleteBatch(keyIds)
}
```

### Task 3：跨平台 TXT 读取适配器

**新增：**

- `myuniappadmin/common/admin/ip2location-key-file-picker.js`
- `myuniappadmin/common/admin/ip2location-key-file-picker.test.cjs`

职责：

- H5 选择并读取 UTF-8 `.txt`。
- Android 运行时能力检测；支持时调用受支持的文件选择 API，不支持时返回明确的 `FILE_PICKER_UNAVAILABLE`。
- 校验扩展名、256 KB 上限和 UTF-8 解码。
- 关闭或成功导入后释放文件引用。

### Task 4：导入任务组件

**新增：**

- `myuniappadmin/components/admin/ip2location-key-import-sheet.vue`

职责：

- 粘贴 / TXT 两种输入模式。
- 套餐、额度、只读有效期说明、CREATE_ONLY/UPSERT 高级选项。
- 实时显示有效行、重复行、剩余容量和最终提交数量。
- 内联错误、提交 loading、成功汇总和关闭前未提交内容保护。
- 桌面表现为右侧任务面板，Android 表现为底部 Sheet。

### Task 5：脱敏列表组件

**新增：**

- `myuniappadmin/components/admin/ip2location-key-list.vue`

职责：

- 桌面语义表格与移动分组列表共用同一数据源。
- 显示 maskedKey、套餐、剩余额度、精确和相对有效期、文本状态。
- 状态不只依靠颜色，必须同时显示文字或图标。
- 支持选择、单删、批量删除和键盘焦点。
- 每页 20 条，分页按钮保留 44/48px 触控范围。

### Task 6：管理页面与导航入口

**新增：**

- `myuniappadmin/pages/risk/ip2location-keys.vue`

**修改：**

- `myuniappadmin/pages.json`
- `myuniappadmin/pages/index/index.vue`
- `myuniappadmin/common/app-theme.scss`

职责：

- 注册管理页面路由。
- 管理员已认证状态增加“IP 信誉凭据”入口。
- 页面编排加载、刷新、排序、分页、导入和删除状态。
- 把可复用的颜色、间距、焦点和状态色放入现有主题文件，组件禁止散落新的品牌色。
- 使用 UniApp 条件编译区分 H5 与 APP-PLUS 文件选择行为。

### Task 7：前端安全和状态测试代码

**新增或修改：**

- `myuniappadmin/common/admin/admin-ip2location-key-contract.test.cjs`
- `myuniappadmin/package.json`

测试代码覆盖：

- 所有 API 路径固定在 `/api/admin/risk/ip2location/keys/**`。
- GET 收齐游标后才排序和分页。
- POST 复用管理员 CSRF、管理员会话和 PreAuth 请求链。
- H5 与 Android 请求都不把 API Key 写入 URL、Header、日志或本地持久化。
- 导入完成和面板关闭会清空原始 Key。
- 管理员会话失效、PreAuth重建、风险Challenge和网络失败状态。
- Android文件选择不可用时仍保留多行粘贴主路径。

### Task 8：后端 100 条硬上限配套（需单独确认）

**可能修改：**

- `ai-temperate-service/src/main/java/com/example/temperate/service/risk/ip2location/service/Ip2LocationApiKeyService.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/risk/ip2location/service/impl/Ip2LocationApiKeyServiceImpl.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/risk/ip2location/store/Ip2LocationApiKeyStore.java`
- `ai-temperate-service/src/main/java/com/example/temperate/service/risk/ip2location/store/impl/RedisIp2LocationApiKeyStore.java`
- 相应 Redis 脚本或 `WATCH + MULTI/EXEC` 实现与测试代码。

目标：

- 服务端原子拒绝 `现有有效凭据 + 本次新增非重复凭据 > 100`。
- 前端 100 条限制只是提示，服务端才是最终安全边界。
- 不新增 ZSet。
- 如果用户坚持不使用 Lua 循环，实施前单独确认 `WATCH + MULTI/EXEC` 的并发重试和失败语义。

---

## 五、状态与文案

| 状态 | 页面表现 | 可恢复动作 |
|---|---|---|
| Loading | 固定行高 Skeleton | 等待 |
| Empty | “还没有可用凭据” + 导入说明 | 打开导入 |
| Ready | 表格/列表 + `n / 100` | 刷新、导入、删除 |
| Expiring | “即将过期”文字 + warning色 | 导入替代凭据 |
| Exhausted | “额度已耗尽”文字 | 删除或更新 |
| Import success | 新增/更新/重复数量 | 返回列表 |
| Validation error | 对应字段下方说明 | 修改后重试 |
| Session expired | 不展示凭据内容 | 返回管理员登录 |
| Risk challenge | 走现有顶层Challenge导航 | 验证后回原页面 |
| Network error | 保留未提交表单 | 重新加载/重新提交 |

---

## 六、第一阶段交付边界

计划确认后，第一阶段可以实现：

- 管理员 H5/Android 页面代码。
- API 适配器、排序分页纯函数和测试代码。
- 多行粘贴与能力检测后的 TXT 导入。
- 脱敏列表、刷新和删除交互。
- 路由和认证后入口。

第一阶段明确不执行：

- `npm test`、前端构建或 HBuilderX 编译。
- 后端 Maven 测试或构建。
- 真实管理员接口、生产 Redis、Cloudflare 或 IP2Location 外部连接。
- 部署 H5 或 Android 包。

后端 100 条硬上限不是纯前端修改，必须在实施前单独确认是否与前端代码同批完成。

## 七、第二阶段候选验证

只有用户再次明确批准后，才说明并执行：

1. 纯函数与请求契约 Node 测试。
2. H5 375px、768px、1024px、1440px 响应式检查。
3. Android 小屏、横屏、系统返回键、键盘和安全区检查。
4. H5 TXT 文件解析与 Android 文件选择降级检查。
5. 测试环境管理员会话、CSRF、PreAuth、Challenge和删除流程。
6. 如批准后端配套，再运行隔离 Redis 7.4.9 集成测试。

## 八、验收标准

- 同一页面同时适配管理员 H5 和 Android。
- 管理员可粘贴一行一个 Key，H5 可导入 UTF-8 TXT。
- 原始 API Key 提交后立即从前端内存和输入控件清除。
- 列表只显示脱敏值，不显示明文、密文或供应商响应。
- 最多 100 条完整集合先排序，再按每页 20 条显示，最多 5 页。
- 默认最早过期在前；同时间按 `keyId` 稳定升序。
- Hash Field 过期后记录不再从后端返回，不依赖 ZSet 清理。
- 所有写操作继续经过管理员 CSRF、会话、设备、PreAuth和网络风险安全链。
- 文件选择不可用不会阻断 Android 的多行粘贴主路径。
- 未执行测试前只表述为“代码已实现”，禁止表述为“测试通过”或“功能已验证”。
