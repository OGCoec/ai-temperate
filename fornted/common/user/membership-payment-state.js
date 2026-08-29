export const PAYMENT_RETURN_CONTEXT_KEY = 'ait:h5:membership-payment:return:v1'

const ORDER_ID_PATTERN = /^[A-Za-z0-9_-]{22}$/
const BAR_SUBMIT_ACTION = 'https://ihaveagoddamnplan.com/api/pay/submit'
const CHECKOUT_SUBMISSION_KEYS = Object.freeze([
	'action', 'contentType', 'fields', 'method', 'provider', 'submitExpiresAt'
])
const CHECKOUT_FIELD_KEYS = Object.freeze([
	'pid', 'out_trade_no', 'type', 'name', 'money', 'notify_url',
	'return_url', 'timestamp', 'key_version', 'sign_type', 'sign'
])
const RETURN_CONTEXT_MAX_AGE_MILLIS = 30 * 60 * 1000
const ALLOWED_FUTURE_SKEW_MILLIS = 60 * 1000
const UNCERTAIN_CODES = new Set([
	'NETWORK_ERROR',
	'BAR_TIMEOUT',
	'BAR_UNAVAILABLE',
	'HTTP_502',
	'HTTP_503',
	'HTTP_504'
])

function defaultSecureRandom(bytes) {
	if (!globalThis.crypto || typeof globalThis.crypto.getRandomValues !== 'function') {
		throw new Error('当前浏览器不支持安全随机数，无法创建支付意图。')
	}
	return globalThis.crypto.getRandomValues(bytes)
}

/**
 * 使用浏览器安全随机数生成规范 UUIDv4，服务端数据库唯一约束仍是最终幂等保障。
 */
export function createPaymentIdempotencyKey(fillRandom = defaultSecureRandom) {
	const bytes = new Uint8Array(16)
	fillRandom(bytes)
	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	const hex = [...bytes].map(value => value.toString(16).padStart(2, '0')).join('')
	return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function hasExactKeys(value, expected) {
	const keys = Object.keys(value).sort()
	const sortedExpected = [...expected].sort()
	return keys.length === sortedExpected.length
		&& keys.every((key, index) => key === sortedExpected[index])
}

function isNormalizedBarSubmission(value) {
	return value
		&& typeof value === 'object'
		&& !Array.isArray(value)
		&& Object.isFrozen(value)
		&& hasExactKeys(value, CHECKOUT_SUBMISSION_KEYS)
		&& value.provider === 'BAR'
		&& value.action === BAR_SUBMIT_ACTION
		&& value.method === 'POST'
		&& value.contentType === 'application/x-www-form-urlencoded'
		&& typeof value.submitExpiresAt === 'string'
		&& value.fields
		&& typeof value.fields === 'object'
		&& !Array.isArray(value.fields)
		&& Object.isFrozen(value.fields)
		&& hasExactKeys(value.fields, CHECKOUT_FIELD_KEYS)
		&& CHECKOUT_FIELD_KEYS.every(key => typeof value.fields[key] === 'string')
}

function defaultCheckoutDomAdapter() {
	return {
		document: globalThis.document,
		submit(form) {
			globalThis.HTMLFormElement.prototype.submit.call(form)
		}
	}
}

function removeTemporaryForm(form) {
	if (!form) return
	try {
		if (typeof form.remove === 'function') {
			form.remove()
		} else if (form.parentNode && typeof form.parentNode.removeChild === 'function') {
			form.parentNode.removeChild(form)
		}
	} catch (_) {
		// 清理失败不能携带或输出签名字段，页面只保留统一的受控错误。
	}
}

/**
 * 将已严格规范化并冻结的 BAR 提交描述转换为一次顶层原生 Form POST，不记录或持久化任何签名字段。
 */
export function submitBarCheckout(submission, domAdapter) {
	if (!isNormalizedBarSubmission(submission)) {
		throw new Error('支付提交描述无效。')
	}
	const runtime = domAdapter || defaultCheckoutDomAdapter()
	if (!runtime.document
		|| typeof runtime.document.createElement !== 'function'
		|| !runtime.document.body
		|| typeof runtime.document.body.appendChild !== 'function'
		|| typeof runtime.submit !== 'function') {
		throw new Error('当前页面无法提交支付请求。')
	}

	let form = null
	try {
		form = runtime.document.createElement('form')
		form.method = 'post'
		form.action = BAR_SUBMIT_ACTION
		form.enctype = 'application/x-www-form-urlencoded'
		form.acceptCharset = 'UTF-8'
		form.hidden = true
		for (const key of CHECKOUT_FIELD_KEYS) {
			const input = runtime.document.createElement('input')
			input.type = 'hidden'
			input.name = key
			input.value = submission.fields[key]
			form.appendChild(input)
		}
		runtime.document.body.appendChild(form)
		runtime.submit(form)
	} catch (_) {
		removeTemporaryForm(form)
		throw new Error('无法提交支付请求，请稍后重试。')
	}
}

export function writePaymentReturnContext(
	storage,
	orderId,
	startedAt = Date.now()) {
	if (!storage || typeof storage.setItem !== 'function'
		|| !ORDER_ID_PATTERN.test(String(orderId || ''))
		|| !Number.isSafeInteger(startedAt)
		|| startedAt <= 0) {
		throw new Error('支付返回上下文无效。')
	}
	// 只保存定位本地订单所需的最小字段，绝不持久化 BAR Form 字段或签名。
	storage.setItem(PAYMENT_RETURN_CONTEXT_KEY, JSON.stringify({ orderId, startedAt }))
}

export function readPaymentReturnContext(storage, nowMillis = Date.now()) {
	if (!storage || typeof storage.getItem !== 'function') return null
	let value = null
	try {
		value = JSON.parse(storage.getItem(PAYMENT_RETURN_CONTEXT_KEY) || 'null')
	} catch (_) {
		value = null
	}
	const valid = value
		&& typeof value === 'object'
		&& ORDER_ID_PATTERN.test(value.orderId)
		&& Number.isSafeInteger(value.startedAt)
		&& value.startedAt > 0
		&& value.startedAt <= nowMillis + ALLOWED_FUTURE_SKEW_MILLIS
		&& nowMillis - value.startedAt <= RETURN_CONTEXT_MAX_AGE_MILLIS
	if (!valid) {
		clearPaymentReturnContext(storage)
		return null
	}
	return Object.freeze({ orderId: value.orderId, startedAt: value.startedAt })
}

export function clearPaymentReturnContext(storage) {
	if (storage && typeof storage.removeItem === 'function') {
		storage.removeItem(PAYMENT_RETURN_CONTEXT_KEY)
	}
}

export function isUncertainPaymentError(error) {
	return UNCERTAIN_CODES.has(error?.code)
		|| [502, 503, 504].includes(Number(error?.statusCode))
}
