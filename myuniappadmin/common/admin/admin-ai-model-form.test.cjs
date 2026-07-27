const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'admin-ai-model-form.js'), 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('empty form uses a safe disabled create default outside editable fields', async () => {
	const { createEmptyAiModelForm, AI_MODEL_CAPABILITY_OPTIONS } = await loadModule()
	const form = createEmptyAiModelForm()

	assert.equal(form.modelName, '')
	assert.equal(form.vendor, '')
	assert.deepEqual(form.capabilities, [])
	assert.equal(Object.hasOwn(form, 'enabled'), false)
	assert.deepEqual(AI_MODEL_CAPABILITY_OPTIONS.map(item => item.code), [
		'CHAT_COMPLETIONS', 'RESPONSES', 'IMAGE', 'VIDEO', 'AUDIO'
	])
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
		outputRatio: '2.00000000',
		capabilities: ['RESPONSES', 'IMAGE']
	})

	assert.equal(result.valid, true)
	assert.deepEqual(result.command, {
		modelName: 'gpt-5.6',
		description: 'Production model',
		iconPublicId: 'AAAAAAAAAAE',
		tags: ['chat', 'reasoning'],
		vendor: 'openai',
		inputRatio: '1.25000000',
		outputRatio: '2.00000000',
		capabilities: ['RESPONSES', 'IMAGE']
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
		outputRatio: 'not-a-ratio',
		capabilities: []
	})

	assert.equal(result.valid, false)
	assert.deepEqual(Object.keys(result.errors).sort(), [
		'capabilities', 'inputRatio', 'modelName', 'outputRatio', 'vendor'
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
		outputRatio: 2,
		capabilities: ['RESPONSES'],
		enabled: true
	})
	const draft = {
		...snapshot,
		description: '',
		iconPublicId: '',
		tagsText: 'chat,reasoning',
		outputRatio: '2.50',
		capabilities: ['RESPONSES', 'IMAGE']
	}

	assert.deepEqual(createMergePatch(snapshot, draft), {
		description: null,
		iconPublicId: null,
		tags: ['chat', 'reasoning'],
		outputRatio: '2.50',
		capabilities: ['RESPONSES', 'IMAGE']
	})
	assert.equal(Object.hasOwn(createMergePatch(snapshot, draft), 'enabled'), false)
})
