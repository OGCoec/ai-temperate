const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function loadApp(openMailInspectionSse) {
	let source = fs.readFileSync(
		path.join(__dirname, 'mail-inspection-sse-app.js'),
		'utf8')
	source = source
		.replace(/^import .*ait-sse'\r?\n/m, '')
		.replace(
			'export function openMailInspectionSseApp',
			'function openMailInspectionSseApp')
	source += '\nmodule.exports = { openMailInspectionSseApp }\n'
	const module = { exports: {} }
	new Function(
		'module',
		'exports',
		'openMailInspectionSse',
		source)(module, module.exports, openMailInspectionSse)
	return module.exports.openMailInspectionSseApp
}

test('maps Android structured failures to the shared Error contract', () => {
	let nativeOptions
	const open = loadApp(options => {
		nativeOptions = options
		return { close() {} }
	})
	const errors = []
	let closedCount = 0
	open(
		{ url: 'https://example.test/events', headers: {} },
		{
			onError: error => errors.push(error),
			onClosed: () => {
				closedCount += 1
			}
		})

	const failure = {
		code: 'ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND',
		statusCode: 404,
		message: '原检查任务已过期或不存在，请重新创建检查任务。'
	}
	nativeOptions.onError(failure)
	nativeOptions.onError(failure)
	nativeOptions.onClosed()

	assert.equal(errors.length, 1)
	assert.equal(closedCount, 0)
	assert.equal(errors[0].code, 'ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND')
	assert.equal(errors[0].statusCode, 404)
	assert.equal(
		errors[0].message,
		'原检查任务已过期或不存在，请重新创建检查任务。')
})

test('native Android transport bounds JSON errors and suppresses duplicate close notification', () => {
	const interfaceSource = fs.readFileSync(
		path.join(
			__dirname,
			'../../uni_modules/ait-sse/utssdk/interface.uts'),
		'utf8')
	const androidSource = fs.readFileSync(
		path.join(
			__dirname,
			'../../uni_modules/ait-sse/utssdk/app-android/index.uts'),
		'utf8')

	assert.match(interfaceSource, /export type MailInspectionSseFailure/)
	assert.match(interfaceSource, /statusCode:\s*number/)
	assert.match(
		interfaceSource,
		/onError:\s*\(failure:\s*MailInspectionSseFailure\)\s*=>\s*void/)
	assert.match(androidSource, /MAX_ERROR_BODY_BYTES\s*=\s*16\s*\*\s*1024/)
	assert.match(androidSource, /getErrorStream\(\)/)
	assert.match(androidSource, /JSON\.parse\(errorBody\)\s+as\s+UTSJSONObject/)
	assert.match(androidSource, /callbackCompleted\.compareAndSet\(false,\s*true\)/)
	assert.doesNotMatch(androidSource, /getHeaderField\([^)]*\)\s*\?:/)
	assert.match(
		androidSource,
		/const traceHeader = connection\.getHeaderField\('X-Trace-Id'\)[\s\S]*options\.onOpen\(traceHeader == null \? '' : traceHeader\)/)
	assert.doesNotMatch(
		androidSource,
		/options\.headers\.forEach\(\([^)]*,[^)]*\)\s*=>/)
	assert.match(
		androidSource,
		/options\.headers\.forEach\(\(name:\s*string\)\s*=>[\s\S]*const value = options\.headers\[name\][\s\S]*if \(value == null\) return[\s\S]*setRequestProperty\(name, value\.toString\(\)\)/)
})
