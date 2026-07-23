# 认证标识校验增强 Implementation Plan

> **执行约束：** 本项目的 `AGENTS.md` 要求所有实施工作由根代理完成，禁止子代理。第一阶段只交付代码和测试代码，不运行测试、编译、依赖分析或外部服务检查；第二阶段必须由用户明确批准具体命令后才能执行。

**Goal:** 使用 Google `libphonenumber` 限制后端只接受手机或“座机/手机不可区分”类型，并让注册、登录和忘记密码在前端共享同一套邮箱格式校验。

**Architecture:** 后端继续以 `RegistrationInputNormalizer` 作为注册、密码登录、验证码登录和忘记密码的统一入口，在现有号码有效性校验之后增加 `getNumberType()` 白名单。前端新增无第三方依赖的共享邮箱校验模块，三个认证页面只调用该模块，不各自维护正则。

**Tech Stack:** Java 17、Spring Boot、Google libphonenumber 8.13.55、JUnit 5、AssertJ、uni-app/Vue、Node 内置测试运行器。

---

## 已确认的行为边界

- 后端允许 `PhoneNumberType.MOBILE`。
- 后端允许 `PhoneNumberType.FIXED_LINE_OR_MOBILE`，避免误拒绝美国、加拿大等无法可靠区分座机与手机的号码。
- 后端拒绝明确的 `FIXED_LINE`、`VOIP`、`TOLL_FREE`、`PREMIUM_RATE`、`SHARED_COST`、`PERSONAL_NUMBER`、`PAGER`、`UAN`、`VOICEMAIL` 和 `UNKNOWN`。
- `getNumberType()` 只依据 Google 号码元数据分类，不能证明号码真实存在、当前可接收短信或属于提交者；注册/登录/找回流程仍以验证码完成所有权验证。
- 某些虚拟运营商号码可能仍被 Google 分类为 `MOBILE`，因此本规则只能拒绝明确识别为 `VOIP` 等非手机号类型，不能保证识别所有“虚拟号”。
- 前端邮箱规则与后端 `EmailAddressNormalizer` 对齐：ASCII 邮箱、总长度不超过 254、恰好一个 `@`、本地部分不能以点开头/结尾或包含连续点、域名标签不能以连字符开头/结尾。
- 前端校验用于尽早阻止明显无效请求、改善体验；后端校验继续作为最终安全边界。

## 文件映射

- 修改 `ai-temperate-service/src/main/java/com/example/temperate/service/registration/component/normalizer/RegistrationInputNormalizer.java`：统一手机号类型白名单。
- 修改 `ai-temperate-service/src/test/java/com/example/temperate/service/registration/component/normalizer/RegistrationInputPolicyTest.java`：覆盖允许和拒绝的号码类型。
- 新建 `fornted/common/auth/email-validation.js`：共享邮箱格式规则。
- 新建 `fornted/pages/auth/email-validation.test.cjs`：无第三方依赖的前端邮箱规则测试。
- 修改 `fornted/package.json`：增加只运行邮箱规则测试的脚本，不新增依赖。
- 修改 `fornted/pages/auth/register.vue`：把现有独立正则替换为共享规则。
- 修改 `fornted/pages/auth/login.vue`：密码邮箱登录和邮箱验证码登录提交前校验。
- 修改 `fornted/pages/auth/password-reset.vue`：邮箱找回提交前校验。
- 修改 `docs/authentication-api.md`：记录号码类型白名单和前后端校验边界。

### Task 1：先补充后端号码类型测试代码

**Files:**

- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/registration/component/normalizer/RegistrationInputPolicyTest.java`

- [ ] **Step 1：为测试类增加 Google 号码类型与参数化测试导入**

```java
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import java.util.Comparator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
```

- [ ] **Step 2：增加允许类型测试**

```java
private static final PhoneNumberUtil PHONE_NUMBER_UTIL = PhoneNumberUtil.getInstance();

@ParameterizedTest
@EnumSource(value = PhoneNumberType.class, names = {"MOBILE", "FIXED_LINE_OR_MOBILE"})
void acceptsMobileCapablePhoneNumberTypes(PhoneNumberType type) {
    TypedPhoneExample example = exampleFor(type);

    assertThat(normalizer.normalizePhone(example.region(), example.nationalNumber()))
            .isEqualTo(PHONE_NUMBER_UTIL.format(example.number(), PhoneNumberFormat.E164));
}
```

- [ ] **Step 3：增加明确非手机类型拒绝测试**

```java
@ParameterizedTest
@EnumSource(
        value = PhoneNumberType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = {"MOBILE", "FIXED_LINE_OR_MOBILE", "UNKNOWN"})
void rejectsValidButNonMobilePhoneNumberTypes(PhoneNumberType type) {
    TypedPhoneExample example = exampleFor(type);

    assertThatThrownBy(() ->
            normalizer.normalizePhone(example.region(), example.nationalNumber()))
            .isInstanceOfSatisfying(RegistrationException.class, exception ->
                    assertThat(exception.code())
                            .isEqualTo(RegistrationErrorCode.INVALID_INPUT));
}
```

- [ ] **Step 4：增加从当前 libphonenumber 元数据选择示例号码的辅助代码**

```java
private static TypedPhoneExample exampleFor(PhoneNumberType expectedType) {
    return PHONE_NUMBER_UTIL.getSupportedRegions().stream()
            .sorted(Comparator.naturalOrder())
            .map(region -> exampleFor(region, expectedType))
            .filter(example -> example != null)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                    "No libphonenumber example for type " + expectedType));
}

private static TypedPhoneExample exampleFor(
        String region,
        PhoneNumberType expectedType) {
    PhoneNumber number = PHONE_NUMBER_UTIL.getExampleNumberForType(region, expectedType);
    if (number == null
            || !PHONE_NUMBER_UTIL.isValidNumberForRegion(number, region)
            || PHONE_NUMBER_UTIL.getNumberType(number) != expectedType) {
        return null;
    }
    return new TypedPhoneExample(
            region,
            PHONE_NUMBER_UTIL.getNationalSignificantNumber(number),
            number);
}

private record TypedPhoneExample(
        String region,
        String nationalNumber,
        PhoneNumber number) {
}
```

- [ ] **Step 5：第一阶段不运行测试**

测试代码先于生产代码落地，但根据 `AGENTS.md` 第一阶段规定，本阶段不执行红灯测试。执行前必须按 Task 6 取得用户明确批准。

### Task 2：在统一后端入口增加 `getNumberType()` 白名单

**Files:**

- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/registration/component/normalizer/RegistrationInputNormalizer.java:14`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/registration/component/normalizer/RegistrationInputNormalizer.java:32`

- [ ] **Step 1：定义不可变的允许类型集合**

```java
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import java.util.Set;

private static final Set<PhoneNumberType> ALLOWED_PHONE_NUMBER_TYPES = Set.of(
        PhoneNumberType.MOBILE,
        PhoneNumberType.FIXED_LINE_OR_MOBILE);
```

- [ ] **Step 2：在地区有效性之后增加类型判断**

```java
var parsed = phoneNumberUtil.parse(nationalNumber.trim(), region);
if (!phoneNumberUtil.isValidNumberForRegion(parsed, region)) {
    throw invalidInput("Phone number is invalid.");
}
PhoneNumberType numberType = phoneNumberUtil.getNumberType(parsed);
if (!ALLOWED_PHONE_NUMBER_TYPES.contains(numberType)) {
    throw invalidInput("Phone number must be a supported mobile number.");
}
return phoneNumberUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
```

- [ ] **Step 3：保持错误信息不包含原始号码和号码类型细节**

外部只返回受控的 `INVALID_INPUT`，不得把电话号码、Google 解析异常、`VOIP` 等分类写入异常消息或日志。

- [ ] **Step 4：确认调用范围无需重复修改**

静态确认以下流程仍调用同一个 `RegistrationInputNormalizer`：

- 注册：`RegistrationServiceImpl.start()`。
- 密码登录：`PasswordLoginStrategy.login()`。
- 邮箱/短信验证码登录：`LoginCodeFlowServiceImpl.normalize()`。
- 忘记密码：`PasswordResetServiceImpl.normalize()`。

### Task 3：新增前端共享邮箱校验模块和纯函数测试

**Files:**

- Create: `fornted/common/auth/email-validation.js`
- Create: `fornted/pages/auth/email-validation.test.cjs`
- Modify: `fornted/package.json:7`

- [ ] **Step 1：创建与后端规则对齐的无依赖校验模块**

```javascript
const MAXIMUM_EMAIL_LENGTH = 254
const MAXIMUM_DOMAIN_LABEL_LENGTH = 63
const LOCAL_PART_PATTERN = /^[A-Za-z0-9!#$%&'*+\/=?^_`{|}~.-]+$/
const DOMAIN_LABEL_PATTERN = /^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$/

function trimAsciiSpaces(value) {
	return value.replace(/^ +| +$/g, '')
}

function isValidEmailAddress(value) {
	if (typeof value !== 'string') return false
	const normalized = trimAsciiSpaces(value).toLowerCase()
	if (!normalized || normalized.length > MAXIMUM_EMAIL_LENGTH || /[\s\u0000-\u001f\u007f]/.test(normalized)) {
		return false
	}

	const at = normalized.indexOf('@')
	if (at <= 0 || at !== normalized.lastIndexOf('@') || at === normalized.length - 1) {
		return false
	}

	const localPart = normalized.slice(0, at)
	if (localPart.startsWith('.') || localPart.endsWith('.') || localPart.includes('..') || !LOCAL_PART_PATTERN.test(localPart)) {
		return false
	}

	return normalized.slice(at + 1).split('.').every(label =>
		label.length > 0 &&
		label.length <= MAXIMUM_DOMAIN_LABEL_LENGTH &&
		DOMAIN_LABEL_PATTERN.test(label)
	)
}

module.exports = { isValidEmailAddress }
```

- [ ] **Step 2：创建 Node 内置测试文件**

```javascript
const test = require('node:test')
const assert = require('node:assert/strict')
const { isValidEmailAddress } = require('../../common/auth/email-validation.js')

test('accepts supported email formats', () => {
	for (const value of [
		'person@example.com',
		' Person.Name+alerts@example.test ',
		'user_name@example-domain.test'
	]) {
		assert.equal(isValidEmailAddress(value), true, value)
	}
})

test('rejects malformed email formats', () => {
	for (const value of [
		'',
		'person',
		'person@',
		'@example.com',
		'.person@example.com',
		'person.@example.com',
		'person..name@example.com',
		'person@-example.com',
		'person@example-.com',
		'person@example..com',
		'person\t@example.com'
	]) {
		assert.equal(isValidEmailAddress(value), false, value)
	}
})
```

- [ ] **Step 3：增加不下载任何依赖的测试脚本**

```json
"scripts": {
  "test": "echo \"Error: no test specified\" && exit 1",
  "test:auth-email": "node --test pages/auth/email-validation.test.cjs"
}
```

### Task 4：让三个认证页面统一调用共享邮箱规则

**Files:**

- Modify: `fornted/pages/auth/register.vue:79`
- Modify: `fornted/pages/auth/register.vue:102`
- Modify: `fornted/pages/auth/login.vue:63`
- Modify: `fornted/pages/auth/login.vue:97`
- Modify: `fornted/pages/auth/password-reset.vue:64`
- Modify: `fornted/pages/auth/password-reset.vue:96`

- [ ] **Step 1：注册页替换独立正则**

```javascript
const { isValidEmailAddress } = require('../../common/auth/email-validation.js')

emailError() {
	return isValidEmailAddress(this.email) ? '' : '请输入有效邮箱。'
}
```

保留当前 `touched.email` 和内联错误展示；`start()` 继续在 `emailError` 非空时直接返回，不发送请求。

- [ ] **Step 2：密码登录和邮箱验证码登录提交前校验**

```javascript
const { isValidEmailAddress } = require('../../common/auth/email-validation.js')

validateEmailFor(type) {
	if (type !== 'EMAIL' || isValidEmailAddress(this.email)) return true
	this.error = '请输入有效邮箱。'
	return false
},
async passwordLogin() {
	if (!this.validateEmailFor(this.identifierType)) return
	const result = await this.run(() => authApi.passwordLogin({
		...this.payload(this.identifierType), password: this.password
	}))
	if (result) uni.showToast({ title: '登录成功', icon: 'success' })
},
async startCodeFlow() {
	const type = this.method === 'EMAIL_CODE' ? 'EMAIL' : 'PHONE'
	if (!this.validateEmailFor(type)) return
	const result = await this.run(() => authApi.loginCodeStart({
		strategyType: this.method, ...this.payload(type)
	}))
	if (result) this.flow = result
}
```

手机号前端不伪装成 Google 校验；手机号仍提交给后端 `libphonenumber` 处理。

- [ ] **Step 3：忘记密码邮箱渠道提交前校验**

```javascript
const { isValidEmailAddress } = require('../../common/auth/email-validation.js')

async start() {
	if (this.channel === 'EMAIL' && !isValidEmailAddress(this.email)) {
		this.error = '请输入有效邮箱。'
		return
	}
	const data = this.channel === 'EMAIL'
		? { channel: 'EMAIL', email: this.email }
		: { channel: 'SMS', countryIso2: this.country.iso2.toUpperCase(), phoneNumber: this.phoneNumber }
	const result = await this.run(() => authApi.passwordResetStart(data))
	if (result) { this.flow = result; this.stage = 'HUMAN' }
}
```

- [ ] **Step 4：保留后端最终校验**

不得因为前端新增校验而删除 Controller 的 `@Email`、`@Size`，也不得删除服务层 `EmailAddressNormalizer`。

### Task 5：同步认证文档

**Files:**

- Modify: `docs/authentication-api.md:30`
- Modify: `docs/authentication-api.md:51`
- Modify: `docs/authentication-api.md:116`

- [ ] **Step 1：记录手机号规则**

在认证 API 通用说明中增加：号码先按国家/地区解析并规范化为 E.164，然后只允许 `MOBILE` 和 `FIXED_LINE_OR_MOBILE`；明确的座机、VoIP、免费号码等类型返回输入错误。

- [ ] **Step 2：记录能力限制**

明确说明 libphonenumber 不是号码存活检测，也不能保证识别全部虚拟运营商号码；账号所有权仍由验证码验证。

- [ ] **Step 3：记录前后端邮箱边界**

注册、登录和忘记密码前端使用共享规则提前拦截；所有 API 仍在后端重新验证，禁止依赖前端结果。

### Task 6：第二阶段安全验证（必须再次获得用户批准）

**测试范围与状态变化：** 以下命令只执行本地单元测试/编译，不连接 PostgreSQL、Redis、RabbitMQ、短信或邮件服务。Maven 会写入各模块 `target` 目录；HBuilderX 编译会写入 `fornted/unpackage`，并可能启动本地 Web 开发服务；Node 邮箱测试不写业务状态。

- [ ] **Step 1：请求用户批准以下具体命令**

```powershell
mvn -pl ai-temperate-service -am -Dtest=RegistrationInputPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
npm --prefix fornted run test:auth-email
& 'C:\Users\damn\Desktop\HBuilderX\cli.exe' launch web --project 'C:\Users\damn\Desktop\ai-temperate\fornted' --compile true
```

- [ ] **Step 2：批准后运行后端定向测试**

预期：`MOBILE` 与 `FIXED_LINE_OR_MOBILE` 通过；Google 元数据中可提供示例的明确非手机类型全部得到 `RegistrationErrorCode.INVALID_INPUT`。

- [ ] **Step 3：批准后运行前端纯函数测试**

预期：有效邮箱集合全部通过，空值、缺少 `@`、连续点、非法域名标签和控制字符全部失败。

- [ ] **Step 4：批准后执行 H5 编译检查**

预期：三个认证页面能够解析共享 CommonJS 模块，没有模板或脚本编译错误。

- [ ] **Step 5：进行不触发后端请求的页面检查**

- 注册输入无效邮箱并点击继续：停留在当前步骤并显示邮箱错误。
- 密码邮箱登录输入无效邮箱并点击登录：显示顶部错误，不调用登录 API。
- 邮箱验证码登录输入无效邮箱并点击继续：显示顶部错误，不创建验证码流程。
- 邮箱找回密码输入无效邮箱并点击继续：显示顶部错误，不创建找回流程。
- 手机渠道仍交由后端校验，不在前端复制 Google 元数据。

## 完成标准

- 四个后端入口通过统一方法执行 Google 号码类型白名单。
- 明确座机和 VoIP 等非手机号类型不能进入认证业务查询或流程存储。
- 注册、登录、忘记密码前端共享一套邮箱规则，并在明显无效时不发请求。
- 后端邮箱校验完全保留。
- 第一阶段交付时明确标注所有测试和编译均未执行。
- 第二阶段仅执行用户明确批准的命令，并分别报告通过、失败、跳过和未执行项。
