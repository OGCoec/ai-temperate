const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function loadParser() {
	let source = fs.readFileSync(
		path.join(__dirname, 'mail-inspection-sse-parser.js'),
		'utf8')
	source = source.replace(
		'export function createMailInspectionSseParser',
		'function createMailInspectionSseParser')
	source += '\nmodule.exports = { createMailInspectionSseParser }\n'
	const module = { exports: {} }
	new Function('module', 'exports', source)(module, module.exports)
	return module.exports.createMailInspectionSseParser
}

function loadH5(createMailInspectionSseParser) {
	let source = fs.readFileSync(
		path.join(__dirname, 'mail-inspection-sse-h5.js'),
		'utf8')
	source = source
		.replace(/^import .*mail-inspection-sse-parser\.js'\r?\n/m, '')
		.replace('export function openMailInspectionSseH5', 'function openMailInspectionSseH5')
	source += '\nmodule.exports = { openMailInspectionSseH5 }\n'
	const module = { exports: {} }
	new Function(
		'module',
		'exports',
		'createMailInspectionSseParser',
		source)(module, module.exports, createMailInspectionSseParser)
	return module.exports.openMailInspectionSseH5
}

function responseBody(chunks) {
	let index = 0
	return {
		getReader() {
			return {
				async read() {
					return index < chunks.length
						? { done: false, value: chunks[index++] }
						: { done: true }
				},
				async cancel() {},
				releaseLock() {}
			}
		}
	}
}

test('keeps a multibyte UTF-8 character intact across response chunks', async () => {
	const open = loadH5(loadParser())
	const bytes = new TextEncoder().encode(
		'event: status\nid: 3\ndata: {"revision":3,"data":{"status":"运行"}}\n\n')
	const split = bytes.indexOf(0xe8) + 1
	let index = 0
	const chunks = [bytes.slice(0, split), bytes.slice(split)]
	const originalFetch = global.fetch
	global.fetch = async () => ({
		ok: true,
		status: 200,
		headers: new Headers({ 'Content-Type': 'text/event-stream' }),
		body: {
			getReader() {
				return {
					async read() {
						return index < chunks.length
							? { done: false, value: chunks[index++] }
							: { done: true }
					},
					releaseLock() {}
				}
			}
		}
	})
	const events = []
	const connection = open(
		{ url: 'https://example.test/events', headers: {} },
		{ onEvent: event => events.push(event) })
	await assert.rejects(connection.completed, error =>
		error.code === 'MAIL_INSPECTION_SSE_CLOSED')
	assert.equal(events[0].data.data.status, '运行')
	global.fetch = originalFetch
})

test('parses a bounded JSON error without consuming a successful event stream', async () => {
	const open = loadH5(loadParser())
	const originalFetch = global.fetch
	const body = new TextEncoder().encode(JSON.stringify({
		code: 'ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND',
		message: '原检查任务已过期或不存在，请重新创建检查任务。'
	}))
	global.fetch = async () => ({
		ok: false,
		status: 404,
		headers: new Headers({ 'Content-Type': 'application/json' }),
		body: responseBody([body])
	})
	try {
		const connection = open(
			{ url: 'https://example.test/events', headers: {} })
		await assert.rejects(connection.completed, error => {
			assert.equal(error.code, 'ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND')
			assert.equal(error.statusCode, 404)
			assert.equal(
				error.message,
				'原检查任务已过期或不存在，请重新创建检查任务。')
			return true
		})
	} finally {
		global.fetch = originalFetch
	}
})

test('falls back to the stable missing-job code for invalid or oversized 404 bodies', async () => {
	const open = loadH5(loadParser())
	const originalFetch = global.fetch
	const bodies = [
		new TextEncoder().encode('{invalid-json'),
		new Uint8Array(16 * 1024 + 1)
	]
	try {
		for (const body of bodies) {
			global.fetch = async () => ({
				ok: false,
				status: 404,
				headers: new Headers({ 'Content-Type': 'application/json' }),
				body: responseBody([body])
			})
			const connection = open(
				{ url: 'https://example.test/events', headers: {} })
			await assert.rejects(connection.completed, error => {
				assert.equal(error.code, 'ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND')
				assert.equal(error.statusCode, 404)
				return true
			})
		}
	} finally {
		global.fetch = originalFetch
	}
})

test('event stream handshake explicitly accepts SSE and JSON', () => {
	const source = fs.readFileSync(
		path.join(__dirname, 'admin-http.js'),
		'utf8')
	assert.match(
		source,
		/Accept:\s*'text\/event-stream,\s*application\/json;q=0\.9'/)
})

test('propagates cancellation through AbortController', () => {
	const source = fs.readFileSync(
		path.join(__dirname, 'mail-inspection-sse-h5.js'),
		'utf8')
	assert.match(source, /signal:\s*abortController\.signal/)
	assert.match(source, /abortController\.abort\(\)/)
	assert.doesNotMatch(source, /\.text\(\)|\.json\(\)/)
})
