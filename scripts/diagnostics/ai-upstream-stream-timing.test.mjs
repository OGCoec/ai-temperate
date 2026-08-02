import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createDiagnosticSseFrameParser } from './ai-upstream-stream-parser.mjs'

const directory = path.dirname(fileURLToPath(import.meta.url))

test('parses split CRLF frames and multiple events in one network chunk', () => {
	const frames = []
	const parser = createDiagnosticSseFrameParser(frame => frames.push(frame))

	parser.push('event: delta\r\ndata: {"choices":[{"delta":{"content":"hel')
	parser.push('lo"}}]}\r\n\r\nevent: completed\ndata: [DONE]\n\n')
	parser.finish()

	assert.deepEqual(frames.map(frame => frame.eventType), [
		'delta', 'completed'
	])
	assert.equal(frames[0].terminal, false)
	assert.equal(frames[1].terminal, true)
	assert.ok(frames[0].dataBytes > 0)
	assert.equal(Object.hasOwn(frames[0], 'data'), false)
})

test('ignores comments and rejects an unbounded frame', () => {
	const frames = []
	const parser = createDiagnosticSseFrameParser(
		frame => frames.push(frame),
		{ maximumFrameBytes: 16 }
	)

	parser.push(': heartbeat\n\n')
	assert.equal(frames.length, 0)
	assert.throws(
		() => parser.push(`data: ${'x'.repeat(512)}`),
		/SSE frame exceeds/i
	)
})

test('timing script never prints credentials or request payloads', () => {
	const source = fs.readFileSync(
		path.join(directory, 'ai-upstream-stream-timing.mjs'),
		'utf8'
	)

	assert.doesNotMatch(source, /console\.log\([^\n]*(apiKey|requestBody|payload)/i)
	assert.doesNotMatch(source, /JSON\.stringify\(requestBody\)/)
	assert.match(source, /stream:\s*true/)
	assert.match(source, /include_usage:\s*true/)
})
