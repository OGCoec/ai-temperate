import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { loopbackResponsesUrl } from './ai-responses-web-search-probe.mjs'

const directory = path.dirname(fileURLToPath(import.meta.url))

test('probe only accepts loopback Responses endpoints', () => {
	assert.equal(loopbackResponsesUrl('http://127.0.0.1:8317'),
		'http://127.0.0.1:8317/v1/responses')
	assert.throws(() => loopbackResponsesUrl('https://example.com'), /loopback/i)
	assert.throws(() => loopbackResponsesUrl(
		'http://user:secret@127.0.0.1:8317'), /loopback/i)
})

test('probe never logs credentials, fixed prompt, full answer or source domains', () => {
	const source = fs.readFileSync(path.join(directory,
		'ai-responses-web-search-probe.mjs'), 'utf8')

	assert.doesNotMatch(source, /console\.log/)
	assert.doesNotMatch(source, /process\.stdout\.write\([^\n]*(apiKey|request|frame\.data)/i)
	assert.match(source, /sourceDomainCount/)
	assert.match(source, /eventTypes/)
	assert.match(source, /max_output_tokens:\s*128/)
	assert.match(source, /AbortSignal\.timeout\(120000\)/)
})
