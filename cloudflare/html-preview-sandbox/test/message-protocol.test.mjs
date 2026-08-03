import assert from 'node:assert/strict'
import test from 'node:test'
import {
	AI_HTML_PREVIEW_MESSAGE_SOURCE,
	AI_HTML_PREVIEW_PROTOCOL_VERSION,
	createShellMessage,
	isAllowedParentOrigin,
	isHostMessage,
	parseShellLocationHash,
	sanitizeRuntimeMessage
} from '../public/message-protocol.js'

const channelId = '000102030405060708090a0b0c0d0e0f'

test('accepts only exact configured main-app origins', () => {
	assert.equal(isAllowedParentOrigin('https://niko000o.site'), true)
	assert.equal(isAllowedParentOrigin('https://dev.niko000o.site'), true)
	assert.equal(isAllowedParentOrigin('https://localhost:3000'), true)
	assert.equal(isAllowedParentOrigin('https://127.0.0.1:3000'), true)
	assert.equal(isAllowedParentOrigin('https://niko000o.site.attacker.example'), false)
	assert.equal(isAllowedParentOrigin('http://localhost:3000'), false)
})

test('parses channel and parent origin only from a valid location fragment', () => {
	const value = parseShellLocationHash(
		'#channelId=' + channelId + '&parentOrigin=https%3A%2F%2Fniko000o.site'
	)
	assert.deepEqual(value, { channelId, parentOrigin: 'https://niko000o.site' })
	assert.deepEqual(parseShellLocationHash('#channelId=short&parentOrigin=https%3A%2F%2Fniko000o.site'), {
		channelId: '',
		parentOrigin: ''
	})
	assert.deepEqual(parseShellLocationHash(
		'#channelId=' + channelId + '&parentOrigin=https%3A%2F%2Fevil.example'
	), { channelId: '', parentOrigin: '' })
})

test('validates render and dispose messages for the active channel', () => {
	const render = {
		source: AI_HTML_PREVIEW_MESSAGE_SOURCE,
		version: AI_HTML_PREVIEW_PROTOCOL_VERSION,
		channelId,
		type: 'render',
		renderId: '101112131415161718191a1b1c1d1e1f',
		html: '<p>ok</p>',
		theme: 'dark'
	}
	assert.equal(isHostMessage(render, channelId), true)
	assert.equal(isHostMessage({ ...render, channelId: 'f'.repeat(32) }, channelId), false)
	assert.equal(isHostMessage({ ...render, version: 2 }, channelId), false)
	assert.equal(isHostMessage({ ...render, type: 'navigate-top' }, channelId), false)
	assert.equal(isHostMessage({ ...render, html: 'x'.repeat(1024 * 1024 + 1) }, channelId), false)
	assert.equal(isHostMessage({ ...render, type: 'dispose', html: undefined }, channelId), true)
})

test('creates shell messages and sanitizes runtime payloads', () => {
	assert.deepEqual(createShellMessage(channelId, 'ready'), {
		source: AI_HTML_PREVIEW_MESSAGE_SOURCE,
		version: AI_HTML_PREVIEW_PROTOCOL_VERSION,
		channelId,
		type: 'ready'
	})
	const error = sanitizeRuntimeMessage({
		type: 'runtime-error',
		renderId: '101112131415161718191a1b1c1d1e1f',
		message: 'https://cdn.example/app.js?token=secret#private failed',
		line: 9,
		column: 14,
		stack: 'must not cross the boundary'
	})
	assert.equal(error.message.includes('token=secret'), false)
	assert.equal('stack' in error, false)
	assert.equal(error.line, 9)
})
