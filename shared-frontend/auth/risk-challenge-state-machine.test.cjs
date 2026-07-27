const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'risk-challenge-state-machine.js'),
		'utf8'
	)
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

function memoryStorage() {
	const values = new Map()
	return {
		getItem(key) { return values.has(key) ? values.get(key) : null },
		setItem(key, value) { values.set(key, String(value)) },
		removeItem(key) { values.delete(key) }
	}
}

function required(attempt, expiresAt) {
	return {
		code: 'RISK_CHALLENGE_REQUIRED',
		challengePath: '/api/_edge/risk-challenge',
		challengeRef: `reference-${attempt}`,
		expiresAt
	}
}

test('runs at most three challenge rounds and never creates a fourth navigation', async () => {
	const { createRiskChallengeFlow, RISK_CHALLENGE_ACTION } = await loadModule()
	let now = Date.parse('2026-07-27T18:00:00Z')
	const flow = createRiskChallengeFlow({
		storage: memoryStorage(),
		storageKey: 'ordinary',
		now: () => now,
		defaultReturnPath: '/pages/auth/login',
		allowedReturnPrefixes: ['/pages/']
	})

	const first = flow.handleChallengeRequired(
		required(1, '2026-07-27T18:05:00Z'),
		'/pages/auth/login?from=challenge',
		'/api/_edge/risk-challenge'
	)
	assert.equal(first.action, RISK_CHALLENGE_ACTION.NAVIGATE)
	assert.equal(first.state.attempt, 1)
	assert.equal(
		flow.handleChallengeRequired(
			required(1, '2026-07-27T18:05:00Z'),
			'/pages/auth/login',
			'/api/_edge/risk-challenge'
		).action,
		RISK_CHALLENGE_ACTION.SUPPRESS
	)

	for (const attempt of [1, 2, 3]) {
		assert.equal(flow.markReturned().action, RISK_CHALLENGE_ACTION.RETURN)
		assert.equal(flow.claimRecheck(), true)
		assert.equal(flow.claimRecheck(), false)
		const response = flow.handleChallengeRequired(
			required(attempt + 1, '2026-07-27T18:05:00Z'),
			'/pages/auth/login',
			'/api/_edge/risk-challenge'
		)
		if (attempt < 3) {
			assert.equal(response.action, RISK_CHALLENGE_ACTION.NAVIGATE)
			assert.equal(response.state.attempt, attempt + 1)
		} else {
			assert.equal(response.action, RISK_CHALLENGE_ACTION.FAILURE)
			assert.equal(response.state.failureReason, 'EXHAUSTED')
			assert.equal(response.state.attempt, 3)
		}
	}
})

test('fails closed for expired flow and manual restart only returns a validated path', async () => {
	const {
		createRiskChallengeFlow,
		RISK_CHALLENGE_ACTION,
		RISK_CHALLENGE_FAILURE_REASON
	} = await loadModule()
	let now = Date.parse('2026-07-27T18:00:00Z')
	const storage = memoryStorage()
	const flow = createRiskChallengeFlow({
		storage,
		storageKey: 'ordinary',
		now: () => now,
		defaultReturnPath: '/pages/auth/login',
		allowedReturnPrefixes: ['/pages/']
	})

	flow.handleChallengeRequired(
		required(1, '2026-07-27T18:01:00Z'),
		'https://attacker.invalid/',
		'/api/_edge/risk-challenge'
	)
	now = Date.parse('2026-07-27T18:02:00Z')
	const returned = flow.markReturned()
	assert.equal(returned.action, RISK_CHALLENGE_ACTION.FAILURE)
	assert.equal(
		returned.state.failureReason,
		RISK_CHALLENGE_FAILURE_REASON.FLOW_EXPIRED
	)
	assert.equal(flow.resetForManualRetry('/pages/auth/login'), '/pages/auth/login')
	assert.equal(flow.state(), null)
})

test('successful recheck clears state and response errors become a recoverable failure', async () => {
	const { createRiskChallengeFlow, RISK_CHALLENGE_FAILURE_REASON } = await loadModule()
	const now = Date.parse('2026-07-27T18:00:00Z')
	const flow = createRiskChallengeFlow({
		storage: memoryStorage(),
		storageKey: 'ordinary',
		now: () => now,
		defaultReturnPath: '/pages/auth/login',
		allowedReturnPrefixes: ['/pages/']
	})

	flow.handleChallengeRequired(
		required(1, '2026-07-27T18:05:00Z'),
		'/pages/auth/login',
		'/api/_edge/risk-challenge'
	)
	flow.markReturned()
	assert.equal(flow.claimRecheck(), true)
	flow.completeRecheck()
	assert.equal(flow.state(), null)

	flow.handleChallengeRequired(
		required(1, '2026-07-27T18:05:00Z'),
		'/pages/auth/login',
		'/api/_edge/risk-challenge'
	)
	flow.markReturned()
	flow.claimRecheck()
	flow.failRecheck(RISK_CHALLENGE_FAILURE_REASON.RECHECK_ERROR)
	assert.equal(flow.state().phase, 'FAILED')
	assert.equal(flow.failureReason(), 'RECHECK_ERROR')
})
