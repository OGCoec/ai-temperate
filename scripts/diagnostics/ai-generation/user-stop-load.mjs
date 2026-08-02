import { randomUUID } from 'node:crypto'
import { performance } from 'node:perf_hooks'

const confirmation = process.env.AIT_CONFIRM_ISOLATED_LOAD
const baseUrl = process.env.AIT_TEST_BASE_URL
const cookie = process.env.AIT_TEST_USER_COOKIE
const csrfToken = process.env.AIT_TEST_CSRF_TOKEN
const csrfHeader = process.env.AIT_TEST_CSRF_HEADER_NAME || 'X-CSRF-TOKEN'
const modelPublicId = process.env.AIT_TEST_MODEL_PUBLIC_ID
const samples = Number.parseInt(process.env.AIT_TEST_SAMPLES || '200', 10)
const concurrency = Number.parseInt(process.env.AIT_TEST_CONCURRENCY || '1', 10)

if (confirmation !== 'YES_ISOLATED_NON_PRODUCTION') {
	throw new Error('AIT_CONFIRM_ISOLATED_LOAD must confirm an isolated non-production deployment.')
}
if (!baseUrl || !cookie || !csrfToken || !/^[A-Za-z0-9_-]{11}$/.test(modelPublicId || '')) {
	throw new Error('Isolated base URL, user Cookie, CSRF token, and model public ID are required.')
}
if (!Number.isSafeInteger(samples) || samples < 1 || samples > 10_000
	|| !Number.isSafeInteger(concurrency) || concurrency < 1 || concurrency > 100) {
	throw new Error('Load test sample or concurrency boundary is invalid.')
}

const commonHeaders = Object.freeze({
	Cookie: cookie,
	[csrfHeader]: csrfToken,
	'X-Client-Platform': 'H5'
})

async function createAndCancel() {
	const idempotencyKey = randomUUID()
	const response = await fetch(new URL('/api/ai/conversations/responses', baseUrl), {
		method: 'POST',
		headers: {
			...commonHeaders,
			Accept: 'text/event-stream, application/json',
			'Content-Type': 'application/json',
			'Idempotency-Key': idempotencyKey
		},
		body: JSON.stringify({
			modelPublicId,
			reasoningEffortLevel: 2,
			input: { text: 'isolated-ai-generation-load-test', attachments: [] }
		})
	})
	if (!response.ok) {
		throw new Error(`Generation request failed with HTTP ${response.status}.`)
	}
	const generationPublicId = response.headers.get('x-ai-generation-id')
	if (!/^[A-Za-z0-9_-]{22}$/.test(generationPublicId || '')) {
		throw new Error('Generation response did not expose a valid public ID.')
	}

	const started = performance.now()
	const cancellation = await fetch(new URL(
		`/api/ai/conversations/generations/${encodeURIComponent(generationPublicId)}/cancel`,
		baseUrl
	), { method: 'POST', headers: commonHeaders })
	if (!cancellation.ok) {
		throw new Error(`Cancellation failed with HTTP ${cancellation.status}.`)
	}
	await response.body?.cancel()

	const deadline = performance.now() + 10_000
	while (performance.now() < deadline) {
		const statusResponse = await fetch(new URL(
			`/api/ai/conversations/generations/${encodeURIComponent(generationPublicId)}`,
			baseUrl
		), { headers: commonHeaders })
		if (!statusResponse.ok) {
			throw new Error(`Generation status failed with HTTP ${statusResponse.status}.`)
		}
		const status = (await statusResponse.json()).status
		if (status === 'SETTLED' || status === 'REFUNDED') {
			return performance.now() - started
		}
		if (status === 'RECONCILE_REQUIRED') {
			throw new Error('Generation entered RECONCILE_REQUIRED.')
		}
		await new Promise(resolve => setTimeout(resolve, 50))
	}
	throw new Error('Generation did not settle within ten seconds.')
}

async function worker(results, failures, next) {
	while (true) {
		const index = next.value++
		if (index >= samples) return
		try {
			results.push(await createAndCancel())
		} catch (error) {
			failures.push({ sample: index + 1, error: error.message })
		}
	}
}

const durations = []
const failures = []
const next = { value: 0 }
await Promise.all(Array.from(
	{ length: Math.min(concurrency, samples) },
	() => worker(durations, failures, next)
))
durations.sort((left, right) => left - right)
const p95Index = durations.length ? Math.ceil(durations.length * 0.95) - 1 : -1

process.stdout.write(`${JSON.stringify({
	samplesRequested: samples,
	samplesCompleted: durations.length,
	concurrency,
	localCancelToTerminalP95Ms: p95Index >= 0 ? Math.round(durations[p95Index]) : null,
	failureCount: failures.length,
	failures: failures.slice(0, 10)
}, null, 2)}\n`)
if (failures.length > 0) process.exitCode = 1
