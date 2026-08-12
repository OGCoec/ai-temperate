const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const modulePath = path.resolve(__dirname, 'http-url.js')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadHttpUrl() {
	const source = fs.readFileSync(modulePath, 'utf8')
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

async function withoutGlobalUrl(callback) {
	const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'URL')
	try {
		Object.defineProperty(globalThis, 'URL', {
			configurable: true,
			writable: true,
			value: undefined
		})
		return await callback()
	} finally {
		if (descriptor) Object.defineProperty(globalThis, 'URL', descriptor)
		else delete globalThis.URL
	}
}

test('normalizes absolute HTTP URLs without a browser URL implementation', async () => {
	await withoutGlobalUrl(async () => {
		const { parseAbsoluteHttpUrl, canonicalHttpUrl } = await loadHttpUrl()
		const parsed = parseAbsoluteHttpUrl(
			'HTTPS://Docs.Oracle.Com:443/java/./api/../Object.html?view=full#wait')

		assert.deepEqual(parsed, {
			href: 'https://docs.oracle.com/java/Object.html?view=full#wait',
			protocol: 'https:',
			hostname: 'docs.oracle.com',
			port: '',
			origin: 'https://docs.oracle.com',
			pathname: '/java/Object.html',
			search: '?view=full',
			hash: '#wait'
		})
		assert.equal(canonicalHttpUrl(parsed.href, { stripFragment: true }),
			'https://docs.oracle.com/java/Object.html?view=full')
	})
})

test('preserves valid non-default ports and canonicalizes an empty path', async () => {
	const { parseAbsoluteHttpUrl } = await loadHttpUrl()
	assert.deepEqual(parseAbsoluteHttpUrl('http://Example.com:8080'), {
		href: 'http://example.com:8080/',
		protocol: 'http:',
		hostname: 'example.com',
		port: '8080',
		origin: 'http://example.com:8080',
		pathname: '/',
		search: '',
		hash: ''
	})
})

test('rejects unsafe, ambiguous, credentialed, and malformed absolute URLs', async () => {
	const { parseAbsoluteHttpUrl, canonicalHttpUrl } = await loadHttpUrl()
	for (const value of [
		'javascript:alert(1)',
		'data:text/html,hello',
		'/relative',
		'https://user:secret@example.com/path',
		'https://example.com:0/path',
		'https://example.com:65536/path',
		'https://example.com\\evil.example/path',
		'https://example.com/%zz',
		'https://example.com/line\nbreak',
		''
	]) {
		assert.equal(parseAbsoluteHttpUrl(value), null, value)
		assert.equal(canonicalHttpUrl(value), '', value)
	}
})

test('keeps the parser independent from browser-only URL globals', () => {
	const source = fs.readFileSync(modulePath, 'utf8')
	assert.doesNotMatch(source, /\bnew\s+URL\s*\(/)
	assert.doesNotMatch(source, /\b(?:window|document|location|navigator)\b/)
})
