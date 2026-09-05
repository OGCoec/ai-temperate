const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, '../shared-auth/password-policy.js'),
		'utf8'
	)
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(sourceUrl)
}

test('implements the SHOPPING_V1 five-level password contract', async () => {
	const { classifyPassword } = await loadModule()
	const cases = [
		['', 'NONE', 0, false],
		['Aa1!aa', 'NONE', 0, false],
		['1234567', 'WEAK', 1, false],
		['abc123?', 'MEDIUM', 2, true],
		['abcDEF123', 'STRONG', 3, true],
		['abcDEF12!', 'VERY_STRONG', 4, true],
		['Aa63.58516', 'VERY_STRONG', 4, true]
	]

	for (const [password, level, score, acceptable] of cases) {
		const result = classifyPassword(password)
		assert.equal(result.level, level, password)
		assert.equal(result.score, score, password)
		assert.equal(result.acceptable, acceptable, password)
	}
})

test('preserves the original Shopping medium fallback', async () => {
	const { classifyPassword } = await loadModule()

	assert.equal(classifyPassword('!!!!!!!').level, 'MEDIUM')
	assert.equal(classifyPassword('中文密码示例甲').level, 'MEDIUM')
})

test('rejects values beyond the BCrypt UTF-8 byte boundary without changing their level', async () => {
	const { classifyPassword, passwordError } = await loadModule()
	const atLimit = `Aa1!${'a'.repeat(68)}`
	const overLimit = `${atLimit}a`

	assert.equal(classifyPassword(atLimit).utf8Bytes, 72)
	assert.equal(classifyPassword(atLimit).acceptable, true)
	assert.equal(classifyPassword(overLimit).level, 'VERY_STRONG')
	assert.equal(classifyPassword(overLimit).utf8Bytes, 73)
	assert.equal(classifyPassword(overLimit).acceptable, false)
	assert.match(passwordError(overLimit, overLimit), /72/)
})

test('requires confirmation after the password reaches medium strength', async () => {
	const { passwordError } = await loadModule()

	assert.match(passwordError('1234567', '1234567'), /中等/)
	assert.match(passwordError('abc123?', 'abc123!'), /不一致/)
	assert.equal(passwordError('abc123?', 'abc123?'), '')
})
