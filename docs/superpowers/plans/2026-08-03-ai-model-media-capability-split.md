# AI 模型媒体能力拆分实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将模型能力从旧的 `IMAGE`、`AUDIO`、`VIDEO` 三个混合能力拆分为输入、生成、编辑九个精确能力，并让 PostgreSQL、MyBatis、Java、缓存、管理员 API、管理员前端和用户聊天附件校验使用同一套 12 项能力契约。

**Architecture:** `ai_model_capability` 继续采用“一项能力一行”的关系模型，`capability_code` 仍是唯一能力字段，不增加布尔列。Java 枚举和数据库 CHECK 约束共同组成能力白名单；请求与响应继续使用字符串数组。旧媒体能力只迁移为对应的 `*_INPUT`，因为当前生产代码只用旧能力授权附件输入，禁止迁移时自动授予生成或编辑权限。

**Tech Stack:** PostgreSQL、MyBatis XML、Java 21、Spring Boot、Bean Validation、Jackson、Redis AES-256-GCM 模型快照、Vue/uni-app、Node.js `node:test`、JUnit 5、Mockito、AssertJ、Testcontainers。

---

## 实施边界与固定契约

能力代码和顺序固定如下，所有数据库、枚举、OpenAPI、前端选项和测试必须完全一致：

```text
CHAT_COMPLETIONS
RESPONSES
WEB_SEARCH
IMAGE_INPUT
IMAGE_GENERATION
IMAGE_EDIT
AUDIO_INPUT
AUDIO_GENERATION
AUDIO_EDIT
VIDEO_INPUT
VIDEO_GENERATION
VIDEO_EDIT
```

固定语义：

- `*_INPUT`：允许对应媒体作为模型输入；用户聊天上传图片、音频、视频只检查这一类能力。
- `*_GENERATION`：声明模型能够生成对应媒体，不隐含输入或编辑能力。
- `*_EDIT`：声明模型能够编辑对应媒体，不隐含输入或生成能力。
- 不根据模型名称、厂商或 `RESPONSES` 自动推导媒体能力。
- `IMAGE_EDIT` 是精确代码；禁止使用 `IMAGE_EDITING`、`IMAGE_CREATOR` 或 `IMAGE_USE`。
- 创建和更新请求继续发送完整 `capabilities` 数组；PATCH 中出现该字段时采用整组替换语义。
- 本计划不新增图片生成、图片编辑、音频生成或视频生成 API；只建立正确的能力存储、展示和现有附件输入校验。
- `AiModelCapabilityMapper.xml` 保持通用枚举到 VARCHAR 映射，不为 12 种能力增加分支、列或逐条数据库 I/O。

项目采用两阶段交付。第一阶段可以编写测试代码但禁止执行测试、编译、打包、依赖分析或连接数据库/Redis。文中所有“运行”步骤只在用户明确批准第二阶段、确认隔离基础设施后执行。

## 文件结构与职责

### 新建文件

- `sql/migrations/023_split_ai_model_media_capabilities.sql`：把已有旧媒体能力保守迁移为 `*_INPUT`，再建立 12 项 CHECK 约束。
- `ai-temperate-model/src/test/java/com/example/temperate/model/ai/enums/AiModelCapabilityCodeTest.java`：固定枚举名称、顺序和外部代码解析契约。

### 修改文件

- `sql/004_create_ai_model_capability.sql`：新数据库的 12 项能力白名单，内容与用户提供的 `C:/Users/damn/Desktop/ai-temperate/sql/004_create_ai_model_capability.sql` 对齐。
- `ai-temperate-model/src/main/java/com/example/temperate/model/ai/enums/AiModelCapabilityCode.java`：唯一 Java 能力枚举。
- `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/ai/AiModelSchemaContractTest.java`：基础 SQL、023 迁移和通用 Mapper 契约。
- `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/ai/AdminAiModelPatchIntegrationTest.java`：验证新枚举通过现有 XML 批量写入、读取和事务回滚。
- `ai-temperate-common/src/main/java/com/example/temperate/common/redis/key/RedisKeyFactory.java`：启用模型快照 Key 从 `v4` 升为 `v5`。
- `ai-temperate-common/src/test/java/com/example/temperate/common/redis/key/RedisKeyFactoryTest.java`：固定新 Key。
- `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/cache/AiModelCacheSnapshot.java`：快照 Schema 从 `5` 升为 `6`。
- `ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/cache/impl/AiModelCacheServiceImplTest.java`：验证 v6、新 Key 和细分能力快照。
- `ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/security/AiModelCacheProtectorTest.java`：使用新能力验证加密往返。
- `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/service/impl/AdminAiModelServiceImpl.java`：保留批量创建/替换逻辑，确保 12 项能力受控校验和缓存刷新语义不变。
- `ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/service/impl/AdminAiModelServiceImplTest.java`：创建、更新、列表、详情和非法能力测试。
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java`：附件类别映射到对应 `*_INPUT`。该文件当前有用户未提交改动，实施时只修改 `validateAttachmentCapabilities`，禁止覆盖其他差异。
- `ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/compaction/model/impl/AiConversationCompactionModelCatalogImplTest.java`：删除已不存在的旧枚举引用。
- `ai-temperate-web/src/main/java/com/example/temperate/web/admin/controller/AdminAiModelController.java`：创建请求最多 12 项，OpenAPI 声明完整能力集合。
- `ai-temperate-web/src/test/java/com/example/temperate/web/admin/controller/AdminAiModelControllerContractTest.java`：请求校验、OpenAPI 和响应数组契约。
- `ai-temperate-web/src/test/java/com/example/temperate/web/admin/controller/AdminAiModelControllerPatchTest.java`：PATCH 使用新能力代码。
- `ai-temperate-web/src/test/java/com/example/temperate/web/admin/aimodel/AdminAiModelMergePatchMapperTest.java`：Merge Patch 保持字符串数组和整组替换。
- `myuniappadmin/common/admin/admin-ai-model-form.js`：12 项管理员能力元数据、排序和白名单。
- `myuniappadmin/common/admin/admin-ai-model-form.test.cjs`：新增、编辑、校验和 Patch 的 12 项前端契约。
- `myuniappadmin/components/admin/ai-model-form.vue`：按协议/图像/音频/视频分组展示能力，不改变提交结构。
- `myuniappadmin/common/admin/admin-request-body.test.cjs`：管理员请求体使用新能力值。
- `fornted/common/aichat/ai-conversation-upload-state.js`：媒体附件映射到 `IMAGE_INPUT`、`AUDIO_INPUT`、`VIDEO_INPUT`。
- `fornted/common/aichat/ai-conversation-upload-state.test.cjs`：三个媒体输入能力和不兼容提示。
- `docs/database/ai-model.md`：12 项能力语义、迁移规则、缓存版本和回滚说明。

### 明确不修改的文件

- `ai-temperate-mapper/src/main/resources/mapper/ai/AiModelCapabilityMapper.xml`：已有 `EnumTypeHandler`/VARCHAR 映射、批量 INSERT、按模型批量查询和整组删除均可直接承载 12 项枚举。
- `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/dto/AdminAiModelCreateCommand.java`：`List<String> capabilities` 类型不变。
- `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/dto/AdminAiModelPatchCommand.java`：`AiModelPatchField<List<String>> capabilities` 类型不变。
- `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/dto/AdminAiModelResult.java`：`List<AiModelCapabilityCode>` 响应类型不变。
- `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/dto/AdminAiModelDetailResult.java`：`capabilities` 和 `availableCapabilities` 结构不变。
- `ai-temperate-web/src/main/java/com/example/temperate/web/admin/aimodel/AdminAiModelMergePatchMapper.java`：当前通用字符串数组解析无需能力专用分支。

---

### Task 1: 固定数据库能力白名单和存量迁移

**Files:**
- Modify: `sql/004_create_ai_model_capability.sql`
- Create: `sql/migrations/023_split_ai_model_media_capabilities.sql`
- Modify: `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/ai/AiModelSchemaContractTest.java`
- Modify: `docs/database/ai-model.md`

- [ ] **Step 1: 先修改数据库契约测试**

把 `capabilityTableUsesApplicationIdWhitelistAndLogicalAssociation` 中旧的 `IMAGE/VIDEO/AUDIO` 断言替换为完整 12 项，并明确拒绝三个旧值：

```java
assertThat(sql)
        .contains("'CHAT_COMPLETIONS'")
        .contains("'RESPONSES'")
        .contains("'WEB_SEARCH'")
        .contains("'IMAGE_INPUT'")
        .contains("'IMAGE_GENERATION'")
        .contains("'IMAGE_EDIT'")
        .contains("'AUDIO_INPUT'")
        .contains("'AUDIO_GENERATION'")
        .contains("'AUDIO_EDIT'")
        .contains("'VIDEO_INPUT'")
        .contains("'VIDEO_GENERATION'")
        .contains("'VIDEO_EDIT'")
        .doesNotContain("'IMAGE',")
        .doesNotContain("'AUDIO',")
        .doesNotContain("'VIDEO',")
        .doesNotContain("FOREIGN KEY")
        .doesNotContain("REFERENCES");
```

保留 `webSearchCapabilityMigrationExtendsOnlyTheCapabilityWhitelist` 对历史 `022` 的断言；新增一个独立测试读取 023：

```java
@Test
void mediaCapabilityMigrationMapsLegacyInputWithoutGrantingGenerationOrEdit()
        throws IOException {
    String migration = read(
            "sql/migrations/023_split_ai_model_media_capabilities.sql");

    assertThat(migration)
            .contains("DROP CONSTRAINT IF EXISTS chk_ai_model_capability_code")
            .contains("WHEN 'IMAGE' THEN 'IMAGE_INPUT'")
            .contains("WHEN 'AUDIO' THEN 'AUDIO_INPUT'")
            .contains("WHEN 'VIDEO' THEN 'VIDEO_INPUT'")
            .contains("'IMAGE_GENERATION'")
            .contains("'IMAGE_EDIT'")
            .contains("'AUDIO_GENERATION'")
            .contains("'AUDIO_EDIT'")
            .contains("'VIDEO_GENERATION'")
            .contains("'VIDEO_EDIT'")
            .doesNotContain("FOREIGN KEY")
            .doesNotContain("REFERENCES")
            .contains("COMMIT;");
}
```

- [ ] **Step 2: 用用户提供的 SQL 能力清单更新基础建表文件**

`CHECK (capability_code IN (...))` 必须是：

```sql
CHECK (capability_code IN (
    'CHAT_COMPLETIONS',
    'RESPONSES',
    'WEB_SEARCH',
    'IMAGE_INPUT',
    'IMAGE_GENERATION',
    'IMAGE_EDIT',
    'AUDIO_INPUT',
    'AUDIO_GENERATION',
    'AUDIO_EDIT',
    'VIDEO_INPUT',
    'VIDEO_GENERATION',
    'VIDEO_EDIT'
))
```

列注释使用用户提供文件中的中文说明：

```sql
COMMENT ON COLUMN ai_model_capability.capability_code IS
    '固定能力代码：CHAT_COMPLETIONS、RESPONSES、WEB_SEARCH，以及图像、音频、视频的输入、生成和编辑能力';
```

- [ ] **Step 3: 新增可重复收敛的 023 增量迁移**

完整迁移内容：

```sql
BEGIN;

-- 先移除旧白名单，才能在同一事务内把旧能力转换为新的输入能力。
ALTER TABLE ai_model_capability
    DROP CONSTRAINT IF EXISTS chk_ai_model_capability_code;

-- 新旧输入能力同时存在时保留新行，避免转换触发模型与能力的唯一约束冲突。
DELETE FROM ai_model_capability AS legacy
USING ai_model_capability AS replacement
WHERE legacy.ai_model_id = replacement.ai_model_id
  AND (
      (legacy.capability_code = 'IMAGE'
          AND replacement.capability_code = 'IMAGE_INPUT')
      OR
      (legacy.capability_code = 'AUDIO'
          AND replacement.capability_code = 'AUDIO_INPUT')
      OR
      (legacy.capability_code = 'VIDEO'
          AND replacement.capability_code = 'VIDEO_INPUT')
  );

-- 旧能力在现有业务中只授权附件输入，因此迁移不得扩大为生成或编辑权限。
UPDATE ai_model_capability
SET capability_code = CASE capability_code
    WHEN 'IMAGE' THEN 'IMAGE_INPUT'
    WHEN 'AUDIO' THEN 'AUDIO_INPUT'
    WHEN 'VIDEO' THEN 'VIDEO_INPUT'
    ELSE capability_code
END
WHERE capability_code IN ('IMAGE', 'AUDIO', 'VIDEO');

ALTER TABLE ai_model_capability
    ADD CONSTRAINT chk_ai_model_capability_code
        CHECK (capability_code IN (
            'CHAT_COMPLETIONS',
            'RESPONSES',
            'WEB_SEARCH',
            'IMAGE_INPUT',
            'IMAGE_GENERATION',
            'IMAGE_EDIT',
            'AUDIO_INPUT',
            'AUDIO_GENERATION',
            'AUDIO_EDIT',
            'VIDEO_INPUT',
            'VIDEO_GENERATION',
            'VIDEO_EDIT'
        ));

COMMENT ON COLUMN ai_model_capability.capability_code IS
    '固定能力代码：CHAT_COMPLETIONS、RESPONSES、WEB_SEARCH，以及图像、音频、视频的输入、生成和编辑能力';

COMMIT;
```

- [ ] **Step 4: 更新数据库设计文档**

在 `docs/database/ai-model.md` 写明：

```text
能力由协议能力和媒体输入/生成/编辑能力组成；每项能力仍是一行。
旧 IMAGE/AUDIO/VIDEO 只迁移到对应 *_INPUT。
生成和编辑能力必须由管理员按真实供应商能力显式配置。
023 迁移先消除新旧重复，再转换旧值，最后恢复 CHECK 约束。
```

- [ ] **Step 5: 第一阶段只做静态检查**

检查命令：

```powershell
git diff --check -- sql/004_create_ai_model_capability.sql sql/migrations/023_split_ai_model_media_capabilities.sql ai-temperate-mapper/src/test/java/com/example/temperate/mapper/ai/AiModelSchemaContractTest.java docs/database/ai-model.md
```

预期：退出码 0；允许显示 Git 的 LF/CRLF 提示，但不得有 whitespace error。

- [ ] **Step 6: 第二阶段经用户批准后运行数据库契约测试**

```powershell
mvn -pl ai-temperate-mapper -am -Dtest=AiModelSchemaContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`AiModelSchemaContractTest` 全部通过；该命令不连接外部 PostgreSQL。

- [ ] **Step 7: 可选原子提交**

```powershell
git add sql/004_create_ai_model_capability.sql sql/migrations/023_split_ai_model_media_capabilities.sql ai-temperate-mapper/src/test/java/com/example/temperate/mapper/ai/AiModelSchemaContractTest.java docs/database/ai-model.md
git commit -m "feat(ai-model): split persisted media capabilities"
```

---

### Task 2: 更新 Java 枚举并证明现有 Mapper XML 无需重写

**Files:**
- Modify: `ai-temperate-model/src/main/java/com/example/temperate/model/ai/enums/AiModelCapabilityCode.java`
- Create: `ai-temperate-model/src/test/java/com/example/temperate/model/ai/enums/AiModelCapabilityCodeTest.java`
- Modify: `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/ai/AiModelSchemaContractTest.java`
- Modify: `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/ai/AdminAiModelPatchIntegrationTest.java`
- Verify unchanged: `ai-temperate-mapper/src/main/resources/mapper/ai/AiModelCapabilityMapper.xml`

- [ ] **Step 1: 先新增枚举契约测试**

```java
package com.example.temperate.model.ai.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 验证模型能力枚举与数据库、HTTP 和前端共享的十二项稳定代码完全一致。
 */
class AiModelCapabilityCodeTest {

    @Test
    void exposesExactlyThePersistedCapabilityCodesInStableOrder() {
        assertThat(Arrays.stream(AiModelCapabilityCode.values())
                .map(Enum::name)
                .toList())
                .containsExactly(
                        "CHAT_COMPLETIONS",
                        "RESPONSES",
                        "WEB_SEARCH",
                        "IMAGE_INPUT",
                        "IMAGE_GENERATION",
                        "IMAGE_EDIT",
                        "AUDIO_INPUT",
                        "AUDIO_GENERATION",
                        "AUDIO_EDIT",
                        "VIDEO_INPUT",
                        "VIDEO_GENERATION",
                        "VIDEO_EDIT");
    }

    @Test
    void parsesCanonicalCodesAndRejectsRemovedAggregateCodes() {
        assertThat(AiModelCapabilityCode.fromExternalCode(" image_input "))
                .isEqualTo(AiModelCapabilityCode.IMAGE_INPUT);
        assertThat(AiModelCapabilityCode.fromExternalCode("VIDEO_EDIT"))
                .isEqualTo(AiModelCapabilityCode.VIDEO_EDIT);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AiModelCapabilityCode.fromExternalCode("IMAGE"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AiModelCapabilityCode.fromExternalCode("AUDIO"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AiModelCapabilityCode.fromExternalCode("VIDEO"));
    }
}
```

- [ ] **Step 2: 替换枚举常量并更新中文 JavaDoc**

```java
public enum AiModelCapabilityCode {
    CHAT_COMPLETIONS,
    RESPONSES,
    WEB_SEARCH,
    IMAGE_INPUT,
    IMAGE_GENERATION,
    IMAGE_EDIT,
    AUDIO_INPUT,
    AUDIO_GENERATION,
    AUDIO_EDIT,
    VIDEO_INPUT,
    VIDEO_GENERATION,
    VIDEO_EDIT;

    public static AiModelCapabilityCode fromExternalCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AI model capability code is required.");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unsupported AI model capability code.", exception);
        }
    }
}
```

JavaDoc 必须说明三类媒体能力互不推导，枚举名称同时是数据库和 HTTP 白名单。

- [ ] **Step 3: 强化 Mapper XML 静态契约**

在 `editingUsesOptimisticVersionAndNeverDeletesModelRows` 对 `capabilityMapper` 增加：

```java
assertThat(capabilityMapper)
        .contains("javaType=\"com.example.temperate.model.ai.enums.AiModelCapabilityCode\"")
        .contains("#{capability.capabilityCode,jdbcType=VARCHAR}")
        .contains("<foreach collection=\"capabilities\"")
        .doesNotContain("IMAGE_INPUT")
        .doesNotContain("IMAGE_GENERATION")
        .doesNotContain("IMAGE_EDIT");
```

后三项断言用于证明 XML 是通用映射，禁止把业务能力硬编码进 Mapper XML。

- [ ] **Step 4: 更新 Mapper 集成回滚用例**

将：

```java
capability(AiModelCapabilityCode.IMAGE)
```

改为一次批量写入三种不同能力，以证明 XML 无需分支：

```java
assertThat(capabilityMapper.insertBatch(java.util.List.of(
        capability(AiModelCapabilityCode.IMAGE_INPUT),
        capability(AiModelCapabilityCode.IMAGE_GENERATION),
        capability(AiModelCapabilityCode.IMAGE_EDIT))))
        .isEqualTo(3);
```

事务回滚后仍只允许读到原始 `RESPONSES`。

- [ ] **Step 5: 第二阶段经用户批准后运行枚举和 Mapper 测试**

```powershell
mvn -pl ai-temperate-model,ai-temperate-mapper -am -Dtest=AiModelCapabilityCodeTest,AiModelSchemaContractTest,AdminAiModelPatchIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

基础设施：`AdminAiModelPatchIntegrationTest` 使用 Testcontainers，需要本机 Docker 和隔离 PostgreSQL 容器；禁止连接生产数据库。

预期：枚举契约、XML 静态契约、三项能力批量写入和事务回滚全部通过。

- [ ] **Step 6: 可选原子提交**

```powershell
git add ai-temperate-model/src/main/java/com/example/temperate/model/ai/enums/AiModelCapabilityCode.java ai-temperate-model/src/test/java/com/example/temperate/model/ai/enums/AiModelCapabilityCodeTest.java ai-temperate-mapper/src/test/java/com/example/temperate/mapper/ai/AiModelSchemaContractTest.java ai-temperate-mapper/src/test/java/com/example/temperate/mapper/ai/AdminAiModelPatchIntegrationTest.java
git commit -m "feat(ai-model): define granular media capability enum"
```

---

### Task 3: 更新管理员 Service、缓存版本和次级 Java 消费者

**Files:**
- Modify: `ai-temperate-common/src/main/java/com/example/temperate/common/redis/key/RedisKeyFactory.java`
- Modify: `ai-temperate-common/src/test/java/com/example/temperate/common/redis/key/RedisKeyFactoryTest.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/cache/AiModelCacheSnapshot.java`
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/cache/impl/AiModelCacheServiceImplTest.java`
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/security/AiModelCacheProtectorTest.java`
- Review: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/service/impl/AdminAiModelServiceImpl.java`
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/service/impl/AdminAiModelServiceImplTest.java`
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/compaction/model/impl/AiConversationCompactionModelCatalogImplTest.java`

- [ ] **Step 1: 先更新 Service 测试输入和断言**

覆盖以下行为：

```java
List<String> allCapabilities = Arrays.stream(AiModelCapabilityCode.values())
        .map(Enum::name)
        .toList();
```

- 创建模型接受完整 12 项列表并只调用一次 `insertBatch`。
- `IMAGE`、`AUDIO`、`VIDEO` 返回 `AI_MODEL_CAPABILITY_INVALID`，且发生在数据库 I/O 之前。
- 详情的 `availableCapabilities` 与 `AiModelCapabilityCode.values()` 完全一致。
- PATCH 传入 `RESPONSES + IMAGE_INPUT + IMAGE_GENERATION + IMAGE_EDIT` 时，先乐观锁更新主表，再删除旧能力，再一次批量插入三行媒体能力。
- 列表和详情断言中的旧 `IMAGE` 替换为语义匹配的 `IMAGE_INPUT`。

新增核心断言示例：

```java
assertThat(result.availableCapabilities())
        .containsExactly(AiModelCapabilityCode.values());

assertThat(capturedCapabilities)
        .extracting(AiModelCapability::getCapabilityCode)
        .containsExactly(
                AiModelCapabilityCode.RESPONSES,
                AiModelCapabilityCode.IMAGE_INPUT,
                AiModelCapabilityCode.IMAGE_GENERATION,
                AiModelCapabilityCode.IMAGE_EDIT);
```

- [ ] **Step 2: 保持管理员 Service 的批量算法不变**

`normalizeCapabilities()` 继续使用：

```java
if (capabilities == null || capabilities.isEmpty()
        || capabilities.size() > AiModelCapabilityCode.values().length) {
    throw new AdminAiModelException(
            AdminAiModelErrorCode.AI_MODEL_CAPABILITY_INVALID,
            "AI model capabilities are invalid.");
}
```

创建仍调用一次 `capabilityMapper.insertBatch(capabilityRows)`；PATCH 仍在同一事务内执行：

```text
带 row_version 的主表更新
-> deleteByAiModelId
-> insertBatch（完整新集合）
-> 提交后刷新启用模型快照
```

禁止改成按能力循环调用 Mapper。

- [ ] **Step 3: 升级 Redis Key 和快照 Schema**

`RedisKeyFactory.aiModelEnabledSnapshotKey()` 改为：

```java
// v5 与十二项媒体能力语义同步，避免新应用反序列化含旧 IMAGE/AUDIO/VIDEO 枚举的密文。
return fixedKey("ai", "model", "v5", "enabled");
```

`AiModelCacheSnapshot` 改为：

```java
// v6 用输入、生成、编辑细分媒体能力替代旧聚合枚举，旧快照不得进入能力判断链路。
public static final int CURRENT_SCHEMA_VERSION = 6;
```

同步更新测试常量：

```java
private static final String CACHE_KEY = "ait:test:ai:model:v5:enabled";
```

旧 Schema 测试改为构造版本 `5`，新快照使用版本 `6`。缓存条目能力改为 `IMAGE_INPUT` 等新枚举。

- [ ] **Step 4: 更新次级 Java 测试引用**

- `AiModelCacheProtectorTest`：`IMAGE` 改为 `IMAGE_INPUT`。
- `AiConversationCompactionModelCatalogImplTest`：用于表示非对话媒体模型的 `VIDEO` 改为 `VIDEO_INPUT`。
- 全仓生产 Java 禁止残留 `AiModelCapabilityCode.IMAGE`、`.AUDIO`、`.VIDEO`。

静态搜索命令：

```powershell
rg -n "AiModelCapabilityCode\.(IMAGE|AUDIO|VIDEO)\b" ai-temperate-model ai-temperate-mapper ai-temperate-service ai-temperate-web
```

预期：退出码 1，表示没有旧枚举引用。

- [ ] **Step 5: 第二阶段经用户批准后运行 Service 和缓存测试**

```powershell
mvn -pl ai-temperate-common,ai-temperate-service -am -Dtest=RedisKeyFactoryTest,AdminAiModelServiceImplTest,AiModelCacheServiceImplTest,AiModelCacheProtectorTest,AiConversationCompactionModelCatalogImplTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：所有指定测试通过；Mockito 测试不连接数据库或 Redis。

- [ ] **Step 6: 可选原子提交**

```powershell
git add ai-temperate-common/src/main/java/com/example/temperate/common/redis/key/RedisKeyFactory.java ai-temperate-common/src/test/java/com/example/temperate/common/redis/key/RedisKeyFactoryTest.java ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/cache/AiModelCacheSnapshot.java ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/cache/impl/AiModelCacheServiceImplTest.java ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/security/AiModelCacheProtectorTest.java ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/service/impl/AdminAiModelServiceImplTest.java ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/compaction/model/impl/AiConversationCompactionModelCatalogImplTest.java
git commit -m "feat(ai-model): version granular capability cache"
```

---

### Task 4: 对齐管理员创建、PATCH 和响应契约

**Files:**
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/admin/controller/AdminAiModelController.java`
- Modify: `ai-temperate-web/src/test/java/com/example/temperate/web/admin/controller/AdminAiModelControllerContractTest.java`
- Modify: `ai-temperate-web/src/test/java/com/example/temperate/web/admin/controller/AdminAiModelControllerPatchTest.java`
- Modify: `ai-temperate-web/src/test/java/com/example/temperate/web/admin/aimodel/AdminAiModelMergePatchMapperTest.java`

- [ ] **Step 1: 先更新 HTTP 契约测试**

`exposesEveryCapabilityCodeInRequestValidationAndOpenApi` 已从枚举动态生成期望值，保留该设计；枚举变为 12 项后，它必须验证：

```java
assertThat(size.max()).isEqualTo(12);
assertThat(arraySchema.schema().allowableValues())
        .containsExactly(
                "CHAT_COMPLETIONS",
                "RESPONSES",
                "WEB_SEARCH",
                "IMAGE_INPUT",
                "IMAGE_GENERATION",
                "IMAGE_EDIT",
                "AUDIO_INPUT",
                "AUDIO_GENERATION",
                "AUDIO_EDIT",
                "VIDEO_INPUT",
                "VIDEO_GENERATION",
                "VIDEO_EDIT");
```

PATCH 测试请求改成：

```json
{
  "description": null,
  "capabilities": [
    "RESPONSES",
    "IMAGE_INPUT",
    "IMAGE_GENERATION"
  ]
}
```

Merge Patch Mapper 测试继续证明 `capabilities` 是字符串数组且保持顺序，不在 Web 层静默改名。

- [ ] **Step 2: 更新创建请求注解**

`AdminAiModelController.CreateRequest.capabilities()` 使用：

```java
@ArraySchema(schema = @Schema(
        type = "string",
        description = "模型支持的单项 API 或媒体能力代码",
        allowableValues = {
                "CHAT_COMPLETIONS",
                "RESPONSES",
                "WEB_SEARCH",
                "IMAGE_INPUT",
                "IMAGE_GENERATION",
                "IMAGE_EDIT",
                "AUDIO_INPUT",
                "AUDIO_GENERATION",
                "AUDIO_EDIT",
                "VIDEO_INPUT",
                "VIDEO_GENERATION",
                "VIDEO_EDIT"
        }))
@NotEmpty
@Size(max = 12)
List<@NotBlank @Size(max = 64) String> capabilities
```

请求 DTO 类型、`toCommand()` 和 JSON 字段名不变。

- [ ] **Step 3: 固定请求和响应示例**

创建请求示例：

```json
{
  "modelName": "gpt-5.6-luna",
  "description": "支持图片输入的 Responses 模型",
  "iconPublicId": null,
  "tags": ["chat", "reasoning", "vision"],
  "vendor": "openai",
  "inputRatio": 1,
  "cachedInputRatio": 1,
  "outputRatio": 1,
  "contextWindowK": 256,
  "maxOutputK": 32,
  "enabled": true,
  "capabilities": [
    "RESPONSES",
    "WEB_SEARCH",
    "IMAGE_INPUT"
  ]
}
```

PATCH 请求示例，字段出现即完整替换：

```http
PATCH /api/admin/ai-models/AAAAAAAAAAA
Content-Type: application/merge-patch+json
If-Match: "3"
```

```json
{
  "capabilities": [
    "RESPONSES",
    "WEB_SEARCH",
    "IMAGE_INPUT",
    "IMAGE_GENERATION"
  ]
}
```

详情响应结构不增加布尔字段：

```json
{
  "publicId": "AAAAAAAAAAA",
  "modelName": "gpt-5.6-luna",
  "capabilities": [
    "RESPONSES",
    "WEB_SEARCH",
    "IMAGE_INPUT"
  ],
  "availableCapabilities": [
    "CHAT_COMPLETIONS",
    "RESPONSES",
    "WEB_SEARCH",
    "IMAGE_INPUT",
    "IMAGE_GENERATION",
    "IMAGE_EDIT",
    "AUDIO_INPUT",
    "AUDIO_GENERATION",
    "AUDIO_EDIT",
    "VIDEO_INPUT",
    "VIDEO_GENERATION",
    "VIDEO_EDIT"
  ]
}
```

`availableCapabilities` 继续由 `List.of(AiModelCapabilityCode.values())` 生成，无需新增响应字段。

- [ ] **Step 4: 第二阶段经用户批准后运行 Web 契约测试**

```powershell
mvn -pl ai-temperate-web -am -Dtest=AdminAiModelControllerContractTest,AdminAiModelControllerPatchTest,AdminAiModelMergePatchMapperTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：创建请求最多 12 项、OpenAPI 顺序与枚举一致、PATCH 数组完整透传、响应序列化仍输出字符串数组。

- [ ] **Step 5: 可选原子提交**

```powershell
git add ai-temperate-web/src/main/java/com/example/temperate/web/admin/controller/AdminAiModelController.java ai-temperate-web/src/test/java/com/example/temperate/web/admin/controller/AdminAiModelControllerContractTest.java ai-temperate-web/src/test/java/com/example/temperate/web/admin/controller/AdminAiModelControllerPatchTest.java ai-temperate-web/src/test/java/com/example/temperate/web/admin/aimodel/AdminAiModelMergePatchMapperTest.java
git commit -m "feat(ai-model): expose granular capability contract"
```

---

### Task 5: 更新管理员前端能力矩阵和提交校验

**Files:**
- Modify: `myuniappadmin/common/admin/admin-ai-model-form.js`
- Modify: `myuniappadmin/common/admin/admin-ai-model-form.test.cjs`
- Modify: `myuniappadmin/components/admin/ai-model-form.vue`
- Modify: `myuniappadmin/common/admin/admin-request-body.test.cjs`

- [ ] **Step 1: 先更新前端表单契约测试**

固定完整选项顺序：

```javascript
assert.deepEqual(AI_MODEL_CAPABILITY_OPTIONS.map(item => item.code), [
  'CHAT_COMPLETIONS',
  'RESPONSES',
  'WEB_SEARCH',
  'IMAGE_INPUT',
  'IMAGE_GENERATION',
  'IMAGE_EDIT',
  'AUDIO_INPUT',
  'AUDIO_GENERATION',
  'AUDIO_EDIT',
  'VIDEO_INPUT',
  'VIDEO_GENERATION',
  'VIDEO_EDIT'
])
```

把创建、编辑和 Merge Patch 测试中的 `IMAGE` 改成精确组合：

```javascript
capabilities: ['RESPONSES', 'IMAGE_INPUT', 'IMAGE_GENERATION']
```

新增拒绝旧值的测试：

```javascript
for (const removed of ['IMAGE', 'AUDIO', 'VIDEO']) {
  const result = validateAiModelForm({
    ...validBase,
    capabilities: ['RESPONSES', removed]
  })
  assert.equal(result.valid, false)
  assert.equal(Object.hasOwn(result.errors, 'capabilities'), true)
}
```

- [ ] **Step 2: 定义分组能力元数据**

`admin-ai-model-form.js` 导出：

```javascript
export const AI_MODEL_CAPABILITY_GROUPS = Object.freeze([
  Object.freeze({
    code: 'protocol',
    label: '协议与工具',
    options: Object.freeze([
      Object.freeze({ code: 'CHAT_COMPLETIONS', label: 'Chat Completions', hint: '兼容对话补全协议' }),
      Object.freeze({ code: 'RESPONSES', label: 'Responses', hint: '统一响应与工具调用' }),
      Object.freeze({ code: 'WEB_SEARCH', label: '联网搜索', hint: 'Responses 托管 web_search 工具' })
    ])
  }),
  Object.freeze({
    code: 'image',
    label: '图像',
    options: Object.freeze([
      Object.freeze({ code: 'IMAGE_INPUT', label: '图片输入', hint: '接收并理解用户上传的图片' }),
      Object.freeze({ code: 'IMAGE_GENERATION', label: '图片生成', hint: '根据请求生成新的图片' }),
      Object.freeze({ code: 'IMAGE_EDIT', label: '图片编辑', hint: '编辑已有图片内容' })
    ])
  }),
  Object.freeze({
    code: 'audio',
    label: '音频',
    options: Object.freeze([
      Object.freeze({ code: 'AUDIO_INPUT', label: '音频输入', hint: '接收并理解用户上传的音频' }),
      Object.freeze({ code: 'AUDIO_GENERATION', label: '音频生成', hint: '生成语音或其他音频' }),
      Object.freeze({ code: 'AUDIO_EDIT', label: '音频编辑', hint: '编辑已有音频内容' })
    ])
  }),
  Object.freeze({
    code: 'video',
    label: '视频',
    options: Object.freeze([
      Object.freeze({ code: 'VIDEO_INPUT', label: '视频输入', hint: '接收并理解用户上传的视频' }),
      Object.freeze({ code: 'VIDEO_GENERATION', label: '视频生成', hint: '根据请求生成新视频' }),
      Object.freeze({ code: 'VIDEO_EDIT', label: '视频编辑', hint: '编辑已有视频内容' })
    ])
  })
])

export const AI_MODEL_CAPABILITY_OPTIONS = Object.freeze(
  AI_MODEL_CAPABILITY_GROUPS.flatMap(group => group.options)
)
```

`CAPABILITY_CODES`、`orderedCapabilities()`、`validateAiModelForm()` 和 `createMergePatch()` 继续从扁平选项生成白名单和稳定顺序。

- [ ] **Step 3: 按组渲染能力矩阵**

`ai-model-form.vue` 导入两个常量，并在 `data()` 中保存分组：

```javascript
import {
  AI_MODEL_CAPABILITY_GROUPS,
  AI_MODEL_CAPABILITY_OPTIONS,
  cloneAiModelForm
} from '@/common/admin/admin-ai-model-form.js'

data() {
  return {
    capabilityGroups: AI_MODEL_CAPABILITY_GROUPS,
    capabilityOptions: AI_MODEL_CAPABILITY_OPTIONS,
    iconSearch: '',
    advancedOpen: false
  }
}
```

模板按组渲染，但按钮仍调用现有 `toggleCapability(option.code)`：

```vue
<view class="capability-groups">
  <view
    v-for="group in capabilityGroups"
    :key="group.code"
    class="capability-group"
    role="group"
    :aria-label="group.label"
  >
    <text class="capability-group-label">{{ group.label }}</text>
    <view class="capability-grid">
      <button
        v-for="option in group.options"
        :key="option.code"
        class="capability-option"
        :class="{ selected: selected(option.code) }"
        type="button"
        :disabled="readonly || busy"
        :aria-pressed="selected(option.code)"
        @click="toggleCapability(option.code)"
      >
        <view class="capability-indicator" aria-hidden="true">
          <view class="capability-dot" />
        </view>
        <view>
          <text class="capability-label">{{ option.label }}</text>
          <text class="capability-hint">{{ option.hint }}</text>
        </view>
      </button>
    </view>
  </view>
</view>
```

补充最小样式：

```scss
.capability-groups { display: grid; gap: 18rpx; }
.capability-group-label {
  display: block;
  margin-bottom: 10rpx;
  color: $app-muted;
  font-size: 22rpx;
  font-weight: 720;
}
```

- [ ] **Step 4: 保持前端请求结构不变**

新增时 `validateAiModelForm().command.capabilities` 仍是完整有序数组；编辑时 `createMergePatch()` 只在数组变化时发送：

```json
{
  "capabilities": [
    "RESPONSES",
    "IMAGE_INPUT",
    "IMAGE_GENERATION"
  ]
}
```

禁止新增 `imageInput: true`、`imageGeneration: true` 等重复布尔字段。

- [ ] **Step 5: 第二阶段经用户批准后运行管理员前端测试**

```powershell
node --test myuniappadmin/common/admin/admin-ai-model-form.test.cjs myuniappadmin/common/admin/admin-request-body.test.cjs
```

预期：12 项顺序、旧值拒绝、创建命令和完整替换 Patch 全部通过。

- [ ] **Step 6: 可选原子提交**

```powershell
git add myuniappadmin/common/admin/admin-ai-model-form.js myuniappadmin/common/admin/admin-ai-model-form.test.cjs myuniappadmin/components/admin/ai-model-form.vue myuniappadmin/common/admin/admin-request-body.test.cjs
git commit -m "feat(admin): expose granular media capability matrix"
```

---

### Task 6: 把用户聊天附件校验切换到输入能力

**Files:**
- Modify carefully: `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java`
- Modify: `fornted/common/aichat/ai-conversation-upload-state.js`
- Modify: `fornted/common/aichat/ai-conversation-upload-state.test.cjs`
- Review unchanged: `fornted/common/aichat/ai-conversation-api.js`

- [ ] **Step 1: 先更新用户前端附件测试**

```javascript
assert.equal(
  state.isAttachmentCompatible(image, { capabilities: ['IMAGE_INPUT'] }),
  true
)
assert.equal(
  state.isAttachmentCompatible(image, { capabilities: ['IMAGE_GENERATION'] }),
  false
)
assert.equal(
  state.isAttachmentCompatible(audio, { capabilities: ['AUDIO_INPUT'] }),
  true
)
assert.equal(
  state.isAttachmentCompatible(video, { capabilities: ['VIDEO_INPUT'] }),
  true
)
```

发送门禁测试中的模型改为：

```javascript
const model = { capabilities: ['IMAGE_INPUT'] }
```

- [ ] **Step 2: 修改前端媒体类别到能力的映射**

保留附件类别 `IMAGE/AUDIO/VIDEO`，因为它描述文件类别，不是模型能力；只替换能力映射：

```javascript
export function requiredMediaCapability(file) {
  switch (attachmentCategory(file)) {
    case 'IMAGE':
      return 'IMAGE_INPUT'
    case 'AUDIO':
      return 'AUDIO_INPUT'
    case 'VIDEO':
      return 'VIDEO_INPUT'
    default:
      return null
  }
}
```

`isAttachmentCompatible()` 和 `deriveSendGate()` 保持通用集合判断。

- [ ] **Step 3: 最小合并后端附件能力映射**

只修改 `AiConversationResponseServiceImpl.validateAttachmentCapabilities()` 中的 switch：

```java
AiModelCapabilityCode required = switch (attachment.category()) {
    case IMAGE -> AiModelCapabilityCode.IMAGE_INPUT;
    case AUDIO -> AiModelCapabilityCode.AUDIO_INPUT;
    case VIDEO -> AiModelCapabilityCode.VIDEO_INPUT;
    case DOCUMENT, ARCHIVE, OTHER -> null;
};
```

实施前执行：

```powershell
git diff -- ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java
```

该文件已有用户关于活动事件去重的未提交修改。只允许修改三行枚举映射；禁止格式化整文件、覆盖用户差异或回退其他逻辑。

- [ ] **Step 4: 明确不改变附件 API 类别**

`ai-conversation-api.js` 的附件类别继续是：

```javascript
new Set(['IMAGE', 'AUDIO', 'VIDEO', 'DOCUMENT', 'ARCHIVE', 'OTHER'])
```

附件类别和模型能力属于不同概念，禁止把上传对象的 `category` 改成 `IMAGE_INPUT`。

- [ ] **Step 5: 第二阶段经用户批准后运行用户前端测试**

```powershell
node --test fornted/common/aichat/ai-conversation-upload-state.test.cjs fornted/common/aichat/ai-conversation-api.test.cjs
```

预期：图片生成能力不能冒充图片输入能力；三个 `*_INPUT` 分别允许对应媒体；文档类附件不需要媒体能力。

- [ ] **Step 6: 第二阶段经用户批准后运行相关 Service 契约**

```powershell
mvn -pl ai-temperate-service -am -Dtest=SpringAiCliProxyConversationModelClientContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：附件能力错误码保持 `AI_ATTACHMENT_CAPABILITY_UNSUPPORTED`，模型客户端契约通过。

- [ ] **Step 7: 可选原子提交**

```powershell
git add ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/response/impl/AiConversationResponseServiceImpl.java fornted/common/aichat/ai-conversation-upload-state.js fornted/common/aichat/ai-conversation-upload-state.test.cjs
git commit -m "fix(ai-chat): gate media attachments by input capability"
```

---

### Task 7: 全仓旧能力清理、文档收敛和第一阶段交付审阅

**Files:**
- Modify as found: capability-related tests containing removed enum names
- Modify: `docs/database/ai-model.md`
- Review: all modified files from Tasks 1-6

- [ ] **Step 1: 搜索旧数据库能力值**

```powershell
rg -n -g '!target/**' -g '!node_modules/**' -e "'IMAGE'" -e "'AUDIO'" -e "'VIDEO'" sql ai-temperate-model ai-temperate-mapper ai-temperate-service ai-temperate-web myuniappadmin fornted docs
```

允许保留的位置只有：

- `023_split_ai_model_media_capabilities.sql` 的旧值迁移逻辑。
- 附件文件类别 `IMAGE/AUDIO/VIDEO`。
- 测试旧能力被拒绝的输入。
- 历史 `022_add_ai_model_web_search_capability.sql` 及其明确标注为历史的契约测试。

其余能力白名单、枚举、管理员表单和模型缓存中的旧值必须删除。

- [ ] **Step 2: 搜索错误的新名称**

```powershell
rg -n -g '!target/**' -g '!node_modules/**' -e 'IMAGE_EDITING' -e 'IMAGE_CREATOR' -e 'IMAGE_USE' -e 'AUDIO_EDITING' -e 'VIDEO_EDITING' .
```

预期：退出码 1，无匹配。

- [ ] **Step 3: 审阅架构约束**

逐项确认：

```text
Mapper XML 没有能力专用 if/switch，也没有逐条数据库 I/O。
Service 创建使用一次 insertBatch。
PATCH 仍在一个 PostgreSQL 本地事务中完成主表更新、旧能力删除和新能力批量插入。
没有物理 FOREIGN KEY/REFERENCES。
数据库和 API 仍使用 capability_code/ capabilities 数组，而不是 12 个布尔字段。
生成和编辑能力没有被 INPUT 或 RESPONSES 自动授予。
缓存 Key/Schema 已升级，旧枚举密文不会进入新能力判断。
Java 新增或修改的非直观逻辑有紧邻中文注释和顶级类型中文 JavaDoc。
当前用户未提交改动没有被覆盖。
```

- [ ] **Step 4: 第一阶段静态差异检查**

```powershell
git diff --check
git status --short
git diff -- sql ai-temperate-model ai-temperate-mapper ai-temperate-common ai-temperate-service ai-temperate-web myuniappadmin fornted docs/database/ai-model.md
```

预期：`git diff --check` 退出码 0；差异只包含计划范围和进入任务前已经存在的用户改动。第一阶段交付说明必须明确列出所有未运行测试。

---

### Task 8: 第二阶段隔离验证和部署顺序

**Files:**
- No source changes expected
- Evidence: Maven/Node test output,隔离 PostgreSQL migration output, Redis test evidence

- [ ] **Step 1: 在执行前向用户说明并确认测试范围**

必须说明：

```text
Node 单元测试只读本地源码，不连接外部服务。
Java 单元/契约测试使用 Mockito 或本地测试上下文。
Mapper 集成测试使用 Testcontainers，并写入随后销毁的隔离 PostgreSQL 容器。
迁移演练会写入隔离测试数据库的 ai_model_capability 表。
不连接生产 PostgreSQL、生产 Redis、生产 RabbitMQ 或生产外部 API。
```

- [ ] **Step 2: 运行管理员与用户前端定向测试**

```powershell
node --test myuniappadmin/common/admin/admin-ai-model-form.test.cjs myuniappadmin/common/admin/admin-request-body.test.cjs fornted/common/aichat/ai-conversation-upload-state.test.cjs fornted/common/aichat/ai-conversation-api.test.cjs
```

预期：全部通过，失败数为 0。

- [ ] **Step 3: 运行 Java 定向测试**

```powershell
mvn -pl ai-temperate-web -am -Dtest=AiModelCapabilityCodeTest,AiModelSchemaContractTest,AdminAiModelPatchIntegrationTest,RedisKeyFactoryTest,AdminAiModelServiceImplTest,AiModelCacheServiceImplTest,AiModelCacheProtectorTest,AiConversationCompactionModelCatalogImplTest,AdminAiModelControllerContractTest,AdminAiModelControllerPatchTest,AdminAiModelMergePatchMapperTest,SpringAiCliProxyConversationModelClientContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：指定测试全部通过；Testcontainers PostgreSQL 正常启动和销毁。

- [ ] **Step 4: 在隔离 PostgreSQL 演练 023 迁移**

准备至少以下数据：

```text
模型 A：RESPONSES + IMAGE
模型 B：RESPONSES + IMAGE + IMAGE_INPUT（模拟新旧重复）
模型 C：CHAT_COMPLETIONS + AUDIO + VIDEO
模型 D：RESPONSES + IMAGE_GENERATION（已经使用新能力）
```

执行 023 后验证：

```sql
SELECT ai_model_id, capability_code
FROM ai_model_capability
ORDER BY ai_model_id, capability_code;
```

预期：

```text
不存在 IMAGE/AUDIO/VIDEO。
模型 A 只有 RESPONSES + IMAGE_INPUT。
模型 B 的 IMAGE_INPUT 只有一行。
模型 C 是 CHAT_COMPLETIONS + AUDIO_INPUT + VIDEO_INPUT。
模型 D 保留 RESPONSES + IMAGE_GENERATION，不被增加 IMAGE_INPUT 或 IMAGE_EDIT。
```

检查约束拒绝旧值：

```sql
INSERT INTO ai_model_capability (id, ai_model_id, capability_code)
VALUES (900000000000000001, 1, 'IMAGE');
```

预期：因 `chk_ai_model_capability_code` 失败；该语句只允许在回滚事务或可销毁测试数据库中执行。

- [ ] **Step 5: 经用户单独批准后运行完整验证候选项**

```powershell
mvn clean verify
mvn dependency:tree
```

预期：必须基于新输出分别报告通过、失败、跳过和未执行项目，禁止用定向测试结果代替完整验证。

- [ ] **Step 6: 固定部署顺序**

```text
1. 备份并核对现有 ai_model_capability 旧值分布。
2. 将旧后端实例从入口摘除并停止其继续读取或写入模型能力。
3. 在目标环境执行 023 迁移；失败时事务回滚，禁止启动新后端。
4. 部署并启动包含 12 项枚举、API、Service 和缓存 v5/v6 的后端。
5. 后端健康检查和模型目录只读检查通过后恢复 API 流量。
6. 部署管理员前端 12 项能力矩阵。
7. 部署用户前端 *_INPUT 附件门禁。
8. 从 PostgreSQL 重建新 v5 启用模型快照，不读取旧 v4 Key。
9. 验证 GPT 等具体模型只获得供应商真实支持的能力，不按名称自动推断。
10. 观察能力保存错误、缓存快照拒绝和附件能力不支持指标。
```

数据库迁移与后端应处于同一维护发布窗口；旧实例停止后才能迁移，迁移成功后才能启动新实例。若业务不能接受该短暂停机窗口，必须另行设计“先兼容读旧值但只写新值、再迁移、最后删除旧枚举”的三阶段发布，本计划不把该兼容方案隐含进单次发布。

## 完成标准

- 基础 SQL 和 023 迁移的最终白名单都是精确 12 项。
- 旧媒体能力只收敛到对应 `*_INPUT`，不自动增加生成或编辑权限。
- Java 枚举、OpenAPI、管理员前端顺序完全一致。
- Mapper XML 仍为通用批量映射且不含能力硬编码。
- 创建和 PATCH 能保存任意合法能力组合，并拒绝旧值、未知值和重复值。
- 管理员响应继续以 `capabilities`/`availableCapabilities` 数组返回能力。
- 用户前后端只用 `*_INPUT` 判断媒体附件兼容性。
- Redis 启用模型快照使用 Key `v5` 和 Schema `6`。
- 全仓旧值只存在于迁移、历史脚本、附件类别和拒绝测试中。
- 第一阶段明确报告测试未执行；第二阶段只有经用户批准并产生新证据后才可声明测试结果。
