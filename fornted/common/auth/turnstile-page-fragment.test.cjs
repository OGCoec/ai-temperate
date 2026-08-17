const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const pageScript = fs.readFileSync(
	path.resolve(__dirname, '../../../ai-temperate-web/src/main/resources/verification-pages/turnstile-page.js'),
	'utf8'
)
const challenge = 'A'.repeat(38)
const siteKey = '1x00000000000000000000AA'
const channel = 'attempt_m4x8k2p9_0001_native'

function executePage(hash, options = {}) {
	const events = []
	let widgetOptions = null
	const elements = new Map([
		['status', { textContent: '' }],
		['error', { textContent: '' }]
	])
	const location = {
		search: `?challenge=${challenge}&action=login`,
		hash,
		pathname: '/api/auth/turnstile/page',
		href: ''
	}
	const document = {
		head: {
			appendChild(script) {
				events.push({ type: 'sdk-appended', src: script.src })
			}
		},
		getElementById(id) {
			return elements.get(id) || null
		},
		createElement(tagName) {
			return { tagName, remove() {} }
		}
	}
	const window = {
		location,
		history: {
			replaceState(_state, _title, url) {
				events.push({ type: 'fragment-cleared', url })
			}
		}
	}
	if (options.providerReady) {
		window.turnstile = {
			render(_selector, configuredOptions) {
				widgetOptions = configuredOptions
				return 'widget-1'
			},
			remove() {}
		}
	}
	const context = vm.createContext({
		window,
		document,
		URLSearchParams,
		Set,
		String,
		encodeURIComponent,
		decodeURIComponent,
		setTimeout() { return 1 },
		clearTimeout() {}
	})

	vm.runInContext(pageScript, context)
	return {
		events,
		location,
		status: elements.get('status'),
		error: elements.get('error'),
		widgetOptions: () => widgetOptions
	}
}

test('Turnstile page clears the Site Key and channel fragment before appending the provider SDK', () => {
	const result = executePage(`#siteKey=${siteKey}&channel=${channel}`)

	assert.deepEqual(result.events.map((event) => event.type), [
		'fragment-cleared',
		'sdk-appended'
	])
	assert.equal(
		result.events[0].url,
		`/api/auth/turnstile/page?challenge=${challenge}&action=login`
	)
	assert.match(result.events[1].src, /^https:\/\/challenges\.cloudflare\.com\/turnstile\/v0\/api\.js\?/)
	assert.equal(result.location.href, '')
})

test('Turnstile page rejects missing, duplicate, unknown, malformed, or empty fragment fields', () => {
	for (const hash of [
		'',
		`#siteKey=${siteKey}&siteKey=${siteKey}&channel=${channel}`,
		`#siteKey=${siteKey}&channel=${channel}&unknown=value`,
		'#unknown=value',
		`#siteKey=&channel=${channel}`,
		`#siteKey=bad%20value&channel=${channel}`,
		`#siteKey=%&channel=${channel}`,
		`#siteKey=${siteKey}&channel=bad%20channel`
	]) {
		const result = executePage(hash)

		assert.deepEqual(result.events, [], hash)
		assert.match(result.error.textContent, /配置|流程/, hash)
	}
})

test('Turnstile page returns verified, provider error, expired, and timeout results with the current channel', () => {
	for (const [invoke, expectedUrl] of [
		[(widget) => widget.callback('0.sample_token'),
			`aiturnstile://verified?channel=${channel}&token=0.sample_token`],
		[(widget) => widget['error-callback']('300030'),
			`aiturnstile://error?channel=${channel}&code=300030`],
		[(widget) => widget['expired-callback'](),
			`aiturnstile://expired?channel=${channel}`],
		[(widget) => widget['timeout-callback'](),
			`aiturnstile://timeout?channel=${channel}`]
	]) {
		const result = executePage(`#siteKey=${siteKey}&channel=${channel}`, { providerReady: true })
		const widget = result.widgetOptions()
		assert.ok(widget)
		assert.equal(widget.retry, 'auto')
		assert.equal(widget['retry-interval'], 8000)
		assert.equal(widget.size, 'normal')
		invoke(widget)
		assert.equal(result.location.href, expectedUrl)
	}
})

test('Turnstile page never performs a fallback configuration request', () => {
	assert.doesNotMatch(pageScript, /fetch\s*\(/)
	assert.doesNotMatch(pageScript, /\/api\/auth\/turnstile\/config/)
})
