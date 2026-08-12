const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function read(relativePath) {
	return fs.readFileSync(path.join(__dirname, '..', '..', relativePath), 'utf8')
}

test('Android native SSE keeps callbacks alive and has no fixed read timeout', () => {
	const source = read('uni_modules/ait-sse/utssdk/app-android/index.uts')

	assert.match(source, /@UTSJS\.keepAlive\s+export function openSseRequest/)
	assert.match(source, /setConnectTimeout\(15000\)/)
	assert.match(source, /setReadTimeout\(0\)/)
	assert.doesNotMatch(source, /setReadTimeout\(60000\)/)
	assert.match(source, /InputStreamReader\([\s\S]*Charsets\.UTF_8/)
	assert.match(source, /length > 0[\s\S]*options\.onChunk/)
	assert.match(source, /hasRequestBody[\s\S]*setDoOutput\(hasRequestBody\)/)
	assert.match(source, /if \(hasRequestBody\)[\s\S]*OutputStreamWriter/)
})

test('Android native SSE reports bounded stages and never logs request or event content', () => {
	const source = read('uni_modules/ait-sse/utssdk/app-android/index.uts')
	const contract = read('uni_modules/ait-sse/utssdk/interface.uts')

	for (const stage of [
		'CONNECT', 'REQUEST_BODY', 'RESPONSE_HEADERS',
		'RESPONSE_BODY', 'JS_CALLBACK', 'CLOSE'
	]) assert.match(source, new RegExp(`'${stage}'`))
	assert.match(contract, /export type AitSseDiagnostic/)
	assert.match(contract, /onDiagnostic:\s*\(diagnostic: AitSseDiagnostic\) => void/)
	assert.match(contract, /exceptionType:\s*string/)
	assert.match(contract, /closedByCaller:\s*boolean/)
	assert.match(contract, /retryable:\s*boolean/)
	assert.match(source, /\[ait-sse\]/)
	assert.doesNotMatch(source, /console\.log\([^)]*options\.body/)
	assert.doesNotMatch(source, /console\.log\([^)]*chunk/)
	assert.doesNotMatch(source, /printStackTrace/)
})

test('Android native SSE distinguishes clean EOF, caller close and callback failure', () => {
	const source = read('uni_modules/ait-sse/utssdk/app-android/index.uts')

	assert.match(source, /emitDiagnostic\('EOF', 'RESPONSE_BODY'\)/)
	assert.match(source, /emitDiagnostic\('CLOSED', 'CLOSE'\)/)
	assert.match(source, /AI_CONVERSATION_SSE_ANDROID_CALLBACK/)
	assert.match(source, /closedByCaller/)
	assert.match(source, /completed\.compareAndSet\(false, true\)/)
})
