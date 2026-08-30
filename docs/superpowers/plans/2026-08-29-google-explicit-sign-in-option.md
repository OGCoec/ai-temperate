# Google Explicit Sign-In Option Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Android 明确点击“使用 Google 登录”按钮后的凭据请求从 `GetGoogleIdOption` 切换为 `GetSignInWithGoogleOption`，验证账号选择器能否正常出现并返回 ID Token。

**Architecture:** 只替换 Android 原生 Credential Manager 的 Google 请求选项，不修改登录页面、JavaScript OAuth 流程、后端接口、Google Cloud 配置或签名信息。保留第二轮已经验证可运行的 Kotlin 协程桥、30 秒超时、一次性请求保护和现有 ID Token 解析；通过同一组 Logcat 阶段日志验证结果。

**Tech Stack:** UniApp UTS、Android Credential Manager 1.6.0、Google ID 1.2.0、Kotlin 协程、Android Logcat。

---

### Task 1: 更新原生选项静态契约

**Files:**
- Modify: `C:/Users/damn/Desktop/ai-temperate-main/fornted/common/auth/oauth-contract.test.cjs`

- [ ] **Step 1: 将旧选项契约改为显式按钮契约**

把 `Google native plugin disables auto selection and retries with all device accounts` 测试替换为：

```js
test('Google native plugin uses the explicit Sign in with Google option', () => {
	const plugin = read('uni_modules/ait-google-signin/utssdk/app-android/index.uts')
	const config = JSON.parse(read('uni_modules/ait-google-signin/utssdk/app-android/config.json'))

	assert.match(plugin, /GetSignInWithGoogleOption/)
	assert.match(plugin, /new GetSignInWithGoogleOption\.Builder\(serverClientId\)/)
	assert.match(plugin, /setNonce\(nonce\)/)
	assert.doesNotMatch(plugin, /GetGoogleIdOption/)
	assert.doesNotMatch(plugin, /setFilterByAuthorizedAccounts/)
	assert.doesNotMatch(plugin, /native_android_retry_no_credential/)
	assert.deepEqual(config.dependencies, [
		'androidx.credentials:credentials:1.6.0',
		'androidx.credentials:credentials-play-services-auth:1.6.0',
		'com.google.android.libraries.identity.googleid:googleid:1.2.0',
		'com.google.android.gms:play-services-base:18.3.0'
	])
})
```

- [ ] **Step 2: 从诊断阶段清单移除旧的二次查询阶段**

从阶段数组删除：

```js
'native_android_retry_no_credential'
```

保留 `native_android_request_begin`、`native_android_result`、`native_android_success`、`native_android_error` 和 `native_android_cancel`。

- [ ] **Step 3: 第一阶段不执行测试**

根据项目规范，本任务只交付测试代码；除非用户明确授权，不运行 `node --test`。

### Task 2: 修改 UTS 原生插件源文件

**Files:**
- Modify: `C:/Users/damn/Desktop/ai-temperate-main/fornted/uni_modules/ait-google-signin/utssdk/app-android/index.uts`

- [ ] **Step 1: 替换 Google Credential Option 导入**

```uts
import GetSignInWithGoogleOption from 'com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption'
```

删除：

```uts
import GetGoogleIdOption from 'com.google.android.libraries.identity.googleid.GetGoogleIdOption'
```

- [ ] **Step 2: 将入口改为单次显式按钮请求**

```uts
requestCredential(serverClientId, nonce, callbacks)
```

将 `requestCredential()` 改为不接收 `filterAuthorized`：

```uts
function requestCredential(
	serverClientId : string,
	nonce : string,
	callbacks : GoogleSignInCallbacks
) : void {
	const activity = UTSAndroid.getUniActivity() as Activity
	const manager = CredentialManager.create(activity)
	const googleOption = new GetSignInWithGoogleOption.Builder(serverClientId)
		.setNonce(nonce)
		.build()
	const request = new GetCredentialRequest.Builder()
		.addCredentialOption(googleOption)
		.build()
	// 其余请求生命周期保护保持原样。
}
```

不得加入 `setFilterByAuthorizedAccounts()`、`setAutoSelectEnabled()`，因为这些属于 `GetGoogleIdOption` 的账号筛选/自动选择能力，不属于显式按钮请求。

- [ ] **Step 3: 简化回调持有字段**

`CredentialCallback` 只保留：

```uts
private requestId : number
private callbacks : GoogleSignInCallbacks
```

构造函数只接收 `requestId` 和 `callbacks`。删除 `serverClientId`、`nonce`、`filterAuthorized` 字段，因为显式按钮流程不再需要失败后二次发起请求。

- [ ] **Step 4: 删除授权账号筛选重试分支**

删除：

```uts
if (this.filterAuthorized && error instanceof NoCredentialException) {
	logGoogleOAuth('native_android_retry_no_credential', ...)
	requestCredential(this.serverClientId, this.nonce, false, this.callbacks)
	return
}
```

保留单次 `NoCredentialException` 的受控失败回调、用户取消回调、未知异常回调以及 ID Token 非空校验。

- [ ] **Step 5: 用无敏感信息日志标识新请求类型**

```uts
logGoogleOAuth(
	'native_android_request_begin',
	'requestType=explicit_sign_in_with_google')
```

禁止记录 `serverClientId`、`nonce` 或 ID Token。

### Task 3: 同步当前 Android Studio 工程并保留协程桥

**Files:**
- Modify: `C:/Users/damn/Downloads/5.24/Android-SDK@5.24.82669_20260813/HBuilder-HelloUniApp/uni_modules/ait-google-signin/utssdk/app-android/src/index.kt`
- Preserve: `C:/Users/damn/Downloads/5.24/Android-SDK@5.24.82669_20260813/HBuilder-HelloUniApp/uni_modules/ait-google-signin/utssdk/app-android/src/GoogleCredentialBridge.kt`

- [ ] **Step 1: 在生成 Kotlin 中替换选项构建**

```kotlin
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption

val googleOption = GetSignInWithGoogleOption.Builder(serverClientId)
    .setNonce(nonce)
    .build()
val request = GetCredentialRequest.Builder()
    .addCredentialOption(googleOption)
    .build()
```

删除 `GetGoogleIdOption`、`filterAuthorized` 参数、两段式重试和相应日志。

- [ ] **Step 2: 保留第二轮桥接实现**

调用仍然必须经过：

```kotlin
GoogleCredentialBridge.request(activity, request, callback)
```

不得改回 `getCredentialAsync()`。`GoogleCredentialBridge.kt` 中的主线程协程、30 秒 `withTimeout`、请求 ID 防迟到回调和 Token 不落日志逻辑全部保持不变。

- [ ] **Step 3: 防止 HBuilder 再次导出覆盖当前实验**

重新生成本地打包资源后，要确认生成的 `index.kt` 仍包含 `GetSignInWithGoogleOption` 和 `GoogleCredentialBridge.request`。若 HBuilder 覆盖了生成文件，则在 Android Studio 构建前重新应用本 Task 的同步修改；源文件 `index.uts` 始终是选项类型的长期事实来源。

### Task 4: 编译、打包和真机验证

**Files:**
- Build output: `C:/Users/damn/Downloads/5.24/Android-SDK@5.24.82669_20260813/HBuilder-HelloUniApp/app/release/app-release.apk`

- [ ] **Step 1: 用户授权后执行定向编译**

```powershell
Set-Location 'C:\Users\damn\Downloads\5.24\Android-SDK@5.24.82669_20260813\HBuilder-HelloUniApp'
.\gradlew.bat :ait-google-signin:compileReleaseKotlin --console=plain
```

预期：`BUILD SUCCESSFUL`，且不存在 `GetSignInWithGoogleOption` 未解析错误。

- [ ] **Step 2: 生成并安装新的 release APK**

使用原来的 release keystore 生成签名 APK。因为原生代码改变，旧 APK 不会自动更新，必须安装新 APK；签名一致时可以覆盖安装，无需先卸载。

- [ ] **Step 3: 清空并监听诊断日志**

```powershell
$adb = 'C:\Users\damn\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb logcat -c
& $adb logcat -v threadtime -s AIT_GOOGLE_OAUTH:I Auth.Api.Credentials:I '*:S'
```

- [ ] **Step 4: 验证唯一变量假设**

点击一次“使用 Google 登录”。预期最先出现：

```text
native_android_request_begin requestType=explicit_sign_in_with_google
native_android_bridge_begin
```

成功验收链路：

```text
账号选择器显示
native_android_bridge_result
native_android_result
native_android_success
native_success
native_complete_begin
native_complete_success
```

如果账号选择器仍未出现并在 30 秒后出现 `native_android_bridge_timeout`，则本次“请求选项不匹配”假设被证伪；停止继续叠加代码修改，回到 GMS 账号认证状态、OAuth 客户端关联或厂商 ROM 的 Credential Provider 行为继续取证。

---

## 范围边界

- 不修改登录页面或按钮样式。
- 不修改 JavaScript OAuth 状态机。
- 不修改后端 `/api/auth/oauth2/google/native/complete`。
- 不修改 Google Cloud OAuth 客户端、包名、SHA-1 或签名文件。
- 不新增浏览器/WebView 回退。
- 不升级当前依赖；`googleid:1.2.0` 已包含 `GetSignInWithGoogleOption`。
- 第一阶段只修改代码，不自动执行测试、编译、打包、安装或真机操作。
