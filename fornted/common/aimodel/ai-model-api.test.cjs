const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadAiModelApi(request) {
	const nonce = `${Date.now()}-${Math.random()}`
	globalThis.__requestAiModels = request
	const httpClientUrl = `${sourceUrl(`export const authorizedRequest = (...args) => globalThis.__requestAiModels(...args)`)}#http-${nonce}`
	const source = fs.readFileSync(
		path.resolve(__dirname, 'ai-model-api.js'),
		'utf8'
	).replace("from '../auth/http-client.js'", `from '${httpClientUrl}'`)
	return import(`${sourceUrl(source)}#api-${nonce}`)
}

const PAGE_RESPONSE = {
	models: [{
		publicId: 'AAABi0VWeJ8',
		modelName: 'gpt-5.4',
		modelNameMatchedTokens: ['5.4'],
		vendor: 'openai',
		description: '用于代码与推理的模型。',
		descriptionMatchedTokens: ['代码', '推理'],
		icon: 'https://cdn.example.test/gpt-5.4.svg',
		tags: ['代码', '推理'],
		inputRatio: 1,
		cachedInputRatio: '0.25',
		outputRatio: 4,
		capabilities: ['RESPONSES'],
		supportedReasoningEffortLevels: [1, 2, 3, 4, 5],
		supportedImageGenerationLevels: [],
		supportedImageAspects: [],
		defaultReasoningEffortLevel: 2
	}],
	pageNum: 2,
	pageSize: 20,
	total: '21',
	pages: 2,
	hasPrevious: true,
	hasNext: false
}

test('requests model pages through the authenticated client and normalizes safe response values', async () => {
	const calls = []
	const module = await loadAiModelApi(async (...args) => {
		calls.push(args)
		return PAGE_RESPONSE
	})

	const page = await module.aiModelApi.list({
		pageNum: 2,
		pageSize: 20,
		keyword: '  Mini 5.4  '
	})

	assert.deepEqual(calls, [[
		'/api/ai-models?pageNum=2&pageSize=20&keyword=Mini%205.4',
		{ method: 'GET' }
	]])
	assert.equal(page.total, 21)
	assert.equal(page.models[0].publicId, 'AAABi0VWeJ8')
	assert.equal(page.models[0].icon, 'https://cdn.example.test/gpt-5.4.svg')
	assert.equal(page.models[0].cachedInputRatio, '0.25')
	assert.equal(page.models[0].outputRatio, '4')
	assert.deepEqual(page.models[0].modelNameMatchedTokens, ['5.4'])
	assert.equal(Object.isFrozen(page.models[0].modelNameMatchedTokens), true)
	assert.deepEqual(page.models[0].supportedReasoningEffortLevels, [1, 2, 3, 4, 5])
	assert.deepEqual(page.models[0].descriptionMatchedTokens, ['代码', '推理'])
	assert.equal(Object.isFrozen(page.models[0].descriptionMatchedTokens), true)
	assert.equal(page.models[0].defaultReasoningEffortLevel, 2)
	assert.equal(Object.isFrozen(page.models[0].supportedReasoningEffortLevels), true)
	assert.deepEqual(page.models[0].supportedImageGenerationLevels, [])
	assert.deepEqual(page.models[0].supportedImageAspects, [])
	assert.equal(Object.isFrozen(page.models[0].supportedImageGenerationLevels), true)
	assert.equal(Object.isFrozen(page.models[0].supportedImageAspects), true)
	delete globalThis.__requestAiModels
})

test('normalizes image generation profile levels and supported aspects', async () => {
	const module = await loadAiModelApi(async () => ({
		...PAGE_RESPONSE.models[0],
		capabilities: ['RESPONSES', 'IMAGE_GENERATION'],
		supportedImageGenerationLevels: [1, 2, 3],
		supportedImageAspects: ['SQUARE', 'LANDSCAPE', 'PORTRAIT']
	}))

	const model = await module.aiModelApi.detail('AAABi0VWeJ8')

	assert.deepEqual(model.supportedImageGenerationLevels, [1, 2, 3])
	assert.deepEqual(model.supportedImageAspects, ['SQUARE', 'LANDSCAPE', 'PORTRAIT'])
	delete globalThis.__requestAiModels
})

test('rejects image generation levels above High', async () => {
	const module = await loadAiModelApi(async () => ({
		...PAGE_RESPONSE.models[0],
		capabilities: ['IMAGE_GENERATION'],
		supportedImageGenerationLevels: [1, 2, 3, 4],
		supportedImageAspects: ['SQUARE', 'LANDSCAPE', 'PORTRAIT']
	}))

	await assert.rejects(
		() => module.aiModelApi.detail('AAABi0VWeJ8'),
		error => error?.code === 'AI_MODEL_RESPONSE_INVALID'
	)
	delete globalThis.__requestAiModels
})

test('rejects unknown image generation aspects', async () => {
	const module = await loadAiModelApi(async () => ({
		...PAGE_RESPONSE.models[0],
		supportedImageGenerationLevels: [1, 2, 3],
		supportedImageAspects: ['SQUARE', 'CINEMA']
	}))

	await assert.rejects(
		() => module.aiModelApi.detail('AAABi0VWeJ8'),
		error => error?.code === 'AI_MODEL_RESPONSE_INVALID'
	)
	delete globalThis.__requestAiModels
})

test('requests model detail through the authenticated client and preserves the icon URL', async () => {
	const calls = []
	const module = await loadAiModelApi(async (...args) => {
		calls.push(args)
		return PAGE_RESPONSE.models[0]
	})

	const model = await module.aiModelApi.detail('AAABi0VWeJ8')

	assert.deepEqual(calls, [[
		'/api/ai-models/AAABi0VWeJ8',
		{ method: 'GET' }
	]])
	assert.equal(model.icon, 'https://cdn.example.test/gpt-5.4.svg')
	delete globalThis.__requestAiModels
})

test('normalizes an empty icon to null so the user interface can use its placeholder', async () => {
	const module = await loadAiModelApi(async () => ({
		...PAGE_RESPONSE.models[0],
		icon: '   '
	}))

	const model = await module.aiModelApi.detail('AAABi0VWeJ8')

	assert.equal(model.icon, null)
	delete globalThis.__requestAiModels
})

test('defaults missing model-name match tokens to an empty immutable list for rolling deployments', async () => {
	const module = await loadAiModelApi(async () => ({
		...PAGE_RESPONSE,
		models: [{
			...PAGE_RESPONSE.models[0],
			modelNameMatchedTokens: undefined
		}]
	}))

	const page = await module.aiModelApi.list()
	assert.deepEqual(page.models[0].modelNameMatchedTokens, [])
	assert.equal(Object.isFrozen(page.models[0].modelNameMatchedTokens), true)
	delete globalThis.__requestAiModels
})

test('rejects invalid public model IDs before making a request', async () => {
	const module = await loadAiModelApi(async () => {
		throw new Error('the request must not be sent')
	})

	await assert.rejects(
		() => module.aiModelApi.detail('not-a-public-id'),
		error => error?.code === 'AI_MODEL_PUBLIC_ID_INVALID'
	)
	delete globalThis.__requestAiModels
})

test('rejects overlong catalog keywords before making a request', async () => {
	let calls = 0
	const module = await loadAiModelApi(async () => {
		calls += 1
		return PAGE_RESPONSE
	})

	await assert.rejects(
		() => module.aiModelApi.list({ keyword: 'a'.repeat(129) }),
		error => error?.code === 'AI_MODEL_PAGE_INVALID'
	)
	assert.equal(calls, 0)
	delete globalThis.__requestAiModels
})

test('rejects malformed reasoning effort capabilities from the model catalog', async () => {
	const module = await loadAiModelApi(async () => ({
		...PAGE_RESPONSE,
		models: [{
			...PAGE_RESPONSE.models[0],
			supportedReasoningEffortLevels: [1, 2, 2, 6],
			defaultReasoningEffortLevel: 4
		}]
	}))

	await assert.rejects(
		() => module.aiModelApi.list(),
		error => error?.code === 'AI_MODEL_RESPONSE_INVALID'
	)
	delete globalThis.__requestAiModels
})

test('rejects non-numeric default reasoning effort levels', async () => {
	const module = await loadAiModelApi(async () => ({
		...PAGE_RESPONSE,
		models: [{
			...PAGE_RESPONSE.models[0],
			defaultReasoningEffortLevel: '2'
		}]
	}))

	await assert.rejects(
		() => module.aiModelApi.list(),
		error => error?.code === 'AI_MODEL_RESPONSE_INVALID'
	)
	delete globalThis.__requestAiModels
})

test('rejects malformed model responses instead of rendering untrusted shapes', async () => {
	const module = await loadAiModelApi(async () => ({
		models: [{ publicId: 'AAABi0VWeJ8', modelName: '' }],
		pageNum: 1,
		pageSize: 20,
		total: 1,
		pages: 1,
		hasPrevious: false,
		hasNext: false
	}))

	await assert.rejects(
		() => module.aiModelApi.list(),
		error => error?.code === 'AI_MODEL_RESPONSE_INVALID'
	)
	delete globalThis.__requestAiModels
})

test('rejects malformed description match token arrays', async () => {
	const module = await loadAiModelApi(async () => ({
		...PAGE_RESPONSE,
		models: [{
			...PAGE_RESPONSE.models[0],
			descriptionMatchedTokens: ['推理', 5]
		}]
	}))

	await assert.rejects(
		() => module.aiModelApi.list(),
		error => error?.code === 'AI_MODEL_RESPONSE_INVALID'
	)
	delete globalThis.__requestAiModels
})

test('rejects malformed model-name match token arrays when the additive field is present', async () => {
	const module = await loadAiModelApi(async () => ({
		...PAGE_RESPONSE,
		models: [{
			...PAGE_RESPONSE.models[0],
			modelNameMatchedTokens: ['mini', 5]
		}]
	}))

	await assert.rejects(
		() => module.aiModelApi.list(),
		error => error?.code === 'AI_MODEL_RESPONSE_INVALID'
	)
	delete globalThis.__requestAiModels
})
