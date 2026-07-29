export const IP2LOCATION_KEY_LIMIT = 100
export const IP2LOCATION_KEY_PAGE_SIZE = 20
export const IP2LOCATION_EXPIRING_WINDOW_MS = 24 * 60 * 60 * 1000

export function parseIp2LocationKeyText(rawText) {
	const seen = new Set()
	const apiKeys = []
	let duplicateCount = 0
	let nonEmptyCount = 0
	String(rawText || '').split(/\r?\n/u).forEach(line => {
		const value = line.trim()
		if (!value) return
		nonEmptyCount += 1
		if (seen.has(value)) {
			duplicateCount += 1
			return
		}
		seen.add(value)
		apiKeys.push(value)
	})
	return { apiKeys, duplicateCount, nonEmptyCount }
}

export function validateIp2LocationImportCapacity(currentCount, candidateCount) {
	const safeCurrentCount = Math.max(0, Number(currentCount) || 0)
	const safeCandidateCount = Math.max(0, Number(candidateCount) || 0)
	const remainingCapacity = Math.max(0, IP2LOCATION_KEY_LIMIT - safeCurrentCount)
	return {
		allowed: safeCandidateCount > 0 && safeCandidateCount <= remainingCapacity,
		remainingCapacity,
		candidateCount: safeCandidateCount,
		overflowCount: Math.max(0, safeCandidateCount - remainingCapacity)
	}
}

export function compareIp2LocationKeys(left, right) {
	const leftExpiry = Date.parse(left?.expiresAt)
	const rightExpiry = Date.parse(right?.expiresAt)
	const safeLeftExpiry = Number.isFinite(leftExpiry) ? leftExpiry : Number.POSITIVE_INFINITY
	const safeRightExpiry = Number.isFinite(rightExpiry) ? rightExpiry : Number.POSITIVE_INFINITY
	if (safeLeftExpiry !== safeRightExpiry) return safeLeftExpiry < safeRightExpiry ? -1 : 1
	const leftId = String(left?.keyId || '')
	const rightId = String(right?.keyId || '')
	if (leftId === rightId) return 0
	return leftId < rightId ? -1 : 1
}

export function sortIp2LocationKeys(entries) {
	return [...(Array.isArray(entries) ? entries : [])].sort(compareIp2LocationKeys)
}

export function paginateIp2LocationKeys(entries, requestedPage) {
	const safeEntries = Array.isArray(entries) ? entries : []
	const pageCount = Math.max(1, Math.min(5, Math.ceil(safeEntries.length / IP2LOCATION_KEY_PAGE_SIZE)))
	const page = Math.min(pageCount, Math.max(1, Number(requestedPage) || 1))
	const start = (page - 1) * IP2LOCATION_KEY_PAGE_SIZE
	return {
		page,
		pageCount,
		pageSize: IP2LOCATION_KEY_PAGE_SIZE,
		items: safeEntries.slice(start, start + IP2LOCATION_KEY_PAGE_SIZE)
	}
}

export function ip2LocationKeyStatus(entry, now = Date.now()) {
	const expiresAt = Date.parse(entry?.expiresAt)
	if (!Number.isFinite(expiresAt)) return { code: 'INVALID', label: '时间无效' }
	if (expiresAt <= now) return { code: 'EXPIRED', label: '已过期' }
	if ((Number(entry?.remainingQuota) || 0) <= 0) return { code: 'EXHAUSTED', label: '额度已耗尽' }
	if (expiresAt - now <= IP2LOCATION_EXPIRING_WINDOW_MS) return { code: 'EXPIRING', label: '即将过期' }
	return { code: 'ACTIVE', label: '有效' }
}

export function relativeExpiryLabel(expiresAt, now = Date.now()) {
	const timestamp = Date.parse(expiresAt)
	if (!Number.isFinite(timestamp)) return '时间无效'
	const remaining = timestamp - now
	if (remaining <= 0) return '已过期'
	const minutes = Math.max(1, Math.ceil(remaining / 60_000))
	if (minutes < 60) return `${minutes} 分钟后`
	const hours = Math.ceil(minutes / 60)
	if (hours < 24) return `${hours} 小时后`
	return `${Math.ceil(hours / 24)} 天后`
}

export function exactExpiryLabel(expiresAt) {
	const timestamp = Date.parse(expiresAt)
	if (!Number.isFinite(timestamp)) return '无效时间'
	return new Date(timestamp).toLocaleString()
}

export function presentIp2LocationKey(entry, now = Date.now()) {
	return {
		...entry,
		status: ip2LocationKeyStatus(entry, now),
		relativeExpiry: relativeExpiryLabel(entry?.expiresAt, now),
		exactExpiry: exactExpiryLabel(entry?.expiresAt)
	}
}

export function summarizeIp2LocationKeys(entries, now = Date.now()) {
	return (Array.isArray(entries) ? entries : []).reduce((summary, entry) => {
		const code = ip2LocationKeyStatus(entry, now).code
		if (code === 'ACTIVE') summary.active += 1
		if (code === 'EXPIRING') summary.expiring += 1
		if (code === 'EXHAUSTED') summary.exhausted += 1
		if (code === 'EXPIRED' || code === 'INVALID') summary.unavailable += 1
		return summary
	}, { active: 0, expiring: 0, exhausted: 0, unavailable: 0 })
}
