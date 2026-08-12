const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const modulePath = path.resolve(__dirname, 'query-string.js')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadQueryString() {
	const source = fs.readFileSync(modulePath, 'utf8')
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

async function withoutGlobal(name, callback) {
	const descriptor = Object.getOwnPropertyDescriptor(globalThis, name)
	try {
		Object.defineProperty(globalThis, name, {
			configurable: true,
			writable: true,
			value: undefined
		})
		return await callback()
	} finally {
		if (descriptor) Object.defineProperty(globalThis, name, descriptor)
		else delete globalThis[name]
	}
}

test('builds ordered scalar query parameters without URLSearchParams', async () => {
	await withoutGlobal('URLSearchParams', async () => {
		const { buildQueryString } = await loadQueryString()
		assert.equal(buildQueryString([]), '')
		assert.equal(buildQueryString([['pageSize', 20]]), 'pageSize=20')
		assert.equal(
			buildQueryString([
				['pageSize', 20],
				['cursor', 'example'],
				['active', false]
			]),
			'pageSize=20&cursor=example&active=false'
		)
	})
})

test('encodes keys and values without changing their order', async () => {
	const { buildQueryString } = await loadQueryString()
	assert.equal(
		buildQueryString([
			['中文 key', '值 &+?#%'],
			['empty', '']
		]),
		'%E4%B8%AD%E6%96%87%20key=%E5%80%BC%20%26%2B%3F%23%25&empty='
	)
})

test('rejects invalid entries, keys, and non-scalar values', async () => {
	const { buildQueryString } = await loadQueryString()
	for (const entries of [
		null,
		{},
		[null],
		[['only-key']],
		[['key', 'value', 'extra']],
		[['', 'value']],
		[[1, 'value']],
		[['key', null]],
		[['key', undefined]],
		[['key', {}]],
		[['key', []]],
		[['key', () => {}]],
		[['key', Symbol('value')]]
	]) {
		assert.throws(() => buildQueryString(entries), TypeError)
	}
})

test('keeps the platform helper free of browser-only globals', () => {
	const source = fs.readFileSync(modulePath, 'utf8')
	assert.doesNotMatch(
		source,
		/\b(?:window|document|URLSearchParams|Intl|location|navigator)\b/
	)
})
