# FC Java Runtime Bootstrap Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `xai-video-transfer` 在 FC `custom.debian11` Web Function 中稳定启动 Linux Java 11，并由同一个 Maven 打包命令生成可部署目录。

**Architecture:** 保留现有 9000 端口 HTTP Server 与 NDJSON 进度协议。Maven 固定下载 Eclipse Temurin Linux x64 JRE 11 归档并校验 SHA-256，部署包携带压缩归档；`bootstrap` 在冷启动时解压至 `/tmp` 后使用绝对路径启动 Shade JAR，避免 Windows 打包丢失 Linux 可执行权限。

**Tech Stack:** Maven 3、maven-antrun-plugin、POSIX shell、Eclipse Temurin JRE 11、阿里云 FC `custom.debian11`。

---

### Task 1: 固化启动失败契约

**Files:**
- Create: `functions/xai-video-transfer/src/test/java/com/example/temperate/functions/video/FcDeploymentPackageContractTest.java`

- [ ] **Step 1: 编写失败契约测试**

测试源 `bootstrap` 必须引用随包携带的 Linux JRE 归档及 `/tmp` 中的绝对 Java 路径，`s.yaml` 必须只部署 `target/fc-deploy`。

- [ ] **Step 2: 在用户授权的第二阶段运行测试并确认旧配置失败**

Run: `mvn -f functions/xai-video-transfer/pom.xml -Dtest=FcDeploymentPackageContractTest test`

Expected: FAIL，因为当前 `bootstrap` 使用裸 `java`，且 `s.yaml` 指向整个 `target`。

### Task 2: 生成自包含 FC 部署包

**Files:**
- Modify: `functions/xai-video-transfer/bootstrap`
- Modify: `functions/xai-video-transfer/pom.xml`

- [ ] **Step 1: 固定 Linux JRE 供应链信息**

在 POM 中固定 Temurin `11.0.28+6` Linux x64 JRE 下载地址与 SHA-256 `ddbd5d7ef14aa06784fb94d1e0e7177868dfdd0aa216a8a2e654869968ef7392`。

- [ ] **Step 2: 在 package 阶段构建部署目录**

Shade JAR 完成后，下载 JRE 归档、校验 SHA-256，并将 `bootstrap`、Shade JAR、JRE 归档复制到 `target/fc-deploy`，同时生成 `target/xai-video-transfer-deploy.zip`。

- [ ] **Step 3: 使用绝对 Java 路径启动**

`bootstrap` 将 JRE 解压至 `/tmp/xai-video-transfer-java11`，然后执行：

```sh
exec /tmp/xai-video-transfer-java11/bin/java \
  --add-modules jdk.httpserver \
  -jar /code/xai-video-transfer-0.0.1-SNAPSHOT.jar
```

### Task 3: 收紧部署输入

**Files:**
- Modify: `functions/xai-video-transfer/s.yaml`
- Modify: `functions/xai-video-transfer/README.md`

- [ ] **Step 1: 只上传生成的部署目录**

将 `code` 从 `./target` 改为 `./target/fc-deploy`，避免测试报告、classes 和原始 JAR 被误传。

- [ ] **Step 2: 记录运行时边界**

说明 `custom.debian11` 不提供 Java，部署包必须携带经过哈希校验的 Linux x64 JRE 11。

### Task 4: 验证与部署

- [ ] **Step 1: 在用户授权的第二阶段运行定向测试**

Run: `mvn -f functions/xai-video-transfer/pom.xml -Dtest=FcDeploymentPackageContractTest test`

Expected: PASS。

- [ ] **Step 2: 在用户授权的第二阶段生成部署包**

Run: `mvn -f functions/xai-video-transfer/pom.xml -DskipTests package`

Expected: `target/fc-deploy` 包含三个文件，且 ZIP 中也包含相同文件。

- [ ] **Step 3: 重新部署并读取 SLS**

部署 `target/xai-video-transfer-deploy.zip` 后调用一次函数；SLS 不再出现 `exec: java: not found` 或退出码 `127`，并出现 Web Server 启动或请求处理日志。
