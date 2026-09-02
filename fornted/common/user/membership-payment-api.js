import { authorizedRequest } from '../auth/http-client.js'

const PERSONAL_TIERS = new Set(['GO', 'PLUS', 'PRO', 'MAX'])
const ALL_TIERS = new Set(['FREE', 'GO', 'EDU', 'TEAM', 'PLUS', 'PRO', 'MAX'])
const PAY_TYPES = new Set(['alipay', 'wxpay'])
const PROVIDERS = new Set(['LOCAL_SIMULATOR', 'BAR', 'LIUHAO'])
const PUBLIC_PROVIDERS = new Set(['BAR', 'LIUHAO'])
const TRANSITIONS = new Set(['NEW_PURCHASE', 'UPGRADE'])
const ORDER_STATUSES = new Set([
	'PENDING_PAYMENT', 'CLOSING', 'PAID', 'CANCELLED', 'CLOSED'
])
const ORDER_ID_PATTERN = /^[A-Za-z0-9_-]{22}$/
const UUID_V4_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const MONEY_PATTERN = /^(?:0|[1-9]\d{0,9})\.\d{2}$/
const TIMESTAMP_PATTERN = /^[1-9]\d{9}$/
const KEY_VERSION_PATTERN = /^[1-9]\d*$/
const SIGN_PATTERN = /^[0-9a-f]{64}$/
const BAR_SUBMIT_ACTION = 'https://ihaveagoddamnplan.com/api/pay/submit'
const BAR_NOTIFY_URL = 'https://niko000o.site/api/payment/bar/notify'
const BAR_RETURN_ORIGIN = 'https://niko000o.site'
const ORDER_KEYS = Object.freeze([
	'closingDeadlineAt', 'createdAt', 'expiresAt', 'membershipTier', 'orderId',
	'paidAt', 'payAmountYuan', 'payType', 'paymentStartedAt', 'status', 'updatedAt'
])
const PAYMENT_ATTEMPT_KEYS = Object.freeze(['checkoutSubmission', 'order'])
const CHECKOUT_SUBMISSION_KEYS = Object.freeze([
	'action', 'checkoutMode', 'contentType', 'fields', 'method', 'provider', 'submitExpiresAt'
])
const BAR_CHECKOUT_FIELD_KEYS = Object.freeze([
	'key_version', 'money', 'name', 'notify_url', 'out_trade_no', 'pid',
	'return_url', 'sign', 'sign_type', 'timestamp', 'type'
])
const FORM_CONTENT_TYPE_PATTERN = /^application\/x-www-form-urlencoded(?:;\s*charset=UTF-8)?$/i
const MAX_REDIRECT_URL_LENGTH = 4096
const DISPLAY_NAMES = Object.freeze({
	GO: 'Go',
	PLUS: 'Plus',
	PRO: 'Pro',
	MAX: 'Ultra'
})

function clientError(message, code) {
	const error = new Error(message)
	error.code = code
	return error
}

function inputError(message) {
	return clientError(message, 'MEMBERSHIP_PAYMENT_INPUT_INVALID')
}

function responseError(message = '会员支付响应格式无效。') {
	return clientError(message, 'MEMBERSHIP_PAYMENT_RESPONSE_INVALID')
}

function expiredSubmissionError(provider) {
	return clientError(
		'支付提交描述已过期，请重新获取。',
		`${provider}_CHECKOUT_SUBMISSION_EXPIRED`)
}

function objectValue(value) {
	if (!value || typeof value !== 'object' || Array.isArray(value)) throw responseError()
	return value
}

function money(value) {
	if (typeof value !== 'string' || !MONEY_PATTERN.test(value)) throw responseError()
	return value
}

function hasExactKeys(value, expected) {
	const keys = Object.keys(value).sort()
	const sortedExpected = [...expected].sort()
	return keys.length === sortedExpected.length
		&& keys.every((key, index) => key === sortedExpected[index])
}

function absoluteUrl(value) {
	if (typeof value !== 'string' || !value.trim()) return null
	try {
		return new URL(value)
	} catch (_) {
		return null
	}
}

function isoTime(value, { optional = false } = {}) {
	if (optional && (value == null || value === '')) return null
	if (typeof value !== 'string' || !value.trim() || !Number.isFinite(Date.parse(value))) {
		throw responseError()
	}
	return value
}

function normalizedOffer(value) {
	const source = objectValue(value)
	if (!PERSONAL_TIERS.has(source.targetTier)
		|| source.displayName !== DISPLAY_NAMES[source.targetTier]
		|| !TRANSITIONS.has(source.transitionType)) throw responseError()
	return Object.freeze({
		targetTier: source.targetTier,
		displayName: source.displayName,
		listPriceYuan: money(source.listPriceYuan),
		creditAmountYuan: money(source.creditAmountYuan),
		payAmountYuan: money(source.payAmountYuan),
		transitionType: source.transitionType
	})
}

function normalizedOffers(value) {
	const source = objectValue(value)
	if (!ALL_TIERS.has(source.currentTier)
		|| !PROVIDERS.has(source.provider)
		|| typeof source.checkoutEnabled !== 'boolean'
		|| !Array.isArray(source.payTypes)
		|| !Array.isArray(source.paymentOptions)
		|| !Array.isArray(source.offers)
		|| source.offers.length > PERSONAL_TIERS.size) throw responseError()
	const payTypes = source.payTypes.map(item => {
		if (!PAY_TYPES.has(item)) throw responseError()
		return item
	})
	if (new Set(payTypes).size !== payTypes.length) throw responseError()
	const offers = source.offers.map(normalizedOffer)
	if (new Set(offers.map(offer => offer.targetTier)).size !== offers.length) {
		throw responseError()
	}
	const paymentOptions = source.paymentOptions.map(value => {
		const option = objectValue(value)
		const expectedMode = option.provider === 'LIUHAO' ? 'REDIRECT_URL' : 'FORM_POST'
		if (!PUBLIC_PROVIDERS.has(option.provider)
			|| option.checkoutMode !== expectedMode
			|| !Array.isArray(option.payTypes)) throw responseError()
		const optionPayTypes = option.payTypes.map(item => {
			if (!PAY_TYPES.has(item)) throw responseError()
			return item
		})
		if (optionPayTypes.length !== 2
			|| new Set(optionPayTypes).size !== optionPayTypes.length) throw responseError()
		return Object.freeze({
			provider: option.provider,
			payTypes: Object.freeze(optionPayTypes),
			checkoutMode: option.checkoutMode
		})
	})
	if (new Set(paymentOptions.map(option => option.provider)).size !== paymentOptions.length) {
		throw responseError()
	}
	return Object.freeze({
		currentTier: source.currentTier,
		provider: source.provider,
		checkoutEnabled: source.checkoutEnabled,
		quotedAt: isoTime(source.quotedAt),
		payTypes: Object.freeze(payTypes),
		paymentOptions: Object.freeze(paymentOptions),
		offers: Object.freeze(offers)
	})
}

function normalizedOrder(value) {
	const source = objectValue(value)
	if (!hasExactKeys(source, ORDER_KEYS)
		|| !ORDER_ID_PATTERN.test(source.orderId)
		|| !PERSONAL_TIERS.has(source.membershipTier)
		|| !PAY_TYPES.has(source.payType)
		|| !ORDER_STATUSES.has(source.status)) {
		throw responseError()
	}
	return Object.freeze({
		orderId: source.orderId,
		membershipTier: source.membershipTier,
		payAmountYuan: money(source.payAmountYuan),
		payType: source.payType,
		status: source.status,
		paymentStartedAt: isoTime(source.paymentStartedAt, { optional: true }),
		expiresAt: isoTime(source.expiresAt),
		closingDeadlineAt: isoTime(source.closingDeadlineAt, { optional: true }),
		paidAt: isoTime(source.paidAt, { optional: true }),
		createdAt: isoTime(source.createdAt),
		updatedAt: isoTime(source.updatedAt)
	})
}

function normalizedCheckoutSubmission(value, order, nowMillis = Date.now()) {
	if (value == null) return null
	const source = objectValue(value)
	if (!hasExactKeys(source, CHECKOUT_SUBMISSION_KEYS)
		|| !PUBLIC_PROVIDERS.has(source.provider)) {
		throw responseError()
	}
	const submitExpiresAt = isoTime(source.submitExpiresAt)
	const submitExpiresAtMillis = Date.parse(submitExpiresAt)
	if (submitExpiresAtMillis <= nowMillis) throw expiredSubmissionError(source.provider)
	if (submitExpiresAtMillis > Date.parse(order.expiresAt)) throw responseError()

	// 六号只接收后端验签并绑定真实流水后的 HTTPS 顶层跳转，不再接受浏览器直提六号表单。
	if (source.provider === 'LIUHAO' && source.checkoutMode === 'REDIRECT_URL') {
		if (source.checkoutMode !== 'REDIRECT_URL'
			|| source.method !== 'GET'
			|| source.contentType !== null
			|| source.fields !== null
			|| typeof source.action !== 'string'
			|| source.action.length > MAX_REDIRECT_URL_LENGTH
			|| /[\u0000-\u001f\u007f]/.test(source.action)) throw responseError()
		const action = absoluteUrl(source.action)
		if (!action
			|| action.protocol !== 'https:'
			|| action.hostname === ''
			|| action.username !== ''
			|| action.password !== '') throw responseError()
		return Object.freeze({
			provider: source.provider,
			checkoutMode: source.checkoutMode,
			action: source.action,
			method: source.method,
			contentType: null,
			submitExpiresAt,
			fields: null
		})
	}

	if (source.provider !== 'BAR') throw responseError()
	const contract = { action: BAR_SUBMIT_ACTION, fields: BAR_CHECKOUT_FIELD_KEYS }
	if (source.checkoutMode !== 'FORM_POST'
		|| source.method !== 'POST'
		|| typeof source.contentType !== 'string'
		|| !FORM_CONTENT_TYPE_PATTERN.test(source.contentType)
		|| source.action !== contract.action) throw responseError()
	const action = absoluteUrl(source.action)
	if (!action
		|| action.protocol !== 'https:'
		|| action.hostname !== 'ihaveagoddamnplan.com'
		|| action.port !== ''
		|| action.username !== ''
		|| action.password !== ''
		|| action.pathname !== '/api/pay/submit'
		|| action.search !== ''
		|| action.hash !== '') throw responseError()

	const fields = objectValue(source.fields)
	if (!hasExactKeys(fields, contract.fields)
		|| !Object.values(fields).every(field => typeof field === 'string')) {
		throw responseError()
	}
	const returnUrl = absoluteUrl(fields.return_url)
	if (fields.pid !== '1001'
		|| fields.out_trade_no !== order.orderId
		|| !ORDER_ID_PATTERN.test(fields.out_trade_no)
		|| fields.type !== order.payType
		|| fields.name !== '会员模拟支付订单'
		|| fields.money !== order.payAmountYuan
		|| fields.notify_url !== BAR_NOTIFY_URL
		|| !returnUrl
		|| returnUrl.origin !== BAR_RETURN_ORIGIN
		|| returnUrl.protocol !== 'https:'
		|| returnUrl.port !== ''
		|| returnUrl.username !== ''
		|| returnUrl.password !== ''
		|| returnUrl.search !== ''
		|| returnUrl.hash !== ''
		|| !TIMESTAMP_PATTERN.test(fields.timestamp)
		|| !KEY_VERSION_PATTERN.test(fields.key_version)
		|| fields.sign_type !== 'HMAC-SHA256'
		|| !SIGN_PATTERN.test(fields.sign)) {
		throw responseError()
	}

	// 签名字段一旦通过边界校验即整体冻结，避免响应式代码在原生 Form 提交前改写金额、地址或签名。
	const normalizedFields = Object.freeze(Object.fromEntries(
		contract.fields.map(key => [key, fields[key]])))
	return Object.freeze({
		provider: source.provider,
		checkoutMode: source.checkoutMode,
		action: source.action,
		method: source.method,
		contentType: source.contentType,
		submitExpiresAt,
		fields: normalizedFields
	})
}

function normalizedPaymentAttempt(value) {
	const source = objectValue(value)
	if (!hasExactKeys(source, PAYMENT_ATTEMPT_KEYS)) throw responseError()
	const order = normalizedOrder(source.order)
	return Object.freeze({
		order,
		checkoutSubmission: normalizedCheckoutSubmission(source.checkoutSubmission, order)
	})
}

function requiredOrderId(value) {
	const orderId = String(value || '').trim()
	if (!ORDER_ID_PATTERN.test(orderId)) throw inputError('会员订单编号无效。')
	return orderId
}

export const membershipPaymentApi = Object.freeze({
	async offers() {
		return normalizedOffers(await authorizedRequest(
			'/api/user/membership-plan-offers',
			{ method: 'GET', preserveSessionOnFailure: true }
		))
	},

	async createOrder(command) {
		const source = command && typeof command === 'object' ? command : {}
		if (!PERSONAL_TIERS.has(source.targetTier)
			|| !PAY_TYPES.has(source.payType)
			|| typeof source.idempotencyKey !== 'string'
			|| !UUID_V4_PATTERN.test(source.idempotencyKey)) {
			throw inputError('会员购买参数无效。')
		}
		return normalizedOrder(await authorizedRequest('/api/user/membership-orders', {
			method: 'POST',
			preserveSessionOnFailure: true,
			data: {
				targetTier: source.targetTier,
				payType: source.payType,
				idempotencyKey: source.idempotencyKey
			}
		}))
	},

	async startPayment(orderId, provider) {
		const id = requiredOrderId(orderId)
		if (!PUBLIC_PROVIDERS.has(provider)) {
			throw inputError('支付提供方无效。')
		}
		return normalizedPaymentAttempt(await authorizedRequest(
			`/api/user/membership-orders/${id}/payment-attempts`,
			{
				method: 'POST',
				preserveSessionOnFailure: true,
				data: { provider }
			}
		))
	},

	async order(orderId) {
		const id = requiredOrderId(orderId)
		return normalizedOrder(await authorizedRequest(
			`/api/user/membership-orders/${id}`,
			{ method: 'GET', preserveSessionOnFailure: true }
		))
	},

	async cancelOrder(orderId) {
		const id = requiredOrderId(orderId)
		return normalizedOrder(await authorizedRequest(
			`/api/user/membership-orders/${id}/cancel`,
			{ method: 'POST', preserveSessionOnFailure: true }
		))
	}
})
