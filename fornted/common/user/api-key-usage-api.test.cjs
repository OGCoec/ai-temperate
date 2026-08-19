const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const API_KEY_ID = '01K32S6J00E4Q0H7R9M2N5P8TX'
const USAGE_CURSOR = 'C'.repeat(38)

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadApi(request) {
	const nonce = `${Date.now()}-${Math.random()}`
	globalThis.__apiKeyUsageAuthorizedRequest = request
	const httpClientUrl = `${sourceUrl(`export const authorizedRequest = (...args) => globalThis.__apiKeyUsageAuthorizedRequest(...args)`)}#http-${nonce}`
	const source = fs.readFileSync(path.resolve(__dirname, 'api-key-usage-api.js'), 'utf8')
		.replace("from '../auth/http-client.js'", `from '${httpClientUrl}'`)
	return import(`${sourceUrl(source)}#usage-${nonce}`)
}

function page(overrides = {}) {
	return {
		period: {
			from: '2026-08-18T15:00:00Z',
			to: '2026-08-18T16:00:00Z'
		},
		summary: {
			requestCount: '42',
			promptTokens: '9007199254740993125',
			cachedPromptTokens: '48000',
			uncachedPromptTokens: '9007199254740945125',
			completionTokens: '18500',
			chargedQuotaMinor: '386',
			pendingRequestCount: '1',
			pendingReservedQuotaMinor: '20'
		},
		items: [{
			modelPublicId: 'AAAAAAAAABc',
			modelName: 'gpt-test',
			vendor: 'openai',
			stream: true,
			billingStatus: 'SETTLED',
			promptTokens: '3150',
			cachedPromptTokens: '1200',
			uncachedPromptTokens: '1950',
			completionTokens: '480',
			chargedQuotaMinor: '12',
			reservedQuotaMinor: '15',
			finishReason: 'STOP',
			failureCode: null,
			createdAt: '2026-08-18T15:42:10Z',
			settledAt: '2026-08-18T15:42:14Z'
		}],
		nextCursor: null,
		...overrides
	}
}

test('default recent-hour query leaves server time authoritative', async () => {
	const calls = []
	const { apiKeyUsageApi } = await loadApi(async (...args) => {
		calls.push(args)
		return page()
	})

	const result = await apiKeyUsageApi.query(API_KEY_ID)

	assert.deepEqual(calls, [[
		`/api/users/me/api-keys/${API_KEY_ID}/usage?pageSize=20`,
		{ method: 'GET' }
	]])
	assert.equal(result.summary.promptTokens, '9007199254740993125')
	assert.equal(Object.isFrozen(result), true)
	assert.equal(Object.isFrozen(result.items[0]), true)
})

test('pagination reuses the fixed server period', async () => {
	const calls = []
	const { apiKeyUsageApi } = await loadApi(async (...args) => {
		calls.push(args)
		return page()
	})

	await apiKeyUsageApi.query(API_KEY_ID, {
		from: '2026-08-18T15:00:00Z',
		to: '2026-08-18T16:00:00Z',
		cursor: USAGE_CURSOR,
		pageSize: 40
	})

	assert.match(calls[0][0], /from=2026-08-18T15%3A00%3A00\.000Z/)
	assert.match(calls[0][0], /to=2026-08-18T16%3A00%3A00\.000Z/)
	assert.match(calls[0][0], new RegExp(`cursor=${USAGE_CURSOR}`))
	assert.match(calls[0][0], /pageSize=40/)
})

test('normalizes all billing states and formats minor quota without Number', async () => {
	const statuses = [
		['SETTLED', '12'],
		['RESERVED', null],
		['FAILED_REFUNDED', '0'],
		['RECONCILE_REQUIRED', '15'],
		['REFUNDED', '0']
	]
	const { apiKeyUsageApi, formatQuotaMinor } = await loadApi(async () => page({
		items: statuses.map(([billingStatus, chargedQuotaMinor], index) => ({
			...page().items[0],
			billingStatus,
			chargedQuotaMinor,
			modelPublicId: `AAAAAAAAA${String.fromCharCode(66 + index)}c`
		}))
	}))

	const result = await apiKeyUsageApi.query(API_KEY_ID)

	assert.deepEqual(result.items.map(item => item.billingStatus), statuses.map(value => value[0]))
	assert.equal(formatQuotaMinor('9007199254740993125'), '90071992547409931.25')
	assert.equal(formatQuotaMinor('7'), '0.07')
})

test('rejects incomplete ranges and unsafe response values', async () => {
	const invalidInput = await loadApi(async () => page())
	await assert.rejects(
		() => invalidInput.apiKeyUsageApi.query(API_KEY_ID, {
			from: '2026-08-18T15:00:00Z'
		}),
		error => error.code === 'API_KEY_USAGE_INPUT_INVALID')
	await assert.rejects(
		() => invalidInput.apiKeyUsageApi.query(API_KEY_ID, { cursor: 'short' }),
		error => error.code === 'API_KEY_USAGE_INPUT_INVALID')

	const invalidResponse = await loadApi(async () => page({
		summary: { ...page().summary, chargedQuotaMinor: 3.86 }
	}))
	await assert.rejects(
		() => invalidResponse.apiKeyUsageApi.query(API_KEY_ID),
		error => error.code === 'API_KEY_USAGE_RESPONSE_INVALID')

	const invalidCursorResponse = await loadApi(async () => page({ nextCursor: 'short' }))
	await assert.rejects(
		() => invalidCursorResponse.apiKeyUsageApi.query(API_KEY_ID),
		error => error.code === 'API_KEY_USAGE_RESPONSE_INVALID')
})

test('rejects legacy lowercase and zero API Key IDs before transport', async () => {
	let calls = 0
	const { apiKeyUsageApi } = await loadApi(async () => {
		calls += 1
		return page()
	})

	for (const invalidId of [
		'AAAAAAAAAAE',
		API_KEY_ID.toLowerCase(),
		'00000000000000000000000000'
	]) {
		await assert.rejects(
			() => apiKeyUsageApi.query(invalidId),
			error => error.code === 'API_KEY_USAGE_INPUT_INVALID')
	}
	assert.equal(calls, 0)
})
