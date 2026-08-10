export const AndroidEdgeChallengeError = Object.freeze({
	CHALLENGE: 'EDGE_CHALLENGE',
	CANCELLED: 'EDGE_CHALLENGE_CANCELLED',
	TIMEOUT: 'EDGE_CHALLENGE_TIMEOUT',
	NOT_SHARED: 'EDGE_CLEARANCE_NOT_SHARED',
	REPEATED: 'EDGE_CHALLENGE_REPEATED'
})

export function extractAndroidClearanceCookie(cookieHeader) {
	const header = typeof cookieHeader === 'string' ? cookieHeader : ''
	for (const item of header.split(';')) {
		const separator = item.indexOf('=')
		if (separator < 0) continue
		const name = item.slice(0, separator).trim()
		const value = item.slice(separator + 1).trim()
		if (name !== 'cf_clearance') continue
		if (!value || value.length > 4096 || /[\u0000-\u0020\u007f;]/.test(value)) {
			return ''
		}
		return `cf_clearance=${value}`
	}
	return ''
}

export function isAndroidEdgeChallenge(error) {
	return error?.code === AndroidEdgeChallengeError.CHALLENGE
}

export async function executeWithAndroidEdgeChallengeRecovery(
	executeRequest,
	ensureClearance
) {
	try {
		return await executeRequest()
	} catch (error) {
		if (!isAndroidEdgeChallenge(error)) throw error
		await ensureClearance()
	}

	try {
		return await executeRequest()
	} catch (error) {
		if (!isAndroidEdgeChallenge(error)) throw error
		throw repeatedAndroidEdgeChallengeError(error)
	}
}

export function repeatedAndroidEdgeChallengeError(error) {
	const repeated = edgeChallengeError(
		AndroidEdgeChallengeError.REPEATED,
		'Cloudflare 安全验证仍未通过，请稍后重试。')
	repeated.statusCode = Number(error?.statusCode || 0)
	repeated.cfRay = String(error?.cfRay || '')
	return repeated
}

export function edgeChallengeError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}
