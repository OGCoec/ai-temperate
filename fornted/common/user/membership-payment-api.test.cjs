const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadApi(request) {
	const nonce = `${Date.now()}-${Math.random()}`
	globalThis.__membershipPaymentAuthorizedRequest = request
	const httpClientUrl = `${sourceUrl('export const authorizedRequest = (...args) => globalThis.__membershipPaymentAuthorizedRequest(...args)')}#http-${nonce}`
	const source = fs.readFileSync(
		path.resolve(__dirname, 'membership-payment-api.js'), 'utf8')
		.replace("from '../auth/http-client.js'", `from '${httpClientUrl}'`)
	return import(`${sourceUrl(source)}#api-${nonce}`)
}

function offerResponse(overrides = {}) {
	return {
		currentTier: 'FREE',
		provider: 'BAR',
		checkoutEnabled: true,
		quotedAt: '2026-08-21T12:00:00Z',
		payTypes: ['alipay', 'wxpay'],
		paymentOptions: [
			{ provider: 'BAR', payTypes: ['alipay', 'wxpay'], checkoutMode: 'FORM_POST' },
			{ provider: 'LIUHAO', payTypes: ['alipay', 'wxpay'], checkoutMode: 'REDIRECT_URL' }
		],
		offers: [{
			targetTier: 'GO',
			displayName: 'Go',
			listPriceYuan: '0.05',
			creditAmountYuan: '0.00',
			payAmountYuan: '0.05',
			transitionType: 'NEW_PURCHASE'
		}],
		...overrides
	}
}

function orderResponse(overrides = {}) {
	return {
		orderId: 'AaAjECcaAQGqi_h2Rl1PiA',
		membershipTier: 'GO',
		payAmountYuan: '0.05',
		payType: 'alipay',
		status: 'PENDING_PAYMENT',
		paymentStartedAt: null,
		expiresAt: '2099-08-21T12:05:00Z',
		closingDeadlineAt: null,
		paidAt: null,
		createdAt: '2026-08-21T12:00:00Z',
		updatedAt: '2026-08-21T12:00:00Z',
		...overrides
	}
}

function checkoutFields(overrides = {}) {
	return {
		pid: '1001',
		out_trade_no: 'AaAjECcaAQGqi_h2Rl1PiA',
		type: 'alipay',
		name: '会员模拟支付订单',
		money: '0.05',
		notify_url: 'https://niko000o.site/api/payment/bar/notify',
		return_url: 'https://niko000o.site/membership/payment/result',
		timestamp: '4080470400',
		key_version: '1',
		sign_type: 'HMAC-SHA256',
		sign: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
		...overrides
	}
}

function checkoutSubmission(overrides = {}) {
	return {
		provider: 'BAR',
		checkoutMode: 'FORM_POST',
		action: 'https://ihaveagoddamnplan.com/api/pay/submit',
		method: 'POST',
		contentType: 'application/x-www-form-urlencoded',
		submitExpiresAt: '2099-08-21T12:04:00Z',
		fields: checkoutFields(),
		...overrides
	}
}

function liuhaoCheckoutFields(overrides = {}) {
	return {
		pid: '1001',
		out_trade_no: 'AaAjECcaAQGqi_h2Rl1PiA',
		type: 'wxpay',
		name: '会员支付订单',
		money: '0.05',
		notify_url: 'https://niko000o.site/api/payment/liuhao/notify',
		return_url: 'https://niko000o.site/pages/account/payment-result',
		timestamp: '4080470400',
		sign_type: 'RSA',
		sign: 'c2lnbmF0dXJl',
		...overrides
	}
}

function liuhaoCheckoutSubmission(overrides = {}) {
	return {
		provider: 'LIUHAO',
		checkoutMode: 'FORM_POST',
		action: 'https://liuhao.net/api/pay/submit',
		method: 'POST',
		contentType: 'application/x-www-form-urlencoded; charset=UTF-8',
		submitExpiresAt: '2099-08-21T12:04:00Z',
		fields: liuhaoCheckoutFields(),
		...overrides
	}
}

function legacyLiuhaoRedirectSubmission(overrides = {}) {
	return {
		provider: 'LIUHAO',
		checkoutMode: 'REDIRECT_URL',
		action: 'https://cashier.liuhao.net/pay/session-123',
		method: 'GET',
		contentType: null,
		submitExpiresAt: '2099-08-21T12:04:00Z',
		fields: null,
		...overrides
	}
}

function paymentAttemptResponse(overrides = {}) {
	return {
		order: orderResponse(),
		checkoutSubmission: checkoutSubmission(),
		...overrides
	}
}

test('loads immutable server-priced personal membership offers', async () => {
	const calls = []
	const { membershipPaymentApi } = await loadApi(async (...args) => {
		calls.push(args)
		return offerResponse()
	})

	const result = await membershipPaymentApi.offers()

	assert.deepEqual(calls, [[
		'/api/user/membership-plan-offers',
		{ method: 'GET', preserveSessionOnFailure: true }
	]])
	assert.equal(result.offers[0].payAmountYuan, '0.05')
	assert.equal(Object.isFrozen(result), true)
	assert.equal(Object.isFrozen(result.offers[0]), true)
	assert.deepEqual(result.paymentOptions.map(option => option.provider), ['BAR', 'LIUHAO'])
})

test('creates and starts an order without sending a client price', async () => {
	const calls = []
	const { membershipPaymentApi } = await loadApi(async (...args) => {
		calls.push(args)
		return calls.length === 1
			? orderResponse()
			: paymentAttemptResponse()
	})

	await membershipPaymentApi.createOrder({
		targetTier: 'GO',
		payType: 'alipay',
		idempotencyKey: '550e8400-e29b-41d4-a716-446655440000'
	})
	const attempt = await membershipPaymentApi.startPayment('AaAjECcaAQGqi_h2Rl1PiA', 'BAR')

	assert.deepEqual(calls[0], [
		'/api/user/membership-orders',
		{
			method: 'POST',
			preserveSessionOnFailure: true,
			data: {
				targetTier: 'GO',
				payType: 'alipay',
				idempotencyKey: '550e8400-e29b-41d4-a716-446655440000'
			}
		}
	])
	assert.deepEqual(calls[1], [
		'/api/user/membership-orders/AaAjECcaAQGqi_h2Rl1PiA/payment-attempts',
		{ method: 'POST', preserveSessionOnFailure: true, data: { provider: 'BAR' } }
	])
	assert.equal('payAmountYuan' in calls[0][1].data, false)
	assert.equal(attempt.order.orderId, 'AaAjECcaAQGqi_h2Rl1PiA')
	assert.equal(attempt.checkoutSubmission.fields.out_trade_no, attempt.order.orderId)
	assert.equal(Object.isFrozen(attempt), true)
	assert.equal(Object.isFrozen(attempt.order), true)
	assert.equal(Object.isFrozen(attempt.checkoutSubmission), true)
	assert.equal(Object.isFrozen(attempt.checkoutSubmission.fields), true)
})

test('accepts an explicit null checkout submission for the local provider', async () => {
	const { membershipPaymentApi } = await loadApi(async () => paymentAttemptResponse({
		checkoutSubmission: null
	}))

	const attempt = await membershipPaymentApi.startPayment('AaAjECcaAQGqi_h2Rl1PiA', 'BAR')

	assert.equal(attempt.checkoutSubmission, null)
})

test('rejects unknown payment-attempt and checkout field keys', async () => {
	for (const response of [
		{ ...paymentAttemptResponse(), legacy: true },
		{ order: orderResponse() },
		paymentAttemptResponse({
			order: orderResponse({ payUrl: 'https://legacy.example/redirect' })
		}),
		paymentAttemptResponse({
			checkoutSubmission: checkoutSubmission({ legacy: 'value' })
		}),
		paymentAttemptResponse({
			checkoutSubmission: checkoutSubmission({
				fields: checkoutFields({ param: 'unexpected' })
			})
		}),
		paymentAttemptResponse({
			checkoutSubmission: checkoutSubmission({
				fields: Object.fromEntries(Object.entries(checkoutFields())
					.filter(([key]) => key !== 'sign'))
			})
		})
	]) {
		const { membershipPaymentApi } = await loadApi(async () => response)
		await assert.rejects(
			() => membershipPaymentApi.startPayment('AaAjECcaAQGqi_h2Rl1PiA', 'BAR'),
			error => error.code === 'MEMBERSHIP_PAYMENT_RESPONSE_INVALID')
	}
})

test('rejects unsafe or inconsistent BAR checkout submissions', async () => {
	const invalidSubmissions = [
		checkoutSubmission({ provider: 'LOCAL_SIMULATOR' }),
		checkoutSubmission({ action: 'https://evil.example/api/pay/submit' }),
		checkoutSubmission({ action: 'https://ihaveagoddamnplan.com/api/pay/submit?leak=1' }),
		checkoutSubmission({ method: 'GET' }),
		checkoutSubmission({ contentType: 'application/json' }),
		checkoutSubmission({ fields: checkoutFields({ pid: '1002' }) }),
		checkoutSubmission({ fields: checkoutFields({ out_trade_no: 'BaAjECcaAQGqi_h2Rl1PiA' }) }),
		checkoutSubmission({ fields: checkoutFields({ type: 'wxpay' }) }),
		checkoutSubmission({ fields: checkoutFields({ name: '其他订单' }) }),
		checkoutSubmission({ fields: checkoutFields({ money: '0.06' }) }),
		checkoutSubmission({ fields: checkoutFields({ notify_url: 'https://evil.example/callback' }) }),
		checkoutSubmission({ fields: checkoutFields({ return_url: 'https://niko000o.site/result?trade=1' }) }),
		checkoutSubmission({ fields: checkoutFields({ timestamp: '04080470400' }) }),
		checkoutSubmission({ fields: checkoutFields({ key_version: '01' }) }),
		checkoutSubmission({ fields: checkoutFields({ sign_type: 'MD5' }) }),
		checkoutSubmission({ fields: checkoutFields({ sign: 'A'.repeat(64) }) }),
		checkoutSubmission({ fields: checkoutFields({ pid: 1001 }) }),
		checkoutSubmission({ submitExpiresAt: '2100-08-21T12:04:00Z' })
	]

	for (const checkoutSubmission of invalidSubmissions) {
		const { membershipPaymentApi } = await loadApi(async () => paymentAttemptResponse({
			checkoutSubmission
		}))
		await assert.rejects(
			() => membershipPaymentApi.startPayment('AaAjECcaAQGqi_h2Rl1PiA', 'BAR'),
			error => error.code === 'MEMBERSHIP_PAYMENT_RESPONSE_INVALID')
	}
})

test('marks an otherwise valid expired submission for one bounded refresh', async () => {
	const { membershipPaymentApi } = await loadApi(async () => paymentAttemptResponse({
		checkoutSubmission: checkoutSubmission({ submitExpiresAt: '2026-08-21T12:04:00Z' })
	}))

	await assert.rejects(
		() => membershipPaymentApi.startPayment('AaAjECcaAQGqi_h2Rl1PiA', 'BAR'),
		error => error.code === 'BAR_CHECKOUT_SUBMISSION_EXPIRED')
})

test('rejects the obsolete Liuhao browser page-submit contract', async () => {
	const { membershipPaymentApi } = await loadApi(async () => paymentAttemptResponse({
		order: orderResponse({ payType: 'wxpay' }),
		checkoutSubmission: liuhaoCheckoutSubmission()
	}))
	await assert.rejects(
		() => membershipPaymentApi.startPayment('AaAjECcaAQGqi_h2Rl1PiA', 'LIUHAO'),
		error => error.code === 'MEMBERSHIP_PAYMENT_RESPONSE_INVALID')

	const unsafe = await loadApi(async () => paymentAttemptResponse({
		order: orderResponse({ payType: 'wxpay' }),
		checkoutSubmission: liuhaoCheckoutSubmission({
			action: 'https://evil.example/api/pay/submit'
		})
	}))
	await assert.rejects(
		() => unsafe.membershipPaymentApi.startPayment('AaAjECcaAQGqi_h2Rl1PiA', 'LIUHAO'),
		error => error.code === 'MEMBERSHIP_PAYMENT_RESPONSE_INVALID')
})

test('accepts the Liuhao HTTPS cashier redirect after backend validation', async () => {
	const { membershipPaymentApi } = await loadApi(async () => paymentAttemptResponse({
		checkoutSubmission: legacyLiuhaoRedirectSubmission()
	}))

	const attempt = await membershipPaymentApi.startPayment(
		'AaAjECcaAQGqi_h2Rl1PiA', 'LIUHAO')

	assert.equal(attempt.checkoutSubmission.checkoutMode, 'REDIRECT_URL')
	assert.equal(attempt.checkoutSubmission.fields, null)
})

test('queries only canonical 22-character order identifiers', async () => {
	const calls = []
	const { membershipPaymentApi } = await loadApi(async (...args) => {
		calls.push(args)
		return orderResponse({ status: 'PAID' })
	})

	const order = await membershipPaymentApi.order('AaAjECcaAQGqi_h2Rl1PiA')

	assert.equal(order.status, 'PAID')
	assert.deepEqual(calls, [[
		'/api/user/membership-orders/AaAjECcaAQGqi_h2Rl1PiA',
		{ method: 'GET', preserveSessionOnFailure: true }
	]])
	await assert.rejects(
		() => membershipPaymentApi.order('../not-an-order'),
		error => error.code === 'MEMBERSHIP_PAYMENT_INPUT_INVALID')
})

test('rejects malformed offer and order money instead of using browser floats', async () => {
	for (const response of [
		offerResponse({ offers: [{
			targetTier: 'GO',
			displayName: 'Go',
			listPriceYuan: '0.05',
			creditAmountYuan: '0.00',
			payAmountYuan: 0.05,
			transitionType: 'NEW_PURCHASE'
		}] }),
		orderResponse({ payAmountYuan: '0.050' })
	]) {
		const { membershipPaymentApi } = await loadApi(async () => response)
		await assert.rejects(
			() => Array.isArray(response.offers)
				? membershipPaymentApi.offers()
				: membershipPaymentApi.order(response.orderId),
			error => error.code === 'MEMBERSHIP_PAYMENT_RESPONSE_INVALID')
	}
})
