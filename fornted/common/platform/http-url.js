const MAX_HTTP_URL_LENGTH = 4096
const HTTP_URL_PATTERN = /^(https?):\/\/([^/?#]+)(\/[^?#]*)?(\?[^#]*)?(#.*)?$/i
const DNS_LABEL_PATTERN = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i

function validPercentEncoding(value) {
	return !/%(?![0-9a-f]{2})/i.test(value)
}

function normalizedIpv4(value) {
	const parts = value.split('.')
	if (parts.length !== 4 || parts.some(part => !/^\d{1,3}$/.test(part))) return ''
	const normalized = parts.map(part => String(Number(part)))
	return normalized.every((part, index) => Number(part) <= 255
		&& String(Number(parts[index])) === part) ? normalized.join('.') : ''
}

function normalizedHostname(value) {
	const raw = String(value || '').toLowerCase().replace(/\.$/, '')
	if (!raw || raw.length > 253) return ''
	if (raw.startsWith('[') && raw.endsWith(']')) {
		const literal = raw.slice(1, -1)
		return literal && /^[0-9a-f:.]+$/i.test(literal) ? `[${literal}]` : ''
	}
	if (raw.includes(':')) return ''
	if (/^[\d.]+$/.test(raw)) return normalizedIpv4(raw)
	const labels = raw.split('.')
	return labels.every(label => label.length <= 63 && DNS_LABEL_PATTERN.test(label))
		? raw : ''
}

function normalizedAuthority(value, protocol) {
	const raw = String(value || '')
	if (!raw || raw.includes('@')) return null
	let hostname = ''
	let port = ''
	if (raw.startsWith('[')) {
		const closing = raw.indexOf(']')
		if (closing < 0) return null
		hostname = normalizedHostname(raw.slice(0, closing + 1))
		const suffix = raw.slice(closing + 1)
		if (suffix && !/^:\d+$/.test(suffix)) return null
		port = suffix ? suffix.slice(1) : ''
	} else {
		const separator = raw.lastIndexOf(':')
		if (separator >= 0) {
			if (raw.indexOf(':') !== separator) return null
			hostname = normalizedHostname(raw.slice(0, separator))
			port = raw.slice(separator + 1)
		} else {
			hostname = normalizedHostname(raw)
		}
	}
	if (!hostname || (port && !/^\d+$/.test(port))) return null
	if (port) {
		const numericPort = Number(port)
		if (!Number.isSafeInteger(numericPort) || numericPort < 1 || numericPort > 65535) {
			return null
		}
		port = String(numericPort)
		if ((protocol === 'https:' && port === '443')
			|| (protocol === 'http:' && port === '80')) port = ''
	}
	return { hostname, port }
}

function normalizedPathname(value) {
	const raw = value || '/'
	const segments = raw.split('/')
	const normalized = []
	for (const segment of segments) {
		if (segment === '.') continue
		if (segment === '..') {
			if (normalized.length > 1) normalized.pop()
			continue
		}
		normalized.push(segment)
	}
	const pathname = normalized.join('/')
	return pathname.startsWith('/') ? pathname || '/' : `/${pathname}`
}

/**
 * 解析聊天内容中的绝对 HTTP 地址，并在所有前端运行时产生相同的规范化结果。
 */
export function parseAbsoluteHttpUrl(value) {
	const raw = typeof value === 'string' ? value.trim() : ''
	if (!raw || raw.length > MAX_HTTP_URL_LENGTH
		|| /[\u0000-\u0020\u007f]/.test(raw)
		|| raw.includes('\\')
		|| !validPercentEncoding(raw)) return null
	const match = raw.match(HTTP_URL_PATTERN)
	if (!match) return null
	const protocol = `${match[1].toLowerCase()}:`
	const authority = normalizedAuthority(match[2], protocol)
	if (!authority) return null
	const pathname = normalizedPathname(match[3] || '/')
	const search = match[4] || ''
	const hash = match[5] || ''
	const host = authority.port
		? `${authority.hostname}:${authority.port}` : authority.hostname
	const origin = `${protocol}//${host}`
	return {
		href: `${origin}${pathname}${search}${hash}`,
		protocol,
		hostname: authority.hostname,
		port: authority.port,
		origin,
		pathname,
		search,
		hash
	}
}

/**
 * 为来源去重和匹配生成稳定 URL；调用方可选择忽略不会改变资源身份的 fragment。
 */
export function canonicalHttpUrl(value, { stripFragment = false } = {}) {
	const parsed = parseAbsoluteHttpUrl(value)
	if (!parsed) return ''
	return stripFragment
		? `${parsed.origin}${parsed.pathname}${parsed.search}`
		: parsed.href
}
