const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'admin-ai-model-form.js'), 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('web search is an explicit model capability', async () => {
	const { AI_MODEL_CAPABILITY_OPTIONS, validateAiModelForm } = await loadModule()
	assert.equal(
		AI_MODEL_CAPABILITY_OPTIONS.some(option => option.code === 'WEB_SEARCH'),
		true
	)
	const result = validateAiModelForm({
		modelName: 'gpt-web',
		vendor: 'openai',
		description: '',
		iconPublicId: '',
		tagsText: '',
		inputRatio: '1',
		cachedInputRatio: '1',
		outputRatio: '1',
		contextWindowK: '128',
		maxOutputK: '16',
		capabilities: ['RESPONSES', 'WEB_SEARCH']
	})
	assert.equal(result.valid, true)
	assert.deepEqual(result.command.capabilities, ['RESPONSES', 'WEB_SEARCH'])
})

test('legacy aggregate media capabilities are rejected instead of being inferred', async () => {
	const { validateAiModelForm } = await loadModule()
	const base = {
		modelName: 'gpt-media',
		vendor: 'openai',
		description: '',
		iconPublicId: '',
		tagsText: '',
		inputRatio: '1',
		cachedInputRatio: '1',
		outputRatio: '1',
		contextWindowK: '128',
		maxOutputK: '16'
	}

	for (const removed of ['IMAGE', 'AUDIO', 'VIDEO']) {
		const result = validateAiModelForm({
			...base,
			capabilities: ['RESPONSES', removed]
		})
		assert.equal(result.valid, false)
		assert.equal(Object.hasOwn(result.errors, 'capabilities'), true)
	}
})

test('empty form exposes the exact grouped capability contract', async () => {
	const {
		createEmptyAiModelForm,
		AI_MODEL_CAPABILITY_GROUPS,
		AI_MODEL_CAPABILITY_OPTIONS
	} = await loadModule()
	const form = createEmptyAiModelForm()

	assert.equal(form.modelName, '')
	assert.equal(form.vendor, '')
	assert.equal(form.cachedInputRatio, '1')
	assert.equal(form.contextWindowK, '')
	assert.equal(form.maxOutputK, '')
	assert.deepEqual(form.capabilities, [])
	assert.equal(Object.hasOwn(form, 'enabled'), false)
	assert.deepEqual(AI_MODEL_CAPABILITY_OPTIONS.map(item => item.code), [
		'CHAT_COMPLETIONS', 'RESPONSES', 'WEB_SEARCH',
		'IMAGE_INPUT', 'IMAGE_GENERATION', 'IMAGE_EDIT',
		'AUDIO_INPUT', 'AUDIO_GENERATION', 'AUDIO_EDIT',
		'VIDEO_INPUT', 'VIDEO_GENERATION', 'VIDEO_EDIT'
	])
	assert.deepEqual(
		AI_MODEL_CAPABILITY_GROUPS.map(group => group.options.map(option => option.code)),
		[
			['CHAT_COMPLETIONS', 'RESPONSES', 'WEB_SEARCH'],
			['IMAGE_INPUT', 'IMAGE_GENERATION', 'IMAGE_EDIT'],
			['AUDIO_INPUT', 'AUDIO_GENERATION', 'AUDIO_EDIT'],
			['VIDEO_INPUT', 'VIDEO_GENERATION', 'VIDEO_EDIT']
		]
	)
})

test('gateway discovery prefill never supplies billing ratios or capabilities', async () => {
	const { createDiscoveredAiModelForm } = await loadModule()

	const form = createDiscoveredAiModelForm({
		modelId: ' GPT-5.4-Codex ',
		owner: ' OpenAI '
	})

	assert.equal(form.modelName, 'GPT-5.4-Codex')
	assert.equal(form.vendor, '')
	assert.equal(form.inputRatio, '')
	assert.equal(form.cachedInputRatio, '')
	assert.equal(form.outputRatio, '')
	assert.equal(form.contextWindowK, '')
	assert.equal(form.maxOutputK, '')
	assert.deepEqual(form.capabilities, [])
	assert.equal(Object.hasOwn(form, 'enabled'), false)
})

test('validation normalizes tags, ratios and capabilities without losing decimal text', async () => {
	const { validateAiModelForm } = await loadModule()
	const result = validateAiModelForm({
		modelName: ' GPT-5.6 ',
		vendor: ' OpenAI ',
		description: '  Production model  ',
		iconPublicId: 'AAAAAAAAAAE',
		tagsText: 'chat, chat\nreasoning',
		inputRatio: '1.25000000',
		cachedInputRatio: '0.12500000',
		outputRatio: '2.00000000',
		contextWindowK: '256',
		maxOutputK: '32',
		capabilities: ['RESPONSES', 'IMAGE_INPUT', 'IMAGE_GENERATION']
	})

	assert.equal(result.valid, true)
	assert.deepEqual(result.command, {
		modelName: 'gpt-5.6',
		description: 'Production model',
		iconPublicId: 'AAAAAAAAAAE',
		tags: ['chat', 'reasoning'],
		vendor: 'openai',
		inputRatio: '1.25000000',
		cachedInputRatio: '0.12500000',
		outputRatio: '2.00000000',
		contextWindowK: 256,
		maxOutputK: 32,
		capabilities: ['RESPONSES', 'IMAGE_INPUT', 'IMAGE_GENERATION']
	})
})

test('validation reports field-specific errors and requires at least one capability', async () => {
	const { validateAiModelForm } = await loadModule()
	const result = validateAiModelForm({
		modelName: '',
		vendor: '',
		description: '',
		iconPublicId: '',
		tagsText: '',
		inputRatio: '-1',
		cachedInputRatio: 'not-a-ratio',
		outputRatio: 'not-a-ratio',
		contextWindowK: '256',
		maxOutputK: '32',
		capabilities: []
	})

	assert.equal(result.valid, false)
	assert.deepEqual(Object.keys(result.errors).sort(), [
		'cachedInputRatio', 'capabilities', 'inputRatio', 'modelName', 'outputRatio', 'vendor'
	])
})

test('merge patch contains only changed editable fields and uses null to clear optional text', async () => {
	const { createMergePatch, modelToAiModelForm } = await loadModule()
	const snapshot = modelToAiModelForm({
		modelName: 'gpt-5.5',
		description: 'old',
		iconPublicId: 'AAAAAAAAAAE',
		icon: 'https://example.test/old.svg',
		tags: ['chat'],
		vendor: 'openai',
		inputRatio: 1,
		cachedInputRatio: 0.5,
		outputRatio: 2,
		contextWindowK: 256,
		maxOutputK: 32,
		contextWindowTokens: '256000',
		maxOutputTokens: '32000',
		capabilities: ['RESPONSES'],
		enabled: true
	})
	const draft = {
		...snapshot,
		description: '',
		iconPublicId: '',
		tagsText: 'chat,reasoning',
		cachedInputRatio: '0.25',
		outputRatio: '2.50',
		capabilities: ['RESPONSES', 'IMAGE_INPUT', 'IMAGE_GENERATION']
	}

	assert.deepEqual(createMergePatch(snapshot, draft), {
		description: null,
		iconPublicId: null,
		tags: ['chat', 'reasoning'],
		cachedInputRatio: '0.25',
		outputRatio: '2.50',
		capabilities: ['RESPONSES', 'IMAGE_INPUT', 'IMAGE_GENERATION']
	})
	assert.equal(Object.hasOwn(createMergePatch(snapshot, draft), 'enabled'), false)
})

test('model editing fills K fields from K responses and never derives them from raw Token strings', async () => {
	const { modelToAiModelForm } = await loadModule()

	assert.deepEqual(
		{
			contextWindowK: modelToAiModelForm({
				contextWindowK: 256,
				maxOutputK: 32,
				contextWindowTokens: '999999',
				maxOutputTokens: '888888'
			}).contextWindowK,
			maxOutputK: modelToAiModelForm({
				contextWindowK: 256,
				maxOutputK: 32,
				contextWindowTokens: '999999',
				maxOutputTokens: '888888'
			}).maxOutputK
		},
		{ contextWindowK: '256', maxOutputK: '32' })

	const unconfigured = modelToAiModelForm({
		contextWindowTokens: '256000',
		maxOutputTokens: '32000'
	})
	assert.equal(unconfigured.contextWindowK, '')
	assert.equal(unconfigured.maxOutputK, '')
})

test('token limit validation accepts bounded positive K integers and sends JSON numbers only', async () => {
	const { validateAiModelForm } = await loadModule()
	const base = {
		modelName: 'gpt-5.6',
		vendor: 'openai',
		description: '',
		iconPublicId: '',
		tagsText: '',
		inputRatio: '1',
		cachedInputRatio: '1',
		outputRatio: '1',
		capabilities: ['RESPONSES']
	}

	const maximum = validateAiModelForm({
		...base,
		contextWindowK: '2147483647',
		maxOutputK: '2147483647'
	})
	assert.equal(maximum.valid, true)
	assert.equal(maximum.command.contextWindowK, 2147483647)
	assert.equal(maximum.command.maxOutputK, 2147483647)
	assert.equal(Object.hasOwn(maximum.command, 'contextWindowTokens'), false)
	assert.equal(Object.hasOwn(maximum.command, 'maxOutputTokens'), false)

	for (const [contextWindowK, maxOutputK, expectedField] of [
		['', '32', 'contextWindowK'],
		[' 256 ', '32', 'contextWindowK'],
		['256.0', '32', 'contextWindowK'],
		['2.56e2', '32', 'contextWindowK'],
		['0', '32', 'contextWindowK'],
		['2147483648', '32', 'contextWindowK'],
		['256', '257', 'maxOutputK']
	]) {
		const result = validateAiModelForm({ ...base, contextWindowK, maxOutputK })
		assert.equal(result.valid, false)
		assert.equal(Object.hasOwn(result.errors, expectedField), true)
	}
})

test('merge patch includes only changed K fields as JSON numbers', async () => {
	const { createMergePatch, modelToAiModelForm } = await loadModule()
	const snapshot = modelToAiModelForm({
		modelName: 'gpt-5.6',
		description: '',
		iconPublicId: null,
		tags: [],
		vendor: 'openai',
		inputRatio: 1,
		cachedInputRatio: 1,
		outputRatio: 1,
		contextWindowK: 256,
		maxOutputK: 32,
		capabilities: ['RESPONSES']
	})

	assert.deepEqual(
		createMergePatch(snapshot, { ...snapshot, maxOutputK: '64' }),
		{ maxOutputK: 64 })
})
