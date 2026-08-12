const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

async function loadProtocol() {
	return loadEsmModule(path.join(__dirname, 'ai-html-preview-protocol.js'))
}

async function withoutUrlSearchParams(callback) {
	const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'URLSearchParams')
	try {
		Object.defineProperty(globalThis, 'URLSearchParams', {
			configurable: true,
			writable: true,
			value: undefined
		})
		return await callback()
	} finally {
		if (descriptor) Object.defineProperty(globalThis, 'URLSearchParams', descriptor)
		else delete globalThis.URLSearchParams
	}
}

function deterministicCrypto() {
	return {
		getRandomValues(bytes) {
			for (let index = 0; index < bytes.length; index += 1) bytes[index] = index
			return bytes
		}
	}
}

test('creates a 128-bit channel identifier using Web Crypto', async () => {
	const { createAiHtmlPreviewSecureId, isAiHtmlPreviewSecureId } = await loadProtocol()
	const value = createAiHtmlPreviewSecureId(deterministicCrypto())

	assert.equal(value, '000102030405060708090a0b0c0d0e0f')
	assert.equal(isAiHtmlPreviewSecureId(value), true)
	assert.throws(() => createAiHtmlPreviewSecureId({}), /安全随机数/)
})

test('builds a fragment-only iframe URL with exact parent and preview origins', async () => {
	await withoutUrlSearchParams(async () => {
		const { createAiHtmlPreviewFrameUrl } = await loadProtocol()
		const channelId = '000102030405060708090a0b0c0d0e0f'
		const value = createAiHtmlPreviewFrameUrl({
			previewOrigin: 'https://ai-temperate-html-preview.pages.dev',
			parentOrigin: 'https://niko000o.site',
			channelId
		})

		assert.equal(value.startsWith('https://ai-temperate-html-preview.pages.dev/#'), true)
		assert.equal(value.includes('channelId=' + channelId), true)
		assert.equal(value.includes('parentOrigin=https%3A%2F%2Fniko000o.site'), true)
		assert.throws(() => createAiHtmlPreviewFrameUrl({
			previewOrigin: 'https://ai-temperate-html-preview.pages.dev/path',
			parentOrigin: 'https://niko000o.site',
			channelId
		}), /精确的 HTTPS Origin/)
	})
})

test('enforces the HTML byte limit before creating a render message', async () => {
	const {
		AI_HTML_PREVIEW_MAX_HTML_BYTES,
		createAiHtmlPreviewRenderMessage
	} = await loadProtocol()
	const channelId = '000102030405060708090a0b0c0d0e0f'
	const renderId = '101112131415161718191a1b1c1d1e1f'

	assert.equal(createAiHtmlPreviewRenderMessage({ channelId, renderId, html: '<p>ok</p>' }).type, 'render')
	assert.throws(() => createAiHtmlPreviewRenderMessage({
		channelId,
		renderId,
		html: '水'.repeat(AI_HTML_PREVIEW_MAX_HTML_BYTES)
	}), /1 MiB/)
})

test('accepts only known shell messages for the active channel and version', async () => {
	const {
		AI_HTML_PREVIEW_MESSAGE_SOURCE,
		AI_HTML_PREVIEW_PROTOCOL_VERSION,
		isAiHtmlPreviewShellMessage
	} = await loadProtocol()
	const channelId = '000102030405060708090a0b0c0d0e0f'
	const message = {
		source: AI_HTML_PREVIEW_MESSAGE_SOURCE,
		version: AI_HTML_PREVIEW_PROTOCOL_VERSION,
		channelId,
		type: 'ready'
	}

	assert.equal(isAiHtmlPreviewShellMessage(message, channelId), true)
	assert.equal(isAiHtmlPreviewShellMessage({ ...message, channelId: 'f'.repeat(32) }, channelId), false)
	assert.equal(isAiHtmlPreviewShellMessage({ ...message, version: 2 }, channelId), false)
	assert.equal(isAiHtmlPreviewShellMessage({ ...message, source: 'other' }, channelId), false)
	assert.equal(isAiHtmlPreviewShellMessage({ ...message, type: 'execute-anything' }, channelId), false)
})

test('sanitizes runtime error URLs and truncates oversized messages', async () => {
	const {
		AI_HTML_PREVIEW_MAX_ERROR_CHARACTERS,
		sanitizeAiHtmlPreviewRuntimeError
	} = await loadProtocol()
	const value = sanitizeAiHtmlPreviewRuntimeError(
		'https://cdn.example/app.js?token=secret#private ' + 'x'.repeat(AI_HTML_PREVIEW_MAX_ERROR_CHARACTERS + 50)
	)

	assert.equal(value.includes('token=secret'), false)
	assert.equal(value.length, AI_HTML_PREVIEW_MAX_ERROR_CHARACTERS)
})
