# Alibaba Qwen Trusted SVG Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `ALIBABA_QWEN` as the eighth trusted official SVG vendor and trust only `qwen.ai` plus its true DNS subdomains.

**Architecture:** Extend the existing enum-backed trusted-origin Registry without creating a parallel matcher. The final response host continues to pass through normalized DNS point-boundary matching, cross-vendor overlap validation, and the existing `TRUSTED_OFFICIAL` SVG policy.

**Tech Stack:** Java 21, Spring Boot configuration properties, JUnit 5, AssertJ, Maven, YAML.

---

### Task 1: Define the failing Qwen Registry contract

**Files:**
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/icon/remote/AiModelIconTrustedOriginRegistryTest.java`

- [ ] **Step 1: Add Qwen expectations before production support exists**

Add Qwen to the exact/subdomain test:

```java
assertThat(registry.resolve("qwen.ai").trustedVendor())
        .isEqualTo(AiModelIconVendor.ALIBABA_QWEN);
assertThat(registry.resolve("assets.qwen.ai").trustedVendor())
        .isEqualTo(AiModelIconVendor.ALIBABA_QWEN);
```

Add lookalike rejection assertions:

```java
assertThat(registry.resolve("qwen.ai.attacker.test").svgPolicyProfile())
        .isEqualTo(AiModelIconSvgPolicyProfile.STRICT);
assertThat(registry.resolve("evil-qwen.ai").svgPolicyProfile())
        .isEqualTo(AiModelIconSvgPolicyProfile.STRICT);
```

Append `List.of("qwen.ai")` to every `TrustedHosts` constructor in the test so the test fixture models all eight required vendors.

- [ ] **Step 2: Run the Registry test and verify RED**

Run:

```powershell
mvn -pl ai-temperate-service -am "-Dtest=AiModelIconTrustedOriginRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: test compilation fails because `ALIBABA_QWEN` and the eighth `TrustedHosts` component do not exist.

- [ ] **Step 3: Commit the failing contract**

```powershell
git add -- ai-temperate-service/src/test/java/com/example/temperate/service/admin/aimodel/icon/remote/AiModelIconTrustedOriginRegistryTest.java
git commit -m "test: define trusted Qwen SVG origin"
```

### Task 2: Implement the eighth trusted vendor

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/remote/config/AiModelIconVendor.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/remote/config/AiModelIconRemoteSvgProperties.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/remote/AiModelIconTrustedOriginRegistry.java`

- [ ] **Step 1: Add the stable vendor enum**

Append the enum value:

```java
ALIBABA_QWEN
```

- [ ] **Step 2: Add the required Qwen host component**

Append the record component:

```java
@NotEmpty List<@NotBlank String> qwen
```

Update the type JavaDoc from seven vendors to eight vendors.

- [ ] **Step 3: Register Qwen in the immutable EnumMap**

Add:

```java
normalized.put(AiModelIconVendor.ALIBABA_QWEN, normalize(hosts.qwen()));
```

Keep the existing cross-vendor overlap check and point-boundary matcher unchanged.

- [ ] **Step 4: Run the Registry test and verify GREEN**

Run:

```powershell
mvn -pl ai-temperate-service -am "-Dtest=AiModelIconTrustedOriginRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: all `AiModelIconTrustedOriginRegistryTest` cases pass with no failures or errors.

- [ ] **Step 5: Commit the implementation**

```powershell
git add -- ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/remote/config/AiModelIconVendor.java ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/remote/config/AiModelIconRemoteSvgProperties.java ai-temperate-service/src/main/java/com/example/temperate/service/admin/aimodel/icon/remote/AiModelIconTrustedOriginRegistry.java
git commit -m "feat: trust official Qwen SVG origins"
```

### Task 3: Bind the Qwen host in production and test configuration

**Files:**
- Modify: `ai-temperate-web/src/main/resources/application.yml`
- Modify: `ai-temperate-web/src/test/resources/application-test.yml`

- [ ] **Step 1: Add the production-safe YAML default**

Under `ai-model-icon.remote-svg.trusted-hosts`, append:

```yaml
# 通义千问仅信任 qwen.ai 点边界子域，不扩大到阿里共享 CDN。
qwen: ${AI_MODEL_ICON_TRUSTED_QWEN_HOSTS:qwen.ai}
```

- [ ] **Step 2: Add the isolated test binding**

Under the corresponding test node, append:

```yaml
# 通义千问测试主机只用于属性绑定，不发起真实网络请求。
qwen: qwen.ai
```

- [ ] **Step 3: Run the isolated Spring binding test**

Run:

```powershell
mvn -pl ai-temperate-web -am "-Dtest=AiTemperateApplicationTest#bindsValidatedTestInfrastructureAndHasSingletonBeans" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: the Spring test context starts and the selected binding test passes without connecting to PostgreSQL, Redis, RabbitMQ, OSS, or Qwen.

- [ ] **Step 4: Commit configuration**

```powershell
git add -- ai-temperate-web/src/main/resources/application.yml ai-temperate-web/src/test/resources/application-test.yml
git commit -m "config: bind trusted Qwen SVG host"
```

### Task 4: Synchronize trusted-vendor documentation

**Files:**
- Modify: `docs/superpowers/plans/2026-07-27-official-ai-vendor-svg-external-url.md`

- [ ] **Step 1: Update the vendor matrix and examples**

Add:

```text
ALIBABA_QWEN: qwen.ai
```

Append `ALIBABA_QWEN` to the documented enum and add:

```yaml
# 通义千问仅信任 qwen.ai 点边界子域。
qwen: ${AI_MODEL_ICON_TRUSTED_QWEN_HOSTS:qwen.ai}
```

Change references from seven trusted vendors to eight trusted vendors.

- [ ] **Step 2: Run documentation and whitespace checks**

Run:

```powershell
rg -n "ALIBABA_QWEN|AI_MODEL_ICON_TRUSTED_QWEN_HOSTS|qwen.ai" docs/superpowers/plans/2026-07-27-official-ai-vendor-svg-external-url.md
git diff --check
```

Expected: all three Qwen identifiers are present and `git diff --check` reports no whitespace errors.

- [ ] **Step 3: Commit documentation**

```powershell
git add -- docs/superpowers/plans/2026-07-27-official-ai-vendor-svg-external-url.md
git commit -m "docs: document trusted Qwen SVG origin"
```

### Task 5: Run focused final verification

**Files:**
- Verify all files modified by Tasks 1–4.

- [ ] **Step 1: Run the trusted-origin and SVG policy tests**

Run:

```powershell
mvn -pl ai-temperate-service -am "-Dtest=AiModelIconTrustedOriginRegistryTest,SvgAiModelIconImageValidationStrategyTest,AiModelIconRemoteImageValidatorImplTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: all selected tests pass with no failures or errors.

- [ ] **Step 2: Run the isolated Spring configuration test**

Run:

```powershell
mvn -pl ai-temperate-web -am "-Dtest=AiTemperateApplicationTest#bindsValidatedTestInfrastructureAndHasSingletonBeans" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: one Spring context test passes.

- [ ] **Step 3: Inspect the final scoped diff**

Run:

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors; only the intended Qwen changes plus the user's pre-existing working-tree changes remain.

The repository-wide `mvn clean verify` is not the completion gate for this increment because it currently fails earlier in `CommonDependencyContractTest` over the pre-existing `libphonenumber` contract conflict. Real Qwen URL, Chrome, Clash, Cloudflare, OSS, PostgreSQL, Redis, and RabbitMQ verification remain outside this plan.
