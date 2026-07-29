const DELIMITER = '----'
const MAX_REQUEST_BYTES = 1024 * 1024
const MAX_LINE_CHARS = 12288
const MAX_EMAIL_CHARS = 320
const MAX_PASSWORD_CHARS = 512
const MAX_REFRESH_TOKEN_CHARS = 8192
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/u

function utf8Length(value) {
	let bytes = 0
	for (const character of String(value || '')) {
		const code = character.codePointAt(0)
		if (code <= 0x7f) bytes += 1
		else if (code <= 0x7ff) bytes += 2
		else if (code <= 0xffff) bytes += 3
		else bytes += 4
	}
	return bytes
}

function containsControlCharacter(value) {
	return /[\u0000-\u001f\u007f-\u009f]/u.test(value)
}

function lineError(lineNumber, code, message) {
	return { lineNumber, code, message }
}

function validateLine(line, lineNumber, seenEmails) {
	if (line.length > MAX_LINE_CHARS) {
		return lineError(lineNumber, 'INVALID_CREDENTIAL_FORMAT', `第 ${lineNumber} 行超过 12288 个字符。`)
	}
	const fields = line.split(DELIMITER)
	if (fields.length !== 4) {
		return lineError(lineNumber, 'INVALID_CREDENTIAL_FORMAT', `第 ${lineNumber} 行必须恰好包含四段凭证。`)
	}
	const email = fields[0].trim().toLowerCase()
	const password = fields[1]
	const clientId = fields[2].trim().toLowerCase()
	const refreshToken = fields[3].trim()
	if (!email
		|| email.length > MAX_EMAIL_CHARS
		|| containsControlCharacter(email)
		|| !EMAIL_PATTERN.test(email)) {
		return lineError(lineNumber, 'INVALID_EMAIL', `第 ${lineNumber} 行邮箱格式无效。`)
	}
	if (!password.trim() || password.length > MAX_PASSWORD_CHARS) {
		return lineError(lineNumber, 'INVALID_PASSWORD_FIELD', `第 ${lineNumber} 行密码字段为空或过长。`)
	}
	if (!UUID_PATTERN.test(clientId)) {
		return lineError(lineNumber, 'INVALID_CLIENT_ID', `第 ${lineNumber} 行 clientId 不是规范 UUID。`)
	}
	if (!refreshToken
		|| refreshToken.length > MAX_REFRESH_TOKEN_CHARS
		|| containsControlCharacter(refreshToken)) {
		return lineError(lineNumber, 'INVALID_REFRESH_TOKEN', `第 ${lineNumber} 行 refresh token 格式无效。`)
	}
	if (seenEmails.has(email)) {
		return lineError(lineNumber, 'DUPLICATE_EMAIL', `第 ${lineNumber} 行邮箱与前面的输入重复。`)
	}
	seenEmails.add(email)
	return null
}

export function normalizeCredentialLines(text) {
	return String(text || '')
		.replace(/\r\n?/gu, '\n')
		.split('\n')
		.filter(line => line.trim().length > 0)
}

export function analyzeMailboxCredentialText(text) {
	const credentialLines = normalizeCredentialLines(text)
	const byteCount = credentialLines.reduce((total, line) => total + utf8Length(line), 0)
	const errors = []
	if (credentialLines.length < 1) {
		errors.push(lineError(
			0,
			'CREDENTIAL_LINES_EMPTY',
			'至少需要输入一行邮箱凭证。'))
	}
	if (byteCount > MAX_REQUEST_BYTES) {
		errors.push(lineError(0, 'REQUEST_TOO_LARGE', '邮箱凭证请求总量不能超过 1 MiB。'))
	}
	const seenEmails = new Set()
	credentialLines.forEach((line, index) => {
		const error = validateLine(line, index + 1, seenEmails)
		if (error) errors.push(error)
	})
	return Object.freeze({
		valid: errors.length === 0,
		credentialLines: Object.freeze([...credentialLines]),
		errors: Object.freeze(errors),
		lineCount: credentialLines.length,
		byteCount
	})
}

export function formatCredentialByteCount(bytes) {
	const value = Number(bytes) || 0
	if (value < 1024) return `${value} B`
	return `${(value / 1024).toFixed(value < 10240 ? 1 : 0)} KiB`
}
