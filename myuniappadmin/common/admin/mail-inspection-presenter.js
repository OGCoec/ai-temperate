export const MAIL_INSPECTION_RESULT_GROUPS = Object.freeze([
	Object.freeze({ value: 'ALL', label: '全部结果' }),
	Object.freeze({ value: 'AUTH_ERROR', label: '凭证或授权错误' }),
	Object.freeze({ value: 'INPUT_ERROR', label: '输入错误' }),
	Object.freeze({ value: 'RETRY_EXHAUSTED', label: '网络重试耗尽' }),
	Object.freeze({ value: 'INTERNAL_ERROR', label: '内部处理失败' })
])

const BUSINESS_CATEGORY_BY_STATUS = Object.freeze({
	OPENAI_NO_REGISTRATION_EVIDENCE: 'UNREGISTERED',
	OPENAI_REGISTERED_NORMAL: 'REGISTERED',
	OPENAI_UNCLASSIFIED: 'REGISTERED',
	OPENAI_RESTRICTED_EVIDENCE_FOUND: 'RESTRICTED',
	KIRO_NO_REGISTRATION_EVIDENCE: 'UNREGISTERED',
	KIRO_REGISTERED_NORMAL: 'REGISTERED',
	KIRO_UNCLASSIFIED: 'REGISTERED',
	KIRO_RESTRICTED_EVIDENCE_FOUND: 'RESTRICTED',
	IP2_REGISTRATION_MAIL_NOT_FOUND: 'UNREGISTERED',
	IP2_REGISTRATION_MAIL_FOUND: 'REGISTERED',
	IP2_VERIFY_URL_FOUND: 'VERIFY_FOUND',
	IP2_VERIFY_URL_NOT_FOUND: 'VERIFY_NOT_FOUND',
	IP2_VERIFY_URL_MALFORMED: 'VERIFY_MALFORMED'
})

const OPENAI_KIRO_BUSINESS_OPTIONS = Object.freeze([
	Object.freeze({ value: 'UNREGISTERED', label: '未注册', tone: 'neutral' }),
	Object.freeze({ value: 'REGISTERED', label: '已注册', tone: 'success' }),
	Object.freeze({ value: 'RESTRICTED', label: '限制/封禁证据', tone: 'warning' })
])

const IP2_REGISTRATION_BUSINESS_OPTIONS = Object.freeze([
	Object.freeze({ value: 'UNREGISTERED', label: '未注册', tone: 'neutral' }),
	Object.freeze({ value: 'REGISTERED', label: '已注册', tone: 'success' })
])

const IP2_VERIFY_BUSINESS_OPTIONS = Object.freeze([
	Object.freeze({ value: 'VERIFY_FOUND', label: '已找到验证链接', tone: 'success' }),
	Object.freeze({ value: 'VERIFY_NOT_FOUND', label: '未找到验证链接', tone: 'neutral' }),
	Object.freeze({ value: 'VERIFY_MALFORMED', label: '链接格式异常', tone: 'warning' })
])

const BUSINESS_OPTIONS_BY_INSPECTION_TYPE = Object.freeze({
	OPENAI_STATUS: OPENAI_KIRO_BUSINESS_OPTIONS,
	KIRO_STATUS: OPENAI_KIRO_BUSINESS_OPTIONS,
	IP2LOCATION_REGISTRATION: IP2_REGISTRATION_BUSINESS_OPTIONS,
	IP2LOCATION_VERIFY_LINK: IP2_VERIFY_BUSINESS_OPTIONS
})

const UNREGISTERED_STATUSES_BY_INSPECTION_TYPE = Object.freeze({
	OPENAI_STATUS: new Set(['OPENAI_NO_REGISTRATION_EVIDENCE']),
	KIRO_STATUS: new Set(['KIRO_NO_REGISTRATION_EVIDENCE']),
	IP2LOCATION_REGISTRATION: new Set(['IP2_REGISTRATION_MAIL_NOT_FOUND']),
	IP2LOCATION_VERIFY_LINK: new Set()
})

const INPUT_STATUSES = new Set([
	'INVALID_CREDENTIAL_FORMAT',
	'INVALID_EMAIL',
	'INVALID_PASSWORD_FIELD',
	'INVALID_CLIENT_ID',
	'INVALID_REFRESH_TOKEN',
	'DUPLICATE_EMAIL'
])

const AUTH_STATUSES = new Set([
	'REFRESH_TOKEN_EXPIRED',
	'REFRESH_TOKEN_REVOKED',
	'OAUTH_AUTHORIZATION_FAILED',
	'OAUTH_CLIENT_INVALID',
	'OAUTH_CLIENT_TOKEN_MISMATCH',
	'OAUTH_CONSENT_REQUIRED',
	'MICROSOFT_ACCOUNT_RESTRICTED',
	'OAUTH_RATE_LIMIT_EXHAUSTED',
	'OAUTH_TRANSIENT_EXHAUSTED',
	'OAUTH_NETWORK_EXHAUSTED',
	'OAUTH_RESPONSE_INVALID',
	'IMAP_AUTHENTICATION_FAILED',
	'IMAP_ACCESS_DENIED',
	'IMAP_MAILBOX_UNAVAILABLE',
	'IMAP_NETWORK_EXHAUSTED',
	'IMAP_TRANSIENT_EXHAUSTED',
	'IMAP_SCAN_TIMEOUT'
])

const LABELS = Object.freeze({
	INVALID_CREDENTIAL_FORMAT: '凭证格式无效',
	INVALID_EMAIL: '邮箱格式无效',
	INVALID_PASSWORD_FIELD: '密码字段无效',
	INVALID_CLIENT_ID: 'Client ID 无效',
	INVALID_REFRESH_TOKEN: 'Refresh Token 格式无效',
	DUPLICATE_EMAIL: '邮箱重复',
	REFRESH_TOKEN_EXPIRED: 'Refresh Token 已过期',
	REFRESH_TOKEN_REVOKED: 'Refresh Token 已撤销',
	OAUTH_AUTHORIZATION_FAILED: 'Microsoft 授权失败',
	OAUTH_CLIENT_INVALID: 'OAuth Client 无效',
	OAUTH_CLIENT_TOKEN_MISMATCH: 'Client 与 Token 不匹配',
	OAUTH_CONSENT_REQUIRED: '缺少 IMAP 授权同意',
	MICROSOFT_ACCOUNT_RESTRICTED: 'Microsoft 账号受限',
	OAUTH_RATE_LIMIT_EXHAUSTED: 'OAuth 限流重试耗尽',
	OAUTH_TRANSIENT_EXHAUSTED: 'OAuth 临时错误重试耗尽',
	OAUTH_NETWORK_EXHAUSTED: 'OAuth 网络重试耗尽',
	OAUTH_RESPONSE_INVALID: 'OAuth 响应无效',
	IMAP_AUTHENTICATION_FAILED: 'IMAP 认证失败',
	IMAP_ACCESS_DENIED: 'IMAP 访问被拒绝',
	IMAP_MAILBOX_UNAVAILABLE: '邮箱不可访问',
	IMAP_NETWORK_EXHAUSTED: 'IMAP 网络重试耗尽',
	IMAP_TRANSIENT_EXHAUSTED: 'IMAP 临时错误重试耗尽',
	IMAP_SCAN_TIMEOUT: '邮箱扫描超时',
	OPENAI_NO_REGISTRATION_EVIDENCE: '未找到 OpenAI 注册证据',
	OPENAI_REGISTERED_NORMAL: 'OpenAI 正常证据',
	OPENAI_RESTRICTED_EVIDENCE_FOUND: 'OpenAI 限制证据',
	OPENAI_UNCLASSIFIED: 'OpenAI 邮件无法分类',
	KIRO_NO_REGISTRATION_EVIDENCE: '未找到 Kiro 注册证据',
	KIRO_REGISTERED_NORMAL: 'Kiro 正常证据',
	KIRO_RESTRICTED_EVIDENCE_FOUND: 'Kiro 限制证据',
	KIRO_UNCLASSIFIED: 'Kiro 邮件无法分类',
	IP2_REGISTRATION_MAIL_FOUND: '已找到 IP2Location 注册邮件',
	IP2_REGISTRATION_MAIL_NOT_FOUND: '未找到 IP2Location 注册邮件',
	IP2_VERIFY_URL_FOUND: '已提取验证链接',
	IP2_VERIFY_URL_NOT_FOUND: '未找到验证链接',
	IP2_VERIFY_URL_MALFORMED: '验证链接格式异常',
	RABBIT_DISPATCH_EXHAUSTED: 'RabbitMQ 发布重试耗尽',
	INTERNAL_PROCESSING_FAILURE: '内部处理失败'
})

export function mailInspectionBusinessCategory(status) {
	return BUSINESS_CATEGORY_BY_STATUS[String(status || '')] || null
}

export function mailInspectionBusinessCategoryOptions(inspectionType) {
	return [...(BUSINESS_OPTIONS_BY_INSPECTION_TYPE[String(inspectionType || '')] || [])]
}

export function mailInspectionResultGroupOptions(inspectionType) {
	const [all, ...technicalGroups] = MAIL_INSPECTION_RESULT_GROUPS
	return [all, ...mailInspectionBusinessCategoryOptions(inspectionType), ...technicalGroups]
}

function resultGroup(result) {
	if (result?.retryable === true && result.retryExhausted === true) return 'RETRY_EXHAUSTED'
	if (INPUT_STATUSES.has(result?.status)) return 'INPUT_ERROR'
	if (AUTH_STATUSES.has(result?.status)) return 'AUTH_ERROR'
	if (result?.status === 'INTERNAL_PROCESSING_FAILURE') return 'INTERNAL_ERROR'
	return 'BUSINESS'
}

function resultTone(group, status) {
	if (group === 'RETRY_EXHAUSTED') return 'warning'
	if (group === 'AUTH_ERROR' || group === 'INPUT_ERROR' || group === 'INTERNAL_ERROR') return 'danger'
	if (/RESTRICTED|MALFORMED|UNCLASSIFIED/u.test(status || '')) return 'warning'
	if (/NO_REGISTRATION_EVIDENCE|NOT_FOUND/u.test(status || '')) return 'neutral'
	if (/NORMAL|FOUND/u.test(status || '')) return 'success'
	return 'neutral'
}

function evidenceSummary(result) {
	if (result?.evidencePhrase) return result.evidencePhrase
	if (result?.subject) return result.subject
	if (result?.mailFound) return '已找到候选邮件，详情中提供最小证据。'
	if (/NO_REGISTRATION_EVIDENCE|MAIL_NOT_FOUND|URL_NOT_FOUND/u.test(result?.status || '')) {
		return '邮箱扫描已完成，但没有找到对应证据。'
	}
	if (result?.failureStage) return `失败阶段：${result.failureStage}`
	return '没有更多证据字段。'
}

export function presentMailInspectionResult(result) {
	const group = resultGroup(result)
	const businessCategory = group === 'BUSINESS'
		? mailInspectionBusinessCategory(result?.status)
		: null
	return Object.freeze({
		...result,
		group,
		businessCategory,
		groupLabel: MAIL_INSPECTION_RESULT_GROUPS.find(item => item.value === group)?.label
			|| businessCategory
			|| group,
		label: LABELS[result?.status] || result?.status || '未知结果',
		tone: resultTone(group, result?.status),
		evidenceSummary: evidenceSummary(result),
		attemptLabel: `OAuth ${Number(result?.oauthAttempts) || 0} 次 · IMAP ${Number(result?.imapAttempts) || 0} 次`
	})
}

export function presentMailInspectionResults(results) {
	return [...(Array.isArray(results) ? results : [])]
		.sort((left, right) => left.lineNumber - right.lineNumber)
		.map(presentMailInspectionResult)
}

export function countMailInspectionGroups(results) {
	const counts = { ALL: 0 }
	for (const result of presentMailInspectionResults(results)) {
		counts.ALL += 1
		counts[result.group] = (counts[result.group] || 0) + 1
		if (result.businessCategory) {
			counts[result.businessCategory] = (counts[result.businessCategory] || 0) + 1
		}
	}
	return counts
}

export function recoverRetryCredentialLines(results, credentialLines) {
	const lines = Array.isArray(credentialLines) ? credentialLines : []
	return [...(Array.isArray(results) ? results : [])]
		.filter(result => result?.retryable === true && result.retryExhausted === true)
		.sort((left, right) => left.lineNumber - right.lineNumber)
		.map(result => lines[result.lineNumber - 1])
		.filter(line => typeof line === 'string' && line.length > 0)
}

export function recoverUnregisteredCredentialLines(inspectionType, results, credentialLines) {
	const allowedStatuses = UNREGISTERED_STATUSES_BY_INSPECTION_TYPE[
		String(inspectionType || '')]
	if (!allowedStatuses?.size) return []

	const lines = Array.isArray(credentialLines) ? credentialLines : []
	const recoveredLineNumbers = new Set()
	const recovered = []
	for (const result of [...(Array.isArray(results) ? results : [])]
		.filter(result => allowedStatuses.has(result?.status))
		.sort((left, right) => Number(left?.lineNumber) - Number(right?.lineNumber))) {
		const lineNumber = Number(result?.lineNumber)
		if (!Number.isInteger(lineNumber)
			|| lineNumber < 1
			|| recoveredLineNumbers.has(lineNumber)) continue
		recoveredLineNumbers.add(lineNumber)
		const line = lines[lineNumber - 1]
		if (typeof line === 'string' && line.length > 0) recovered.push(line)
	}
	return recovered
}

export function maskMailInspectionEmail(value) {
	const email = String(value || '')
	const separator = email.lastIndexOf('@')
	if (separator <= 0) return email ? `${email.slice(0, 1)}***` : '无有效邮箱'
	const local = email.slice(0, separator)
	const domain = email.slice(separator)
	return `${local.slice(0, 1)}***${domain}`
}
