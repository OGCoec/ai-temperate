# Admin AI Model Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在管理端 UniApp 中交付 AI 模型分页列表、新增、详情编辑和单个/批量启停，并补齐列表 API 的服务端名称/厂商搜索与状态过滤。

**Architecture:** 后端继续由 `AdminAiModelController -> AdminAiModelService -> AiModelMapper` 负责参数化分页查询，PageHelper 只包围模型主查询，能力仍批量加载。前端新增独立 API 模块和表单状态模块，三个页面全部通过 `adminRequest` 复用管理员 Session、CSRF、PreAuth、WebRTC 和风险恢复；详情请求通过可选完整响应模式读取 ETag。

**Tech Stack:** Spring MVC、Java 21、MyBatis、PageHelper、PostgreSQL、UniApp Vue、SCSS、Node.js `node:test`。

---

## File map

### Backend files

- Modify `ai-temperate-web/src/main/java/com/example/temperate/web/admin/controller/AdminAiModelController.java`
  - 为列表增加可选 `keyword` 和 `enabled` 参数。
- Modify `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/service/AdminAiModelService.java`
  - 扩展列表 Service 契约。
- Modify `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/service/impl/AdminAiModelServiceImpl.java`
  - 规范化并转义前缀搜索关键字，继续在 PageHelper 清理后批量加载能力。
- Modify `ai-temperate-mapper/src/main/java/com/example/temperate/mapper/ai/AiModelMapper.java`
  - 为 `findPage` 增加固定参数。
- Modify `ai-temperate-mapper/src/main/resources/mapper/ai/AiModelMapper.xml`
  - 添加参数化名称/厂商前缀搜索和状态过滤。
- Create `sql/008_add_ai_model_admin_filter_indexes.sql`
  - 为厂商前缀和状态加倍率排序建立真实查询索引。
- Modify existing controller/service/mapper AI model tests.

### Frontend common files

- Modify `myuniappadmin/common/admin/admin-http.js`
  - 保持默认只返回 data；按显式选项返回 data、statusCode 和 headers。
- Create `myuniappadmin/common/admin/admin-ai-model-api.js`
  - 封装列表、详情、新增、Merge Patch、单个和批量状态 API。
- Create `myuniappadmin/common/admin/admin-ai-model-form.js`
  - 定义能力选项、表单快照、校验和 Merge Patch 差异构造。
- Create corresponding Node contract tests.

### Frontend UI files

- Create `myuniappadmin/components/admin/ai-model-form.vue`
  - 新增与编辑共用的受控表单。
- Create `myuniappadmin/pages/ai-models/index.vue`
  - 查询、分页、排序、状态过滤、选择和批量启停。
- Create `myuniappadmin/pages/ai-models/create.vue`
  - 新增模型。
- Create `myuniappadmin/pages/ai-models/detail.vue`
  - 只读详情、编辑、ETag 冲突和单个启停。
- Modify `myuniappadmin/pages/index/index.vue`
  - 增加“AI 模型目录”入口。
- Modify `myuniappadmin/pages.json`
  - 注册三个自定义导航栏页面。
- Modify `myuniappadmin/package.json`
  - 增加 `test:ai-models` 定向测试脚本。
- Create `myuniappadmin/common/admin/admin-ai-model-ui-contract.test.cjs`
  - 锁定路由、请求层、不可物理删除和移动端可访问性契约。

---

### Task 1: Add server-side keyword and enabled filters

**Files:**
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/admin/controller/AdminAiModelController.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/service/AdminAiModelService.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/service/impl/AdminAiModelServiceImpl.java`
- Modify: `ai-temperate-mapper/src/main/java/com/example/temperate/mapper/ai/AiModelMapper.java`
- Modify: `ai-temperate-mapper/src/main/resources/mapper/ai/AiModelMapper.xml`
- Create: `sql/008_add_ai_model_admin_filter_indexes.sql`
- Test: `ai-temperate-web/src/test/java/com/example/temperate/web/admin/controller/AdminAiModelControllerContractTest.java`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/service/impl/AdminAiModelServiceImplTest.java`
- Test: `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/ai/AdminAiModelPageHelperIntegrationTest.java`
- Test: `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/ai/AiModelSchemaContractTest.java`

- [ ] **Step 1: Write list-filter contract tests first**

Add controller assertions that `keyword` is optional with maximum length 128 and `enabled` is an optional Boolean. Add Service tests that:

```java
service.list(1, 50, "  GPT%_  ", Boolean.TRUE, INPUT_FIRST, ASC);

verify(modelMapper).findPage("gpt\\%\\_", Boolean.TRUE);
verify(capabilityMapper).findByAiModelIds(List.of(101L));
```

Add integration rows for enabled/disabled OpenAI and Google models, then assert:

```java
PageInfo<AiModel> page = PageHelper.startPage(1, 50)
        .setOrderBy("input_ratio ASC, output_ratio ASC, model_name ASC")
        .doSelectPageInfo(() -> mapper.findPage("gpt", Boolean.TRUE));

assertThat(page.getList()).extracting(AiModel::getModelName)
        .containsExactly("gpt-5.6");
assertThat(page.getTotal()).isEqualTo(1);
```

Also prove `%` and `_` are literals, not wildcard controls.

- [ ] **Step 2: Extend the fixed backend contract**

Controller signature:

```java
public AdminAiModelPageResult list(
        int pageNum,
        int pageSize,
        @RequestParam(required = false) @Size(max = 128) String keyword,
        @RequestParam(required = false) Boolean enabled,
        AiModelSortPriority sortPriority,
        AiModelSortDirection direction,
        HttpServletResponse response)
```

Service signature:

```java
AdminAiModelPageResult list(
        int pageNum,
        int pageSize,
        String keyword,
        Boolean enabled,
        AiModelSortPriority sortPriority,
        AiModelSortDirection direction);
```

Normalize using `trim().toLowerCase(Locale.ROOT)` and escape `\`, `%`, `_` before passing the prefix to Mapper. Empty input becomes `null`.

Mapper signature:

```java
List<AiModel> findPage(
        @Param("keyword") String keyword,
        @Param("enabled") Boolean enabled);
```

SQL:

```xml
<select id="findPage" resultMap="AiModelResultMap">
    SELECT <include refid="AiModelColumns"/>
    FROM ai_model
    <where>
        <if test="enabled != null">
            is_enabled = #{enabled,jdbcType=BOOLEAN}
        </if>
        <if test="keyword != null and keyword != ''">
            AND (
                LOWER(model_name) LIKE CONCAT(#{keyword,jdbcType=VARCHAR}, '%') ESCAPE '\'
                OR LOWER(vendor) LIKE CONCAT(#{keyword,jdbcType=VARCHAR}, '%') ESCAPE '\'
            )
        </if>
    </where>
</select>
```

PageHelper cleanup remains in `finally`; capability query remains after cleanup.

- [ ] **Step 3: Add query-driven indexes**

Create non-transactional concurrent indexes:

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ai_model_vendor_prefix_ci
    ON ai_model (LOWER(vendor) varchar_pattern_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ai_model_enabled_input_output_name
    ON ai_model (is_enabled, input_ratio, output_ratio, model_name);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ai_model_enabled_output_input_name
    ON ai_model (is_enabled, output_ratio, input_ratio, model_name);
```

The migration must not add `FOREIGN KEY` or `REFERENCES`.

- [ ] **Step 4: Defer test execution to phase two**

Do not run Maven in phase one. The exact phase-two command is:

```powershell
mvn -pl ai-temperate-web -am `
  '-Dtest=AdminAiModelControllerContractTest,AdminAiModelServiceImplTest,AdminAiModelPageHelperIntegrationTest,AiModelSchemaContractTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected phase-two result: all selected tests pass using isolated Testcontainers PostgreSQL only.

### Task 2: Add full-response support and the AI model API client

**Files:**
- Modify: `myuniappadmin/common/admin/admin-http.js`
- Create: `myuniappadmin/common/admin/admin-ai-model-api.js`
- Create: `myuniappadmin/common/admin/admin-ai-model-api.test.cjs`
- Modify: `myuniappadmin/common/admin/admin-auth-contract.test.cjs`

- [ ] **Step 1: Write API request tests first**

Test exact paths and payloads with an injected request function:

```javascript
await api.list({
  pageNum: 2,
  pageSize: 20,
  keyword: 'gpt',
  enabled: true,
  sortPriority: 'OUTPUT_FIRST',
  direction: 'DESC'
})
```

Expected path:

```text
/api/admin/ai-models?pageNum=2&pageSize=20&keyword=gpt&enabled=true&sortPriority=OUTPUT_FIRST&direction=DESC
```

Assert detail requests `{ method: 'GET', returnResponse: true }`, Patch sends:

```javascript
{
  method: 'PATCH',
  data: patch,
  headers: {
    'Content-Type': 'application/merge-patch+json',
    'If-Match': '"v3"'
  }
}
```

Assert the module has no delete method or `/delete` path.

- [ ] **Step 2: Preserve the existing adminRequest default**

In `admin-http.js`, successful requests resolve:

```javascript
const result = options.returnResponse === true
  ? {
      data: response.data,
      statusCode: response.statusCode,
      headers: { ...(response.header || response.headers || {}) }
    }
  : response.data
resolve(result)
```

No existing caller changes behavior unless it explicitly requests metadata.

- [ ] **Step 3: Implement the API client**

Expose:

```javascript
createAdminAiModelApi(request = adminRequest)
adminAiModelApi
```

Methods:

```javascript
list(query)
detail(publicId)
create(command)
patch(publicId, etag, patchDocument)
setEnabled(publicId, enabled)
setEnabledBatch(publicIds, enabled)
```

`detail()` performs case-insensitive ETag header lookup and rejects missing ETag with `AI_MODEL_ETAG_MISSING`. Public IDs are validated against `/^[A-Za-z0-9_-]{11}$/` before path construction. Query parameters use `URLSearchParams`; empty keyword and null enabled are omitted.

- [ ] **Step 4: Defer Node test execution to phase two**

```powershell
Push-Location myuniappadmin
node --test common/admin/admin-ai-model-api.test.cjs common/admin/admin-auth-contract.test.cjs
Pop-Location
```

### Task 3: Add deterministic form state and Merge Patch generation

**Files:**
- Create: `myuniappadmin/common/admin/admin-ai-model-form.js`
- Create: `myuniappadmin/common/admin/admin-ai-model-form.test.cjs`

- [ ] **Step 1: Write form behavior tests first**

Cover:

```javascript
const snapshot = modelToForm(detail)
const unchanged = createMergePatch(snapshot, { ...snapshot })
assert.deepEqual(unchanged, {})

const changed = createMergePatch(snapshot, {
  ...snapshot,
  description: '',
  capabilities: ['RESPONSES', 'IMAGE']
})
assert.deepEqual(changed, {
  description: null,
  capabilities: ['RESPONSES', 'IMAGE']
})
```

Also cover duplicate/blank tags, negative or >8-decimal ratios, missing capability, required model name/vendor, and capability enum whitelist.

- [ ] **Step 2: Implement pure form helpers**

Export:

```javascript
CAPABILITY_OPTIONS
emptyAiModelForm()
modelToForm(model)
normalizeTags(tags)
validateAiModelForm(form)
createAiModelPayload(form)
createMergePatch(snapshot, draft)
hasAiModelChanges(snapshot, draft)
```

String normalization trims input; model name/vendor are lowercased to match backend behavior. Empty description/icon become `null` in API payloads. Ratio values remain decimal strings until payload construction to prevent floating-point presentation drift.

- [ ] **Step 3: Defer Node test execution to phase two**

```powershell
Push-Location myuniappadmin
node --test common/admin/admin-ai-model-form.test.cjs
Pop-Location
```

### Task 4: Build the shared AI model form component

**Files:**
- Create: `myuniappadmin/components/admin/ai-model-form.vue`
- Test: `myuniappadmin/common/admin/admin-ai-model-ui-contract.test.cjs`

- [ ] **Step 1: Write static UI contract assertions first**

Assert the component contains visible labels for every field, all five capabilities, `aria-live` error output, numeric `inputmode="decimal"`, and no direct `uni.request`.

- [ ] **Step 2: Implement the controlled form**

Props:

```javascript
modelValue: Object
errors: Object
availableCapabilities: Array
busy: Boolean
```

Events:

```text
update:modelValue
submit
```

The component groups basic information, ratios, tags, capabilities and initial status. `enabled` is shown only when `showEnabled` is true. Tag addition happens on explicit button/Enter, max 20, and duplicate normalized tags are rejected inline.

Use the existing theme variables, 12rpx control radius, 18rpx panel radius, minimum 88rpx control height, visible focus rings and a mobile single-column/wide two-column layout. No full-page modal and no decorative animation.

### Task 5: Build the paginated list and batch status page

**Files:**
- Create: `myuniappadmin/pages/ai-models/index.vue`
- Test: `myuniappadmin/common/admin/admin-ai-model-ui-contract.test.cjs`

- [ ] **Step 1: Write list-page contract assertions first**

Assert the page imports `adminAiModelApi`, exposes input/output priority and ASC/DESC choices, renders enabled status text, has previous/next controls, clears selection on query changes, and calls only `setEnabled`/`setEnabledBatch`. Assert no delete label, delete method, or DELETE request exists.

- [ ] **Step 2: Implement server-backed list state**

State:

```javascript
query: {
  pageNum: 1,
  pageSize: 50,
  keyword: '',
  enabled: null,
  sortPriority: 'INPUT_FIRST',
  direction: 'ASC'
}
page: {
  models: [],
  total: 0,
  pages: 0,
  hasPrevious: false,
  hasNext: false
}
selectedIds: []
```

Use a 300ms search debounce. Any filter/sort/page-size change sets `pageNum = 1`, clears selection, and reloads. Navigation to detail uses only encoded public ID:

```javascript
uni.navigateTo({
  url: `/pages/ai-models/detail?publicId=${encodeURIComponent(publicId)}`
})
```

Cards show name, vendor, state, two ratios and up to three capabilities. Wide screens become compact list rows; mobile remains one column. Batch action bar is fixed above safe area only while selection mode is active.

- [ ] **Step 3: Implement bounded status writes**

Single state writes use a confirmation only when disabling. Batch action sends at most the selected current-page IDs. On success, clear selection and reload. If the current filtered page becomes empty and `pageNum > 1`, decrement once and reload. Failure retains selection and shows `aria-live` recovery feedback.

### Task 6: Build the create page

**Files:**
- Create: `myuniappadmin/pages/ai-models/create.vue`
- Test: `myuniappadmin/common/admin/admin-ai-model-ui-contract.test.cjs`

- [ ] **Step 1: Write create-page contract assertions first**

Assert the page uses the shared form, validates through `validateAiModelForm`, submits through `adminAiModelApi.create`, has no direct request, and navigates to the returned `publicId`.

- [ ] **Step 2: Implement create flow**

Initialize `emptyAiModelForm()`, with `enabled: false` as the safe default. On submit:

```javascript
const validation = validateAiModelForm(form)
if (!validation.valid) {
  errors = validation.errors
  return
}
const created = await adminAiModelApi.create(createAiModelPayload(form))
uni.redirectTo({
  url: `/pages/ai-models/detail?publicId=${encodeURIComponent(created.publicId)}`
})
```

Keep all inputs after failure. Warn before leaving only if the form differs from its initial empty snapshot.

### Task 7: Build detail, editing, ETag conflict, and single status

**Files:**
- Create: `myuniappadmin/pages/ai-models/detail.vue`
- Test: `myuniappadmin/common/admin/admin-ai-model-ui-contract.test.cjs`

- [ ] **Step 1: Write detail-page contract assertions first**

Assert GET detail stores ETag, save constructs `createMergePatch`, empty Patch sends nothing, Patch receives ETag, status uses the dedicated endpoint, and version conflict preserves the draft.

- [ ] **Step 2: Implement read-only detail**

Validate `options.publicId` against the fixed Base64URL pattern. Load:

```javascript
const { model, etag } = await adminAiModelApi.detail(publicId)
snapshot = modelToForm(model)
draft = modelToForm(model)
```

Render dates, status, ratios, tags and all capabilities. Default mode is read-only.

- [ ] **Step 3: Implement edit and conflict recovery**

On save:

```javascript
const patch = createMergePatch(snapshot, draft)
if (Object.keys(patch).length === 0) {
  editing = false
  return
}
const updated = await adminAiModelApi.patch(publicId, etag, patch)
applyServerDetail(updated.model, updated.etag)
```

When error code is `AI_MODEL_VERSION_CONFLICT`, preserve `draft`, set `conflict = true`, and offer an explicit “查看最新内容” action. Reload only after confirmation; never silently overwrite the draft.

- [ ] **Step 4: Keep status independent from field Patch**

Read-only and editing modes both show current status, but status action calls `setEnabled(publicId, target)` and then reloads detail to acquire the new row version/ETag. `enabled` must never be added to the Merge Patch.

### Task 8: Register routes, add dashboard entry, and complete contracts

**Files:**
- Modify: `myuniappadmin/pages.json`
- Modify: `myuniappadmin/pages/index/index.vue`
- Modify: `myuniappadmin/package.json`
- Modify: `myuniappadmin/common/admin/admin-ai-model-ui-contract.test.cjs`

- [ ] **Step 1: Register all pages**

Add:

```json
{
  "path": "pages/ai-models/index",
  "style": {
    "navigationStyle": "custom",
    "backgroundColor": "#080b0d"
  }
}
```

Repeat for `create` and `detail`, including `softinputMode: "adjustResize"` for form pages.

- [ ] **Step 2: Add the authenticated dashboard entry**

Add an “AI 模型目录” management button beside the existing IP reputation credential entry and navigate to `/pages/ai-models/index`. Do not alter authentication, registration or login flows.

- [ ] **Step 3: Add the npm script**

```json
"test:ai-models": "node --test common/admin/admin-ai-model-api.test.cjs common/admin/admin-ai-model-form.test.cjs common/admin/admin-ai-model-ui-contract.test.cjs"
```

- [ ] **Step 4: Complete static safety assertions**

The contract test must recursively inspect the three pages, shared form and API module and assert:

- no `uni.request` outside `admin-http.js`;
- no DELETE method, `/delete` path, “删除模型” or “批量删除”;
- all model operations route through `adminAiModelApi`;
- responsive and reduced-motion styles exist;
- list selection is cleared on page/query changes;
- details use `If-Match`;
- all three routes use custom navigation.

### Task 9: Phase-two verification

**Files:** No production changes.

- [ ] **Step 1: Explain isolation and obtain explicit authorization**

State that Node tests are local/read-only, while Maven integration tests use Docker Testcontainers PostgreSQL and write only isolated container data. They do not connect to production Redis, PostgreSQL, Cloudflare or hCaptcha.

- [ ] **Step 2: Run frontend tests after authorization**

```powershell
Push-Location myuniappadmin
npm run test:ai-models
npm run test:auth-network-risk
npm run test:auth-hcaptcha
Pop-Location
```

- [ ] **Step 3: Run backend tests after authorization**

```powershell
mvn -pl ai-temperate-web -am `
  '-Dtest=AdminAiModelControllerContractTest,AdminAiModelControllerPatchTest,AdminAiModelServiceImplTest,AdminAiModelPageHelperIntegrationTest,AiModelSchemaContractTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 4: Perform external Chrome acceptance only after separate authorization**

Use only connected external Chrome, never Codex IAB. Verify Android-width and desktop-width layouts, pagination, filters, create, Patch conflict, and batch status with isolated administrator data.
