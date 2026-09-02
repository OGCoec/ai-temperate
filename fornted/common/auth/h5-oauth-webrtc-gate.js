const STORAGE_KEY = 'ait:auth:h5-oauth-webrtc-gate:v1'
const GATE_TTL_MS = 15 * 60 * 1000
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const GENERATION_PATTERN = /^[1-9][0-9]{0,18}$/

export const H5OAuthWebRtcGatePhase = Object.freeze({
	PREPARED: 'PREPARED',
	OAUTH_SUSPENDED: 'OAUTH_SUSPENDED',
	RESUMED: 'RESUMED',
	PENDING_VERDICT: 'PENDING_VERDICT',
	VERIFIED: 'VERIFIED',
	FAILED: 'FAILED'
})

const KNOWN_PHASES = new Set(Object.values(H5OAuthWebRtcGatePhase))
const OWNED_PHASES = new Set([
	H5OAuthWebRtcGatePhase.PREPARED,
	H5OAuthWebRtcGatePhase.OAUTH_SUSPENDED,
	H5OAuthWebRtcGatePhase.RESUMED,
	H5OAuthWebRtcGatePhase.PENDING_VERDICT
])

let memoryGate = null

function storage() {
	try {
		return globalThis.sessionStorage || null
	} catch (_) {
		return null
	}
}

function uuid(value) {
	const normalized = String(value || '').trim().toLowerCase()
	return UUID_PATTERN.test(normalized) ? normalized : ''
}

function generation(value) {
	const normalized = String(value || '').trim()
	return GENERATION_PATTERN.test(normalized) ? normalized : ''
}

function normalizeGate(candidate) {
	if (!candidate || typeof candidate !== 'object') return null
	const phase = String(candidate.phase || '')
	const startedAt = Number(candidate.startedAt)
	const flowId = uuid(candidate.flowId)
	const probeRunId = uuid(candidate.probeRunId)
	const attemptId = uuid(candidate.attemptId)
	const probeGeneration = generation(candidate.generation || candidate.probeGeneration)
	if (!KNOWN_PHASES.has(phase)
		|| !flowId
		|| !probeRunId
		|| !probeGeneration
		|| !Number.isFinite(startedAt)
		|| startedAt <= 0
		|| startedAt > Date.now() + 60_000
		|| Date.now() - startedAt > GATE_TTL_MS) return null
	if ([
		H5OAuthWebRtcGatePhase.RESUMED,
		H5OAuthWebRtcGatePhase.PENDING_VERDICT,
		H5OAuthWebRtcGatePhase.FAILED
	].includes(phase) && !attemptId) return null
	const verdictDeadlineAt = String(candidate.verdictDeadlineAt || '')
	if (verdictDeadlineAt && !Number.isFinite(Date.parse(verdictDeadlineAt))) return null
	return {
		flowId,
		probeRunId,
		attemptId,
		generation: probeGeneration,
		startedAt,
		fallbackUsed: candidate.fallbackUsed === true,
		verdictDeadlineAt,
		phase,
		stunUrls: Array.isArray(candidate.stunUrls)
			? candidate.stunUrls.map(value => String(value || '')).filter(Boolean)
			: [],
		timeoutMillis: Math.max(0, Number(candidate.timeoutMillis) || 0),
		reportGraceMillis: Math.max(0, Number(candidate.reportGraceMillis) || 0),
		reportPath: candidate.reportPath === '/api/_edge/webrtc/report'
			? candidate.reportPath
			: '/api/_edge/webrtc/report'
	}
}

function cloneGate(gate) {
	return gate ? { ...gate, stunUrls: [...gate.stunUrls] } : null
}

export function readH5OAuthWebRtcGate() {
	const target = storage()
	if (!target) return cloneGate(memoryGate)
	try {
		const raw = target.getItem(STORAGE_KEY)
		if (!raw) {
			memoryGate = null
			return null
		}
		const gate = normalizeGate(JSON.parse(raw))
		if (!gate) {
			target.removeItem(STORAGE_KEY)
			memoryGate = null
			return null
		}
		memoryGate = gate
		return cloneGate(gate)
	} catch (_) {
		try { target.removeItem(STORAGE_KEY) } catch (ignored) { }
		memoryGate = null
		return null
	}
}

export function writeH5OAuthWebRtcGate(candidate) {
	const gate = normalizeGate(candidate)
	if (!gate) return null
	memoryGate = gate
	try { storage()?.setItem(STORAGE_KEY, JSON.stringify(gate)) } catch (_) { }
	return cloneGate(gate)
}

export function clearH5OAuthWebRtcGate() {
	memoryGate = null
	try { storage()?.removeItem(STORAGE_KEY) } catch (_) { }
}

export function ownsH5WebRtcScheduling() {
	const gate = readH5OAuthWebRtcGate()
	return !!gate && OWNED_PHASES.has(gate.phase)
}

export function hasPendingH5OAuthWebRtcVerdict() {
	return readH5OAuthWebRtcGate()?.phase === H5OAuthWebRtcGatePhase.PENDING_VERDICT
}
