export const RISK_CHALLENGE_PHASE = Object.freeze({
	NAVIGATING: 'NAVIGATING',
	RETURNED: 'RETURNED',
	RECHECKING: 'RECHECKING',
	FAILED: 'FAILED'
})

export const RISK_CHALLENGE_FAILURE_REASON = Object.freeze({
	EXHAUSTED: 'EXHAUSTED',
	RECHECK_ERROR: 'RECHECK_ERROR',
	FLOW_EXPIRED: 'FLOW_EXPIRED'
})

export const RISK_CHALLENGE_ACTION = Object.freeze({
	IGNORE: 'IGNORE',
	NAVIGATE: 'NAVIGATE',
	SUPPRESS: 'SUPPRESS',
	RETURN: 'RETURN',
	FAILURE: 'FAILURE'
})

const STATE_VERSION = 2
const MAX_ATTEMPTS = 3
const FLOW_TTL_MILLIS = 20 * 60 * 1000
const PHASES = new Set(Object.values(RISK_CHALLENGE_PHASE))
const FAILURE_REASONS = new Set(Object.values(RISK_CHALLENGE_FAILURE_REASON))
const STATE_FIELDS = new Set([
	'version',
	'phase',
	'attempt',
	'flowStartedAt',
	'attemptExpiresAt',
	'returnPath',
	'failureReason'
])

export function createRiskChallengeFlow({
	storage,
	storageKey,
	now = () => Date.now(),
	allowedReturnPrefixes = ['/pages/'],
	defaultReturnPath,
	maxAttempts = MAX_ATTEMPTS,
	flowTtlMillis = FLOW_TTL_MILLIS
}) {
	if (!storage || typeof storage.getItem !== 'function'
		|| typeof storage.setItem !== 'function'
		|| typeof storage.removeItem !== 'function') {
		throw new TypeError('Risk challenge storage is unavailable.')
	}
	if (!storageKey || !Number.isInteger(maxAttempts) || maxAttempts < 1
		|| !Number.isFinite(flowTtlMillis) || flowTtlMillis <= 0) {
		throw new TypeError('Risk challenge flow configuration is invalid.')
	}
	const safeDefaultReturnPath = safeReturnPath(
		defaultReturnPath,
		allowedReturnPrefixes,
		''
	)
	if (!safeDefaultReturnPath) {
		throw new TypeError('Risk challenge default return path is invalid.')
	}

	const read = () => readState({
		storage,
		storageKey,
		now: now(),
		flowTtlMillis,
		allowedReturnPrefixes
	})
	const save = state => {
		storage.setItem(storageKey, JSON.stringify(state))
		return state
	}
	const clear = () => storage.removeItem(storageKey)
	const fail = (previous, reason, fallbackRoute = safeDefaultReturnPath) => save(failedState(
		previous,
		reason,
		now(),
		safeReturnPath(
			previous?.returnPath || fallbackRoute,
			allowedReturnPrefixes,
			safeDefaultReturnPath
		)
	))

	return {
		handleChallengeRequired(error, returnPath, allowedChallengePath) {
			if (error?.code !== 'RISK_CHALLENGE_REQUIRED') {
				return { handled: false, action: RISK_CHALLENGE_ACTION.IGNORE }
			}
			const loaded = read()
			if (loaded.status === 'CORRUPT') {
				clear()
				const state = fail(null, RISK_CHALLENGE_FAILURE_REASON.RECHECK_ERROR, returnPath)
				return failureResult(state)
			}
			if (loaded.status === 'EXPIRED') {
				const state = fail(
					loaded.state,
					RISK_CHALLENGE_FAILURE_REASON.FLOW_EXPIRED,
					returnPath
				)
				return failureResult(state)
			}
			const current = loaded.state
			if (current?.phase === RISK_CHALLENGE_PHASE.FAILED) {
				return failureResult(current)
			}
			if (current?.phase === RISK_CHALLENGE_PHASE.NAVIGATING
				|| current?.phase === RISK_CHALLENGE_PHASE.RETURNED) {
				return {
					handled: true,
					action: RISK_CHALLENGE_ACTION.SUPPRESS,
					state: current
				}
			}
			if (current?.phase === RISK_CHALLENGE_PHASE.RECHECKING
				&& current.attempt >= maxAttempts) {
				const state = fail(
					current,
					RISK_CHALLENGE_FAILURE_REASON.EXHAUSTED,
					returnPath
				)
				return failureResult(state)
			}

			const challenge = validateChallenge(error, allowedChallengePath, now())
			if (!challenge) {
				const state = fail(
					current,
					RISK_CHALLENGE_FAILURE_REASON.RECHECK_ERROR,
					returnPath
				)
				return failureResult(state)
			}
			const startedAt = current?.flowStartedAt || now()
			const flowExpiresAt = startedAt + flowTtlMillis
			if (flowExpiresAt <= now()) {
				const state = fail(
					current,
					RISK_CHALLENGE_FAILURE_REASON.FLOW_EXPIRED,
					returnPath
				)
				return failureResult(state)
			}
			const state = save({
				version: STATE_VERSION,
				phase: RISK_CHALLENGE_PHASE.NAVIGATING,
				attempt: current ? current.attempt + 1 : 1,
				flowStartedAt: startedAt,
				attemptExpiresAt: Math.min(challenge.expiresAt, flowExpiresAt),
				returnPath: safeReturnPath(
					current?.returnPath || returnPath,
					allowedReturnPrefixes,
					safeDefaultReturnPath
				),
				failureReason: null
			})
			return {
				handled: true,
				action: RISK_CHALLENGE_ACTION.NAVIGATE,
				challengeUrl: `${challenge.path}?ref=${encodeURIComponent(challenge.reference)}`,
				state
			}
		},

		markReturned() {
			const loaded = read()
			if (loaded.status === 'EXPIRED') {
				return failureResult(fail(
					loaded.state,
					RISK_CHALLENGE_FAILURE_REASON.FLOW_EXPIRED
				))
			}
			if (loaded.status !== 'VALID'
				|| loaded.state.phase !== RISK_CHALLENGE_PHASE.NAVIGATING) {
				return failureResult(fail(
					loaded.state,
					RISK_CHALLENGE_FAILURE_REASON.RECHECK_ERROR
				))
			}
			const state = save({
				...loaded.state,
				phase: RISK_CHALLENGE_PHASE.RETURNED
			})
			return {
				handled: true,
				action: RISK_CHALLENGE_ACTION.RETURN,
				returnPath: state.returnPath,
				state
			}
		},

		claimRecheck() {
			const loaded = read()
			if (loaded.status === 'CORRUPT') {
				clear()
				fail(null, RISK_CHALLENGE_FAILURE_REASON.RECHECK_ERROR)
				return false
			}
			if (loaded.status === 'EXPIRED') {
				fail(loaded.state, RISK_CHALLENGE_FAILURE_REASON.FLOW_EXPIRED)
				return false
			}
			if (loaded.status !== 'VALID'
				|| loaded.state.phase !== RISK_CHALLENGE_PHASE.RETURNED) {
				return false
			}
			save({ ...loaded.state, phase: RISK_CHALLENGE_PHASE.RECHECKING })
			return true
		},

		completeRecheck() {
			const loaded = read()
			if (loaded.status === 'VALID'
				&& loaded.state.phase === RISK_CHALLENGE_PHASE.RECHECKING) {
				clear()
				return true
			}
			return false
		},

		failRecheck(reason, fallbackRoute = safeDefaultReturnPath) {
			const normalizedReason = FAILURE_REASONS.has(reason)
				? reason
				: RISK_CHALLENGE_FAILURE_REASON.RECHECK_ERROR
			const loaded = read()
			return fail(loaded.state, normalizedReason, fallbackRoute)
		},

		failureReason() {
			const loaded = read()
			return loaded.status === 'VALID'
				&& loaded.state.phase === RISK_CHALLENGE_PHASE.FAILED
				? loaded.state.failureReason
				: null
		},

		resetForManualRetry(retryFallback = safeDefaultReturnPath) {
			const loaded = read()
			const returnPath = safeReturnPath(
				loaded.state?.returnPath || retryFallback,
				allowedReturnPrefixes,
				safeDefaultReturnPath
			)
			clear()
			return returnPath
		},

		clear,

		state() {
			const loaded = read()
			return loaded.status === 'VALID' ? loaded.state : null
		}
	}
}

function readState({
	storage,
	storageKey,
	now,
	flowTtlMillis,
	allowedReturnPrefixes
}) {
	const raw = storage.getItem(storageKey)
	if (!raw) return { status: 'EMPTY', state: null }
	let state
	try {
		state = JSON.parse(raw)
	} catch (error) {
		return { status: 'CORRUPT', state: null }
	}
	if (!validState(state, allowedReturnPrefixes)) {
		return { status: 'CORRUPT', state: null }
	}
	if (state.phase !== RISK_CHALLENGE_PHASE.FAILED
		&& (state.flowStartedAt + flowTtlMillis <= now
			|| state.attemptExpiresAt <= now)) {
		return { status: 'EXPIRED', state }
	}
	return { status: 'VALID', state }
}

function validState(state, allowedReturnPrefixes) {
	if (!state || typeof state !== 'object' || Array.isArray(state)) return false
	const fields = Object.keys(state)
	return fields.length === STATE_FIELDS.size
		&& fields.every(field => STATE_FIELDS.has(field))
		&& state.version === STATE_VERSION
		&& PHASES.has(state.phase)
		&& Number.isInteger(state.attempt)
		&& state.attempt >= 1
		&& state.attempt <= MAX_ATTEMPTS
		&& Number.isFinite(state.flowStartedAt)
		&& state.flowStartedAt > 0
		&& Number.isFinite(state.attemptExpiresAt)
		&& state.attemptExpiresAt > 0
		&& safeReturnPath(state.returnPath, allowedReturnPrefixes, '') === state.returnPath
		&& (state.failureReason === null || FAILURE_REASONS.has(state.failureReason))
		&& (state.phase === RISK_CHALLENGE_PHASE.FAILED
			? FAILURE_REASONS.has(state.failureReason)
			: state.failureReason === null)
}

function validateChallenge(error, allowedPath, now) {
	const path = String(error?.challengePath || '')
	const reference = String(error?.challengeRef || '')
	const expiresAt = Date.parse(String(error?.expiresAt || ''))
	if (!reference || path !== allowedPath || !Number.isFinite(expiresAt)
		|| expiresAt <= now) {
		return null
	}
	return { path, reference, expiresAt }
}

function failedState(previous, failureReason, now, returnPath) {
	return {
		version: STATE_VERSION,
		phase: RISK_CHALLENGE_PHASE.FAILED,
		attempt: Math.min(MAX_ATTEMPTS, Math.max(1, previous?.attempt || 1)),
		flowStartedAt: previous?.flowStartedAt || now,
		attemptExpiresAt: previous?.attemptExpiresAt || now,
		returnPath,
		failureReason
	}
}

function failureResult(state) {
	return {
		handled: true,
		action: RISK_CHALLENGE_ACTION.FAILURE,
		failureReason: state.failureReason,
		state
	}
}

function safeReturnPath(value, allowedPrefixes, fallback) {
	const route = String(value || '')
	const path = route.split(/[?#]/, 1)[0]
	const safe = route.startsWith('/')
		&& !route.startsWith('//')
		&& !route.includes('\\')
		&& !/[\u0000-\u001f\u007f]/.test(route)
		&& allowedPrefixes.some(prefix => path.startsWith(prefix))
		&& path !== '/pages/risk/challenge-complete'
		&& path !== '/pages/risk/challenge-failed'
		&& !path.startsWith('/api/')
	if (safe) return route
	const normalizedFallback = String(fallback || '')
	return normalizedFallback.startsWith('/')
		&& !normalizedFallback.startsWith('//')
		? normalizedFallback
		: '/'
}
