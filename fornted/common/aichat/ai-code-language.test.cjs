const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

test('normalizes common aliases against the registered Shiki language catalog', async () => {
	const { createAiCodeLanguageResolver } = await loadEsmModule(
		path.join(__dirname, 'ai-code-language.js')
	)
	const resolve = createAiCodeLanguageResolver([
		{ id: 'javascript', name: 'JavaScript', aliases: ['js'] },
		{ id: 'typescript', name: 'TypeScript', aliases: ['ts'] },
		{ id: 'python', name: 'Python', aliases: ['py'] },
		{ id: 'cpp', name: 'C++', aliases: ['c++'] },
		{ id: 'csharp', name: 'C#', aliases: ['cs'] }
	])

	assert.equal(resolve('JS').canonicalId, 'javascript')
	assert.equal(resolve({ id: 'c++', label: 'C++' }).canonicalId, 'cpp')
	assert.equal(resolve('cs').canonicalId, 'csharp')
	assert.equal(resolve('py').canonicalId, 'python')
})

test('rejects unregistered or unsafe language identifiers without creating import paths', async () => {
	const { createAiCodeLanguageResolver } = await loadEsmModule(
		path.join(__dirname, 'ai-code-language.js')
	)
	const resolve = createAiCodeLanguageResolver([{ id: 'java', name: 'Java', aliases: [] }])

	assert.deepEqual(resolve('../../malicious.js'), {
		requestedId: 'malicious.js',
		canonicalId: 'text',
		label: 'Plain text',
		supported: false
	})
	assert.equal(resolve('x'.repeat(200)).canonicalId, 'text')
	assert.equal(resolve('').canonicalId, 'text')
})
