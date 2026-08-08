const MAX_FAVICON_HOSTNAME_LENGTH = 253

function isIpv4Literal(value) {
	const parts = String(value || '').split('.')
	return parts.length === 4 && parts.every(part => /^\d{1,3}$/.test(part)
		&& Number(part) >= 0 && Number(part) <= 255)
}

export function normalizeAiSourceFaviconDomain(value) {
	const raw = typeof value === 'string' ? value.trim() : ''
	if (!raw || raw.length > MAX_FAVICON_HOSTNAME_LENGTH + 1
		|| /[\u0000-\u001f\u007f]/.test(raw)
		|| /[:\/@\\]/.test(raw)) return ''
	try {
		const parsed = new URL(`https://${raw}`)
		const hostname = parsed.hostname.toLowerCase().replace(/\.$/, '')
		if (!hostname || hostname.length > MAX_FAVICON_HOSTNAME_LENGTH
			|| hostname === 'localhost' || isIpv4Literal(hostname)
			|| /^\d+$/.test(hostname)) return ''
		const labels = hostname.split('.')
		if (labels.some(label => !label || label.length > 63
			|| !/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i.test(label))) return ''
		return hostname
	} catch (_) {
		return ''
	}
}

export function buildAiSourceFaviconUrl(value) {
	const hostname = normalizeAiSourceFaviconDomain(value)
	return hostname
		? `https://www.google.com/s2/favicons?domain=${encodeURIComponent(`https://${hostname}`)}&sz=128`
		: ''
}
