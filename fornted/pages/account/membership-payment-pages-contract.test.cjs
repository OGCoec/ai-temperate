const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function source(relativePath) {
	return fs.readFileSync(path.resolve(__dirname, relativePath), 'utf8')
}

test('registers two H5 protected membership payment pages', () => {
	const pages = source('../../pages.json')
	const protectedRoutes = source('../../common/auth/protected-routes.js')

	assert.match(pages, /pages\/account\/membership-plans/)
	assert.match(pages, /pages\/account\/payment-result/)
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
	assert.doesNotMatch(page, /Flask|fetch\s*\(|window\.location\.assign|#token|payUrl|payUrlExpiresAt/)
	assert.doesNotMatch(page, /writePaymentReturnContext\([^)]*(?:fields|sign)/)
})

test('payment result page trusts only the authenticated local order query', () => {
	const page = source('payment-result.vue')

	assert.match(page, /membershipPaymentApi\.order\(this\.context\.orderId\)/)
	assert.match(page, /模拟支付已确认，会员权益未发放/)
	assert.match(page, /POLL_INTERVAL_MILLIS\s*=\s*2000/)
	assert.match(page, /POLL_WINDOW_MILLIS\s*=\s*30000/)
	assert.doesNotMatch(page, /URLSearchParams|location\.search|ihaveagoddamnplan/)
})

test('profile quota panel exposes the H5 upgrade entry', () => {
	const profile = source('../../components/user/workspace/user-profile-panel.vue')
	const config = source('../../common/auth/config.js')

	assert.match(profile, /@click="openMembershipPlans"/)
	assert.match(profile, /升级套餐/)
	assert.match(config, /membershipPlans:\s*'\/pages\/account\/membership-plans'/)
	assert.match(config, /paymentResult:\s*'\/pages\/account\/payment-result'/)
})
