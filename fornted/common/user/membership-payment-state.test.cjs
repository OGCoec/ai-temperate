const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadState() {
	const nonce = `${Date.now()}-${Math.random()}`
	const source = fs.readFileSync(
		path.resolve(__dirname, 'membership-payment-state.js'), 'utf8')
	return import(`${sourceUrl(source)}#state-${nonce}`)
}

function memoryStorage() {
	const values = new Map()
	return {
		getItem(key) { return values.has(key) ? values.get(key) : null },
		setItem(key, value) { values.set(key, String(value)) },
		removeItem(key) { values.delete(key) }
	}
}

function checkoutSubmission(fields = {}) {
	return Object.freeze({
		provider: 'BAR',
		checkoutMode: 'FORM_POST',
		action: 'https://ihaveagoddamnplan.com/api/pay/submit',
		method: 'POST',
		contentType: 'application/x-www-form-urlencoded',
		submitExpiresAt: '2099-08-21T12:04:00Z',
		fields: Object.freeze({
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
			...fields
		})
	})
}

function liuhaoCheckoutSubmission() {
	return Object.freeze({
		provider: 'LIUHAO',
		checkoutMode: 'FORM_POST',
		action: 'https://liuhao.net/api/pay/submit',
		method: 'POST',
		contentType: 'application/x-www-form-urlencoded; charset=UTF-8',
		submitExpiresAt: '2099-08-21T12:04:00Z',
		fields: Object.freeze({
			pid: '1001',
			out_trade_no: 'AaAjECcaAQGqi_h2Rl1PiA',
			type: 'wxpay',
			name: '会员支付订单',
			money: '0.05',
			notify_url: 'https://niko000o.site/api/payment/liuhao/notify',
			return_url: 'https://niko000o.site/pages/account/payment-result',
			timestamp: '4080470400',
			sign_type: 'RSA',
			sign: 'c2lnbmF0dXJl'
		})
	})
}

function legacyLiuhaoRedirectSubmission() {
	return Object.freeze({
		provider: 'LIUHAO',
		checkoutMode: 'REDIRECT_URL',
		action: 'https://cashier.liuhao.net/pay/qrcode/session-123',
		method: 'GET',
		contentType: null,
		submitExpiresAt: '2099-08-21T12:04:00Z',
		fields: null
	})
}

function fakeDomAdapter({ failInputAt = -1 } = {}) {
	const inputs = []
	let removed = false
	let submittedForm = null
	const form = {
		children: [],
		appendChild(input) {
			if (this.children.length === failInputAt) throw new Error('synthetic fill failure')
			this.children.push(input)
		},
		remove() { removed = true }
	}
	const document = {
		body: {
			appendChild(value) { value.parentNode = this },
			removeChild() { removed = true }
		},
		createElement(tagName) {
			if (tagName === 'form') return form
			const input = {}
			inputs.push(input)
			return input
		}
	}
	return {
		adapter: {
			document,
			submit(value) { submittedForm = value }
		},
		form,
		inputs,
		removed: () => removed,
		submittedForm: () => submittedForm
	}
}

test('creates a canonical UUIDv4 from secure random bytes', async () => {
	const { createPaymentIdempotencyKey } = await loadState()
	const key = createPaymentIdempotencyKey(bytes => {
		for (let index = 0; index < bytes.length; index += 1) bytes[index] = index
		return bytes
	})

	assert.equal(key, '00010203-0405-4607-8809-0a0b0c0d0e0f')
})

test('creates one hidden input per whitelisted field and submits the form adapter', async () => {
	const { submitBarCheckout } = await loadState()
	const fake = fakeDomAdapter()

	submitBarCheckout(checkoutSubmission(), fake.adapter)

	assert.equal(fake.form.action, 'https://ihaveagoddamnplan.com/api/pay/submit')
	assert.equal(fake.form.method, 'post')
	assert.equal(fake.form.enctype, 'application/x-www-form-urlencoded')
	assert.equal(fake.form.acceptCharset, 'UTF-8')
	assert.equal(fake.form.hidden, true)
	assert.equal('target' in fake.form, false)
	assert.equal(fake.submittedForm(), fake.form)
	assert.equal(fake.form.children.length, 11)
	assert.equal(new Set(fake.form.children.map(input => input.name)).size, 11)
	assert.deepEqual(
		fake.form.children.map(input => input.name).sort(),
		[
			'key_version', 'money', 'name', 'notify_url', 'out_trade_no', 'pid',
			'return_url', 'sign', 'sign_type', 'timestamp', 'type'
		])
	assert.equal(fake.form.children.every(input => input.type === 'hidden'), true)
	assert.deepEqual(
		Object.fromEntries(fake.form.children.map(input => [input.name, input.value])),
		checkoutSubmission().fields)
})

test('rejects extra checkout fields before any form submission', async () => {
	const { submitBarCheckout } = await loadState()
	const fake = fakeDomAdapter()

	assert.throws(
		() => submitBarCheckout(checkoutSubmission({ param: 'unexpected' }), fake.adapter),
		/支付提交描述无效/)
	assert.equal(fake.inputs.length, 0)
	assert.equal(fake.submittedForm(), null)
})

test('submits the normalized Liuhao page contract without navigating or adding fields', async () => {
	const { submitPaymentCheckout } = await loadState()
	const fake = fakeDomAdapter()
	let navigatedTo = null
	fake.adapter.navigate = value => { navigatedTo = value }

	submitPaymentCheckout(liuhaoCheckoutSubmission(), fake.adapter)

	assert.equal(navigatedTo, null)
	assert.equal(fake.form.action, 'https://liuhao.net/api/pay/submit')
	assert.equal(fake.form.method, 'post')
	assert.equal(fake.form.children.length, 10)
	assert.equal(fake.form.children.some(input => input.name === 'key_version'), false)
	assert.equal(fake.submittedForm(), fake.form)
})

test('temporarily navigates legacy Liuhao redirects during frontend-first rollout', async () => {
	const { submitPaymentCheckout } = await loadState()
	const fake = fakeDomAdapter()
	let navigatedTo = null
	fake.adapter.navigate = value => { navigatedTo = value }

	submitPaymentCheckout(legacyLiuhaoRedirectSubmission(), fake.adapter)

	assert.equal(navigatedTo, 'https://cashier.liuhao.net/pay/qrcode/session-123')
	assert.equal(fake.form.children.length, 0)
	assert.equal(fake.submittedForm(), null)
})

test('removes the temporary form when input creation or filling fails', async () => {
	const { submitBarCheckout } = await loadState()
	const fake = fakeDomAdapter({ failInputAt: 3 })
	const secretSign = checkoutSubmission().fields.sign

	assert.throws(
		() => submitBarCheckout(checkoutSubmission(), fake.adapter),
		error => !error.message.includes(secretSign))
	assert.equal(fake.removed(), true)
	assert.equal(fake.submittedForm(), null)
})

test('production adapter invokes the native HTML form submit method', async () => {
	const { submitBarCheckout } = await loadState()
	const fake = fakeDomAdapter()
	const previousDocument = globalThis.document
	const previousHtmlFormElement = globalThis.HTMLFormElement
	let nativeReceiver = null
	globalThis.document = fake.adapter.document
	globalThis.HTMLFormElement = {
		prototype: {
			submit() { nativeReceiver = this }
		}
	}
	try {
		submitBarCheckout(checkoutSubmission())
		assert.equal(nativeReceiver, fake.form)
	} finally {
		globalThis.document = previousDocument
		globalThis.HTMLFormElement = previousHtmlFormElement
	}
})

test('runtime source contains neither the legacy fragment nor redirect validator', () => {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'membership-payment-state.js'), 'utf8')

	assert.doesNotMatch(source, /#token|validateBarPaymentTarget|BAR_PAY_PATH_PATTERN/)
	assert.doesNotMatch(source, /innerHTML|fetch\(|XMLHttpRequest|window\.open|_blank/)
})

test('stores only order id and start time and rejects stale return context', async () => {
	const {
		PAYMENT_RETURN_CONTEXT_KEY,
		clearPaymentReturnContext,
		readPaymentReturnContext,
		writePaymentReturnContext
	} = await loadState()
	const storage = memoryStorage()
	const now = Date.parse('2026-08-21T12:00:00Z')

	writePaymentReturnContext(
		storage, 'AaAjECcaAQGqi_h2Rl1PiA', now)

	assert.deepEqual(readPaymentReturnContext(storage, now + 1000), {
		orderId: 'AaAjECcaAQGqi_h2Rl1PiA',
		startedAt: now
	})
	assert.deepEqual(
		Object.keys(JSON.parse(storage.getItem(PAYMENT_RETURN_CONTEXT_KEY))).sort(),
		['orderId', 'startedAt'])
	assert.equal(readPaymentReturnContext(storage, now + (31 * 60 * 1000)), null)
	clearPaymentReturnContext(storage)
	assert.equal(storage.getItem(PAYMENT_RETURN_CONTEXT_KEY), null)
})

test('keeps idempotency only for uncertain transport outcomes', async () => {
	const { isUncertainPaymentError } = await loadState()
	assert.equal(isUncertainPaymentError({ code: 'NETWORK_ERROR' }), true)
	assert.equal(isUncertainPaymentError({ code: 'BAR_TIMEOUT', statusCode: 504 }), true)
	assert.equal(isUncertainPaymentError({ code: 'LIUHAO_TIMEOUT', statusCode: 504 }), true)
	assert.equal(isUncertainPaymentError({ code: 'LIUHAO_CREATE_OUTCOME_UNKNOWN' }), true)
	assert.equal(isUncertainPaymentError({ code: 'PAYMENT_CREATE_OUTCOME_UNKNOWN' }), true)
	assert.equal(isUncertainPaymentError({ code: 'LIUHAO_CHECKOUT_UNAVAILABLE' }), true)
	assert.equal(isUncertainPaymentError({ statusCode: 503 }), true)
	assert.equal(isUncertainPaymentError({ code: 'BAR_ORDER_CONFLICT', statusCode: 409 }), false)
})
