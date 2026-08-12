const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadModule(defaultBaseUrl = 'https://niko000o.site') {
	let source = fs.readFileSync(path.join(__dirname, 'voice-ticket-api.js'), 'utf8')
	source = source.replace(
		"import { AUTH_API_BASE_URL } from '@/common/auth/config.js'",
		`const AUTH_API_BASE_URL = ${JSON.stringify(defaultBaseUrl)}`)
	source = source.replace(
		"import { authorizedRequest } from '@/common/auth/http-client.js'",
		"const authorizedRequest = async () => { throw new Error('not used') }")
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

test('builds the Android voice socket URL without the browser URL global', async () => {
	const module = await loadModule()
	const previousUrl = globalThis.URL

	try {
		globalThis.URL = undefined
		assert.equal(
			module.voiceWebSocketUrl(),
			'wss://niko000o.site/ws/voice')
	} finally {
		globalThis.URL = previousUrl
	}
})

test('preserves a secure localhost port and removes one trailing slash', async () => {
	const module = await loadModule()

	assert.equal(
		module.voiceWebSocketUrl('https://localhost:6655/'),
		'wss://localhost:6655/ws/voice')
})

test('keeps the secure H5 same-origin fallback when the API base is empty', async () => {
	const module = await loadModule('')
	const previousWindow = globalThis.window

	try {
		globalThis.window = {
			location: {
				protocol: 'https:',
				host: 'niko000o.site'
			}
		}
		assert.equal(
			module.voiceWebSocketUrl(),
			'wss://niko000o.site/ws/voice')
	} finally {
		if (previousWindow === undefined) delete globalThis.window
		else globalThis.window = previousWindow
	}
})

test('rejects every API base that is not a plain secure origin', async () => {
	const module = await loadModule()
	const invalidOrigins = [
		'http://niko000o.site',
		'https://user@niko000o.site',
		'https://niko000o.site/api',
		'https://niko000o.site?mode=voice',
		'https://niko000o.site#voice',
		'https://',
		'https://niko000o.site:0',
		'https://niko000o.site:voice',
		'https://niko000o.site:65536',
		'https:\\niko000o.site'
	]

	for (const origin of invalidOrigins) {
		assert.throws(
			() => module.voiceWebSocketUrl(origin),
			/HTTPS Origin/,
			`must reject ${origin}`)
	}
})
