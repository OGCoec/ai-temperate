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

test('parses split and joined SSE frames with CRLF', () => {
	const events = []
	const parser = loadParser()({ onEvent: event => events.push(event) })
	parser.push(': heartbeat\r\nevent: snapshot-meta\r\ndata: {"revision":')
	parser.push('7,"data":{"status":"RUNNING"}}\r\n\r\nevent: result\n')
	parser.push('id: 8\ndata: {"revision":8,\ndata: "data":{"lineNumber":1}}\n\n')
	parser.finish()

	assert.deepEqual(events, [
		{
			type: 'snapshot-meta',
			id: '',
			data: { revision: 7, data: { status: 'RUNNING' } }
		},
		{
			type: 'result',
			id: '8',
			data: { revision: 8, data: { lineNumber: 1 } }
		}
	])
})

test('rejects malformed JSON instead of silently dropping a revision', () => {
	const parser = loadParser()()
	assert.throws(
		() => parser.push('event: progress\ndata: {\n\n'),
		error => error.code === 'MAIL_INSPECTION_SSE_PROTOCOL_INVALID')
})
