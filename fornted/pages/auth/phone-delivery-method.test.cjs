const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const frontendRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

/**
 * 固定国际手机号验证码的 SMS/WhatsApp 前端契约，防止页面绕过共享限流或暴露供应商选择。
 */
test('phone delivery selector is accessible and explains the Twilio Sandbox boundary', () => {
	const source = read('components/auth/phone-delivery-method.vue')

	assert.match(source, /role="radiogroup"/)
	assert.match(source, /role="radio"/)
	assert.match(source, /:aria-checked="modelValue === item\.value"/)
	assert.match(source, /@keydown="onOptionKeydown/)
	assert.match(source, /当前使用 Twilio Sandbox/)
	assert.match(source, /必须先加入该 Sandbox/)
	assert.match(source, /加入状态可能过期/)
	assert.doesNotMatch(source, /TWILIO_WHATSAPP/)
})

test('registration login and reset show WhatsApp only for non-China phone flows', () => {
	for (const page of ['register.vue', 'login.vue', 'password-reset.vue']) {
		const source = read(`pages/auth/${page}`)
		assert.match(source, /PhoneDeliveryMethod/)
		assert.match(source, /phoneDeliveryMethod:\s*'SMS'/)
		assert.match(source, /dialCode !== '\+86'/)
		assert.match(source, /this\.phoneDeliveryMethod = 'SMS'/)
	}
})

test('TOTP management reuses SMS and WhatsApp delivery for phone reverification', () => {
	const source = read('pages/account/totp-security.vue')

	assert.match(source, /import PhoneDeliveryMethod from '@\/components\/auth\/phone-delivery-method\.vue'/)
	assert.match(source, /components:\s*\{ AuthTurnstile, PhoneDeliveryMethod \}/)
	assert.match(source, /loadCurrentUserProfile/)
	assert.match(source, /\{ value: 'SMS_CODE', label: '手机验证码' \}/)
	assert.match(source, /phoneDeliveryMethod:\s*'SMS'/)
	assert.match(source, /phoneSupportsWhatsapp\(\)[\s\S]*!phone\.startsWith\('\+86'\)/)
	assert.match(source, /v-if="phoneSupportsWhatsapp"/)
	assert.match(source, /v-model="phoneDeliveryMethod"/)
	assert.match(source, /:disabled="busy \|\| codeSent"/)
	assert.match(source, /this\.verificationMethod === 'SMS_CODE'\s*\? this\.phoneDeliveryMethod\s*:\s*undefined/)
	assert.match(source, /selectVerificationMethod\(method\)[\s\S]*this\.phoneDeliveryMethod = 'SMS'/)
	assert.match(source, /resetOverview\(\)[\s\S]*this\.phoneDeliveryMethod = 'SMS'/)
	assert.doesNotMatch(source, /this\.verificationMethod === 'SMS_CODE' \? 'SMS' : undefined/)
})

test('auth API sends deliveryMethod while preserving the legacy registration SMS route', () => {
	const source = read('common/auth/auth-api.js')

	assert.match(source, /\/api\/auth\/register\/codes\/phone\/send/)
	assert.match(source, /\/api\/auth\/register\/codes\/sms\/send/)
	assert.match(source, /data:\s*\{ deliveryMethod \}/)
	assert.match(source, /loginCodeSend\(flow, deliveryMethod\)/)
	assert.match(source, /passwordResetSend\(flow, deliveryMethod\)/)
})
