const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

function token(content, scopeName = 'source.java') {
	return {
		content,
		explanation: [{ content, scopes: [{ scopeName }] }]
	}
}

test('keeps bracket depth across stable lines and uses the Antigravity three-color cycle', async () => {
	const { createAiCodeBracketState } = await loadEsmModule(
		path.join(__dirname, 'ai-code-bracket-state.js')
	)
	const state = createAiCodeBracketState('java')
	const colored = state.appendStable([
		token('{'), token('\n'), token('('), token('['), token(']'), token(')'), token('}')
	])
	const bracketRoles = colored.filter(item => /[()[\]{}]/.test(item.content)).map(item => item.colorRole)

	assert.deepEqual(bracketRoles, [
		'bracket-level-1',
		'bracket-level-2',
		'bracket-level-3',
		'bracket-level-3',
		'bracket-level-2',
		'bracket-level-1'
	])
})

test('does not color brackets inside strings or comments and marks mismatches', async () => {
	const { createAiCodeBracketState } = await loadEsmModule(
		path.join(__dirname, 'ai-code-bracket-state.js')
	)
	const state = createAiCodeBracketState('java')
	const colored = state.appendStable([
		token('"("', 'string.quoted.double.java'),
		token('// }', 'comment.line.double-slash.java'),
		token('(]')
	])

	assert.equal(colored[0].colorRole, undefined)
	assert.equal(colored[1].colorRole, undefined)
	assert.equal(colored.find(item => item.content === '(').colorRole, 'bracket-level-1')
	assert.equal(colored.find(item => item.content === ']').colorRole, 'bracket-error')
})

test('recolors only the unstable tail from the last stable bracket snapshot', async () => {
	const { createAiCodeBracketState } = await loadEsmModule(
		path.join(__dirname, 'ai-code-bracket-state.js')
	)
	const state = createAiCodeBracketState('java')
	state.appendStable([token('{'), token('\n')])

	assert.equal(state.replaceUnstable([token('(')])[0].colorRole, 'bracket-level-2')
	assert.deepEqual(
		state.replaceUnstable([token('[]')]).map(item => item.colorRole),
		['bracket-level-2', 'bracket-level-2']
	)
})

test('colors angle brackets only when grammar scopes identify type parameters or templates', async () => {
	const { createAiCodeBracketState } = await loadEsmModule(
		path.join(__dirname, 'ai-code-bracket-state.js')
	)
	const state = createAiCodeBracketState('cpp')
	const operator = state.appendStable([token('a < b', 'keyword.operator.comparison.cpp')])
	const template = state.appendStable([
		token('<>', 'punctuation.definition.template-expression.begin.cpp')
	])

	assert.equal(operator.some(item => item.colorRole?.startsWith('bracket-')), false)
	assert.deepEqual(template.map(item => item.colorRole), ['bracket-level-1', 'bracket-level-1'])
})
