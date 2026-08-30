# Google Android 原生凭据桥接修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Android Google 登录在账号选择后可靠进入 UTS 成功、取消或失败回调，最多等待 30 秒，并确认只有拿到 ID Token 后才调用后端完成接口。

**Architecture:** 保持现有 UniApp 页面和后端接口不变，先在 UTS 模块中强持有 Credential Manager 请求会话，避免异步回调对象、CancellationSignal 或请求状态在 Google 界面返回前失效。若该最小修复不能恢复回调，再把 Credential Manager 的 suspend API 隔离到手写 Kotlin 桥接类；依赖版本调整只能作为第三个、单独验证的假设。

**Tech Stack:** UniApp App-Plus、UTS、Kotlin、AndroidX Credential Manager 1.6.0、Google ID 1.2.0、ADB Logcat。

---

## 已确认的证据和边界

- 真机连续两次出现 `native_android_request_begin`。
- Google Play 服务出现 `GetGoogleIdOperation Operation succeeded`。
- 同一次请求没有出现 `native_android_result`、`native_android_error` 或 `native_android_cancel`。
- 因此 JavaScript 没有收到 ID Token，也没有进入 `native_complete_begin`。
- `/api/auth/oauth2/google/native/complete` 以及后端 Token 校验不在当前故障路径中。
- `BadAuthentication`/`UNAUTHENTICATED` 出现在自动登录资格辅助检查中；主 `GetGoogleIdOperation` 随后成功，不能把该警告单独当作最终失败。

## 文件结构

- 修改：`fornted/uni_modules/ait-google-signin/utssdk/app-android/index.uts`
  - 负责原生请求会话的强引用、一次性结算、取消和阶段日志。
- 修改：`fornted/common/auth/oauth-contract.test.cjs`
  - 静态检查请求会话被强持有、旧请求被取消、回调签名和安全日志没有改变。
- 条件创建：`HBuilder-HelloUniApp/uni_modules/ait-google-signin/utssdk/app-android/src/GoogleCredentialBridge.kt`
  - 仅在 UTS 会话强引用方案仍无法收到回调时创建；用原生 Kotlin coroutine 隔离 UTS 异步边界。
- 修改：`HBuilder-HelloUniApp/uni_modules/ait-google-signin/utssdk/app-android/src/index.kt`
  - 当前离线 Android Studio 工程的生成副本；必须镜像 UTS 最小修复，后续导出可以覆盖它。
- 不修改：登录页面、`auth-api.js` 路由、后端 `/native/complete`。

---

### Task 1: 固化当前失败基线

**Files:**
- Inspect: signed APK
- Inspect: installed package `site.niko000o.aitemperate`

- [ ] **Step 1: 验证安装包确实使用发布签名**

```powershell
$adb = 'C:\Users\damn\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$apk = 'C:\Users\damn\Downloads\5.24\Android-SDK@5.24.82669_20260813\HBuilder-HelloUniApp\app\release\app-release.apk'
$apksigner = Get-ChildItem 'C:\Users\damn\AppData\Local\Android\Sdk\build-tools' -Filter apksigner.bat -Recurse |
  Sort-Object FullName -Descending |
  Select-Object -First 1
& $apksigner.FullName verify --print-certs $apk
& $adb shell pm path site.niko000o.aitemperate
```

Expected: APK SHA-1 为 `EC:E3:1B:3E:5A:C8:FA:07:39:16:4C:80:37:25:76:59:BF:A0:D7:B7`，设备包名为 `site.niko000o.aitemperate`。

- [ ] **Step 2: 保留一次失败日志作为修复前基线**

```powershell
& $adb logcat -c
& $adb logcat -v threadtime -s AIT_GOOGLE_OAUTH:I Auth.Api.Credentials:I '*:S'
```

Expected final application stage: `native_android_request_begin`；不出现 `native_android_result`。

---

### Task 2: 先验证“UTS 异步请求对象生命周期”假设

**Hypothesis:** `CredentialManager`、`CancellationSignal` 和 `CredentialCallback` 都只存在于 `requestCredential()` 局部变量中；Google 账号界面异步返回前，UTS 生成代码没有为整个请求会话提供明确的强引用和结算边界，导致 Google Play 服务完成后应用层回调丢失。

**Files:**
- Modify: `fornted/uni_modules/ait-google-signin/utssdk/app-android/index.uts`
- Modify: `fornted/common/auth/oauth-contract.test.cjs`
- Modify: `HBuilder-HelloUniApp/uni_modules/ait-google-signin/utssdk/app-android/src/index.kt`

- [ ] **Step 1: 增加模块级活动会话引用**

在 `GOOGLE_OAUTH_LOG_TAG` 后添加：

```uts
let activeCredentialManager : CredentialManager | null = null
let activeCancellationSignal : CancellationSignal | null = null
let activeCredentialCallback : CredentialCallback | null = null
let activeRequestId : number = 0

function releaseCredentialRequest(requestId : number) : void {
	if (requestId != activeRequestId) return
	activeCredentialManager = null
	activeCancellationSignal = null
	activeCredentialCallback = null
}
```

- [ ] **Step 2: 每次请求先取消旧会话，再强持有新会话**

将 `requestCredential()` 中的异步调用改成：

```uts
if (activeCancellationSignal != null) {
	activeCancellationSignal!.cancel()
}

activeRequestId += 1
const requestId = activeRequestId
const signal = new CancellationSignal()
const callback = new CredentialCallback(
	requestId,
	serverClientId,
	nonce,
	filterAuthorized,
	callbacks)

activeCredentialManager = manager
activeCancellationSignal = signal
activeCredentialCallback = callback

try {
	manager.getCredentialAsync(
		activity,
		request,
		signal,
		ContextCompat.getMainExecutor(activity),
		callback)
} catch (error : Exception) {
	releaseCredentialRequest(requestId)
	logGoogleOAuth(
		'native_android_error',
		'errorType=' + UTSAndroid.getJavaClass(error).simpleName)
	callbacks.fail('GOOGLE_NATIVE_UNAVAILABLE', 'Google 原生登录暂时不可用。')
}
```

日志可以包含单调递增的本地 `requestId`，但禁止记录 Client ID、nonce、Token、邮箱或账号。

- [ ] **Step 3: 让 Callback 持有 requestId，并保证所有终态释放会话**

给 `CredentialCallback` 构造器增加 `requestId : number`，并在 `onResult()`、最终 `onError()` 和取消分支进入后先调用：

```uts
releaseCredentialRequest(this.requestId)
```

第一次 `NoCredentialException` 需要重试全部账号时，顺序必须是：

```uts
releaseCredentialRequest(this.requestId)
requestCredential(this.serverClientId, this.nonce, false, this.callbacks)
```

不能先创建新请求再释放旧请求，否则旧回调可能把新会话清空。

- [ ] **Step 4: 将同一会话生命周期修复镜像到当前 Android Studio 生成工程**

在生成的 `index.kt` 中加入对应 Kotlin 状态：

```kotlin
private var activeCredentialManager: CredentialManager? = null
private var activeCancellationSignal: CancellationSignal? = null
private var activeCredentialCallback: CredentialCallback? = null
private var activeRequestId: Int = 0

private fun releaseCredentialRequest(requestId: Int) {
    if (requestId != activeRequestId) return
    activeCredentialManager = null
    activeCancellationSignal = null
    activeCredentialCallback = null
}
```

在调用 `getCredentialAsync()` 前取消旧 signal、创建局部 `signal` 和 `callback`，再赋值给上述三个活动引用；`CredentialCallback` 构造器接收 `requestId`，所有终态按 UTS 源码相同顺序调用 `releaseCredentialRequest(requestId)`。

这是当前离线工程的可构建副本；长期逻辑仍以 `index.uts` 为准。

- [ ] **Step 5: 增加静态契约检查**

在 `oauth-contract.test.cjs` 添加：

```js
test('Google native request keeps async bridge objects alive until a terminal callback', () => {
	const plugin = read('uni_modules/ait-google-signin/utssdk/app-android/index.uts')

	assert.match(plugin, /activeCredentialManager\s*:\s*CredentialManager\s*\|\s*null/)
	assert.match(plugin, /activeCancellationSignal\s*:\s*CancellationSignal\s*\|\s*null/)
	assert.match(plugin, /activeCredentialCallback\s*:\s*CredentialCallback\s*\|\s*null/)
	assert.match(plugin, /activeCancellationSignal!\.cancel\(\)/)
	assert.match(plugin, /releaseCredentialRequest\(this\.requestId\)/)
	assert.doesNotMatch(plugin, /Log\.[a-zA-Z]+\([^\n]*(?:idToken|nonce|serverClientId)/i)
})
```

- [ ] **Step 6: 仅在用户明确授权后执行静态检查**

```powershell
Set-Location 'C:\Users\damn\Desktop\ai-temperate-main\fornted'
node --test .\common\auth\oauth-contract.test.cjs
```

Expected: all contract tests pass.

---

### Task 3: 重新签名并验证最小修复

**Files:**
- Generated: Android Studio `HBuilder-HelloUniApp`

- [ ] **Step 1: 确认生成副本包含会话生命周期修复**

```powershell
rg -n "activeCredentialManager|activeCancellationSignal|activeCredentialCallback|releaseCredentialRequest" `
  'C:\Users\damn\Downloads\5.24\Android-SDK@5.24.82669_20260813\HBuilder-HelloUniApp\uni_modules\ait-google-signin\utssdk\app-android\src\index.kt'
```

Expected: 四个标识都存在。此轮只改原生插件，不需要再次生成或复制 HBuilderX 的 `www` 资源。

- [ ] **Step 2: 在 Android Studio 重新生成签名 release APK**

```text
Build → Generate Signed App Bundle or APK → APK → release → Create
```

Expected APK:

```text
C:\Users\damn\Downloads\5.24\Android-SDK@5.24.82669_20260813\HBuilder-HelloUniApp\app\release\app-release.apk
```

- [ ] **Step 3: 覆盖安装，不卸载应用数据**

```powershell
& $adb install -r $apk
```

Expected: `Success`。

- [ ] **Step 4: 抓取一次完整登录链路**

成功标准：

```text
native_android_request_begin
native_android_result
native_android_success
native_success
native_complete_begin
native_complete_success
```

失败但受控标准：30 秒内出现 `native_android_error`、`native_android_cancel` 或 JavaScript `native_timeout`，页面停止转圈。

如果仍然只有 `native_android_request_begin`，停止继续改 UTS，进入 Task 4；不要同时降级依赖。

---

### Task 4: 条件方案——使用手写 Kotlin coroutine bridge 隔离 UTS

**Trigger:** Task 3 在同一台设备上仍不能进入任何 CredentialManager 回调。

**Hypothesis:** UTS 生成的 `CredentialManagerCallback` 与 Credential Manager 的 UI 生命周期存在兼容问题；手写 Kotlin 使用 suspend `getCredential()` 可以绕开该异步回调边界。

**Files:**
- Create: `HBuilder-HelloUniApp/uni_modules/ait-google-signin/utssdk/app-android/src/GoogleCredentialBridge.kt`
- Modify: `HBuilder-HelloUniApp/uni_modules/ait-google-signin/utssdk/app-android/src/index.kt`

- [ ] **Step 1: 创建 Kotlin bridge**

```kotlin
package uts.sdk.modules.aitGoogleSignin

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object GoogleCredentialBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJob: Job? = null

    fun request(
        activity: Activity,
        request: GetCredentialRequest,
        callback: Callback
    ) {
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                val response = CredentialManager.create(activity)
                    .getCredential(activity, request)
                val credential = response.credential
                if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    callback.onError("GOOGLE_NATIVE_UNSUPPORTED_CREDENTIAL")
                    return@launch
                }
                val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                if (token.isEmpty()) {
                    callback.onError("GOOGLE_NATIVE_EMPTY_TOKEN")
                    return@launch
                }
                callback.onSuccess(token)
            } catch (_: GetCredentialCancellationException) {
                callback.onCancel()
            } catch (error: GetCredentialException) {
                callback.onError(error.javaClass.simpleName)
            } catch (error: Throwable) {
                callback.onError(error.javaClass.simpleName)
            }
        }
    }

    interface Callback {
        fun onSuccess(idToken: String)
        fun onCancel()
        fun onError(code: String)
    }
}
```

ID Token 只允许作为 `onSuccess()` 参数立即传给现有 UTS success 回调，禁止写日志或存储。

- [ ] **Step 2: 让生成的 `index.kt` 只负责 UTS 回调适配**

删除 `getCredentialAsync()` 调用，改为构建相同的 `GetCredentialRequest` 后调用 `GoogleCredentialBridge.request(...)`。每个 Kotlin bridge 终态都必须映射到现有签名：

```text
success(GoogleSignInResult)
cancel()
fail(code, message)
```

- [ ] **Step 3: 编译验证后重新执行 Task 3 的签名、覆盖安装和 Logcat 步骤**

Expected: 至少进入 bridge 的 success/cancel/error 之一；不允许永久无回调。

注意：此文件位于生成工程。确认方案有效后，应把 bridge 打成插件随附 AAR 或建立可重复复制脚本；禁止把手工生成工程当成唯一长期源码。

---

### Task 5: 仅在 Kotlin bridge 也失败时验证依赖冲突

**Trigger:** Task 4 仍不能进入任何终态回调。

- [ ] **Step 1: 查看最终解析到的依赖版本**

```powershell
Set-Location 'C:\Users\damn\Downloads\5.24\Android-SDK@5.24.82669_20260813\HBuilder-HelloUniApp'
.\gradlew.bat :ait-google-signin:dependencyInsight --dependency androidx.credentials --configuration releaseRuntimeClasspath
.\gradlew.bat :ait-google-signin:dependencyInsight --dependency googleid --configuration releaseRuntimeClasspath
```

Expected: `credentials` 与 `credentials-play-services-auth` 都解析为 `1.6.0`，`googleid` 解析为 `1.2.0`；不得出现旧版本强制覆盖。

- [ ] **Step 2: 一次只测试一个版本组合**

先保持 Google Cloud、签名、代码和网络不变，只将两个 AndroidX credentials 依赖同时切换到同一个已发布稳定版本。禁止只改其中一个，也禁止同时改 `googleid`，否则无法判断变量。

- [ ] **Step 3: 每个组合都执行同一份 Logcat 验收链路**

若版本切换恢复 `native_android_result`，记录有效组合并在源 `config.json` 与 Android Studio module `build.gradle` 同步固定；若没有恢复，回滚版本，停止继续随机降级。

---

## 最终验收标准

- 账号选择后 30 秒内必然得到 success、cancel、fail 或 timeout 之一。
- 成功时顺序包含 `native_android_result`、`native_android_success` 和 `native_complete_begin`。
- 没有 `idToken` 时绝不调用 `/native/complete`。
- `BadAuthentication` 辅助警告不能单独改变业务结果；只看应用自己的终态日志。
- 日志不包含 ID Token、nonce、Client Secret、完整 Client ID、邮箱、手机号、Cookie 或 Authorization Header。
- 不修改登录页面，不要求发布到 Google Play，不改后端接口。
- 诊断结束后删除临时阶段日志，但保留强引用、一次性结算、取消旧请求和正式错误处理。

## 执行顺序与停止条件

1. 先做 Task 1，确认实际安装签名。
2. 只实施 Task 2，并用 Task 3 验证。
3. Task 2 无效才进入 Task 4。
4. Kotlin bridge 仍无效才进入 Task 5。
5. 任一步恢复终态回调后立即停止切换架构或依赖，继续验证 ID Token 和后端完成请求。
