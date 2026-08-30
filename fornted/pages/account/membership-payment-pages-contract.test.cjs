const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function source(relativePath) {
	return fs.readFileSync(path.resolve(__dirname, relativePath), 'utf8')
}

test('registers the membership offers page for Android while keeping payment results H5-only', () => {
	const pages = source('../../pages.json')
	const protectedRoutes = source('../../common/auth/protected-routes.js')
	const membershipPlansIndex = pages.indexOf('"path": "pages/account/membership-plans"')
	const paymentResultIndex = pages.indexOf('"path": "pages/account/payment-result"')
	const h5BlockBeforeMembership = pages.lastIndexOf('// #endif', membershipPlansIndex)
	const h5BlockBeforePaymentResult = pages.lastIndexOf('// #ifdef H5', paymentResultIndex)

	assert.ok(membershipPlansIndex > h5BlockBeforeMembership)
	assert.ok(h5BlockBeforePaymentResult > membershipPlansIndex)
	assert.ok(paymentResultIndex > h5BlockBeforePaymentResult)
	assert.doesNotMatch(protectedRoutes, /membership-plans|payment-result/)
})

test('membership plan page performs one-click server-priced BAR checkout', () => {
	const page = source('membership-plans.vue')

	assert.match(page, /membershipPaymentApi\.offers\(\)/)
	assert.match(page, /membershipPaymentApi\.createOrder/)
	assert.match(page, /membershipPaymentApi\.startPayment/)
	assert.match(page, /order\.payAmountYuan\s*!==\s*offer\.payAmountYuan/)
	assert.match(page, /submitBarCheckout\(submission\)/)
	assert.match(page, /writePaymentReturnContext\(sessionStorage, order\.orderId\)/)
	assert.match(page, /BAR_CHECKOUT_SUBMISSION_EXPIRED/)
	assert.equal((page.match(/membershipPaymentApi\.startPayment\(orderId\)/g) || []).length, 2)
	assert.match(page, /:disabled="purchaseDisabled\(offer\)"/)
	assert.match(page, /@click="handleOfferAction\(offer\)"/)
	assert.doesNotMatch(page, /Flask|fetch\s*\(|window\.location\.assign|#token|payUrl|payUrlExpiresAt/)
	assert.doesNotMatch(page, /writePaymentReturnContext\([^)]*(?:fields|sign)/)
})

test('Android membership mode is read-only and returns before every H5 checkout call', () => {
	const page = source('membership-plans.vue')

	assert.match(page, /androidClient\(\)\s*\{[\s\S]*?clientPlatform\(\)\s*===\s*'ANDROID'/)
	assert.match(page, /v-if="!androidClient"[^>]*class="payment-method-block"/)
	assert.match(page, /只读报价/)
	assert.match(page, /Android 客户端不创建订单、不提供支付/)
	assert.match(page, /Android 客户端仅供查看/)
	assert.match(page, /handleOfferAction\(offer\)\s*\{[\s\S]*?if\s*\(this\.androidClient\)[\s\S]*?this\.showAndroidUpgradeNotice\(offer\)[\s\S]*?return[\s\S]*?this\.purchase\(offer\)/)
	assert.match(page, /showAndroidUpgradeNotice\(offer\)\s*\{[\s\S]*?升级到 \$\{offer\.displayName\} 的当前费用为 ¥\$\{offer\.payAmountYuan\}/)
	assert.match(page, /niko000o\.site/)
	assert.match(page, /请前往网页版升级/)
	assert.match(page, /网页版升级维护中/)
	assert.match(page, /\/\/ #ifdef H5[\s\S]*?async purchase\(offer\)/)
	assert.doesNotMatch(page, /plus\.runtime\.openURL|uni\.setClipboardData|二维码/)
})

test('payment result page trusts only the authenticated local order query', () => {
	const page = source('payment-result.vue')

	assert.match(page, /membershipPaymentApi\.order\(this\.context\.orderId\)/)
	assert.match(page, /模拟支付已确认，会员权益未发放/)
	assert.match(page, /POLL_INTERVAL_MILLIS\s*=\s*2000/)
	assert.match(page, /POLL_WINDOW_MILLIS\s*=\s*30000/)
	assert.doesNotMatch(page, /URLSearchParams|location\.search|ihaveagoddamnplan/)
})

test('profile quota panel exposes the upgrade entry with platform-specific guidance', () => {
	const profile = source('../../components/user/workspace/user-profile-panel.vue')
	const config = source('../../common/auth/config.js')

	assert.match(profile, /@click="openMembershipPlans"/)
	assert.match(profile, /升级套餐/)
	assert.match(profile, /查看 Go、Plus、Pro 与 Ultra 的服务端模拟支付报价/)
	assert.match(profile, /查看可升级套餐和实时价格，购买请前往网页版/)
	assert.match(config, /membershipPlans:\s*'\/pages\/account\/membership-plans'/)
	assert.match(config, /paymentResult:\s*'\/pages\/account\/payment-result'/)
})
