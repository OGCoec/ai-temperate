const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const source = fs.readFileSync(path.join(__dirname, 'index.vue'), 'utf8')
const adminHttp = fs.readFileSync(
	path.resolve(__dirname, '..', '..', 'common', 'admin', 'admin-http.js'),
	'utf8'
)
const adminIndex = fs.readFileSync(
	path.resolve(__dirname, '..', '..', 'index.html'),
	'utf8'
)
const navigationModulePath = path.resolve(
	__dirname,
	'..',
	'..',
	'common',
	'admin',
	'admin-page-navigation.js'
)

async function loadNavigationModule() {
	assert.ok(
		fs.existsSync(navigationModulePath),
		'icon library back navigation must have a testable shared implementation'
	)
	const navigationSource = fs.readFileSync(navigationModulePath, 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(navigationSource).toString('base64')}`)
}

function cspSources(directiveName) {
	const policy = adminIndex.match(
		/http-equiv="Content-Security-Policy"\s+content="([^"]+)"/i
	)?.[1]
	assert.ok(policy, 'administrator index must declare a CSP policy')

	const directive = policy
		.split(';')
		.map(part => part.trim())
		.find(part => part.startsWith(`${directiveName} `))
	assert.ok(directive, `administrator CSP must declare ${directiveName}`)
	return directive.split(/\s+/).slice(1)
}

test('icon picker advertises every backend-supported format and keeps original bytes', () => {
	assert.match(source, /const ICON_FILE_EXTENSIONS = \['\.png', '\.jpg', '\.jpeg', '\.webp', '\.gif', '\.ico', '\.avif', '\.svg'\]/)
	assert.match(source, /extension: ICON_FILE_EXTENSIONS/)
	assert.match(source, /sizeType: \['original'\]/)
	assert.match(source, /MAX_FILE_BYTES = 2 \* 1024 \* 1024/)
})

test('icon library shows dedicated validation messages and a preview failure fallback', () => {
	assert.match(source, /AI_MODEL_ICON_IMAGE_FORMAT_UNSUPPORTED/)
	assert.match(source, /AI_MODEL_ICON_IMAGE_UNSAFE/)
	assert.match(source, /AI_MODEL_ICON_DECODER_UNAVAILABLE/)
	assert.match(source, /@error="markPreviewFailure\(icon\.publicId\)"/)
	assert.match(source, /preview-fallback/)
})

test('icon library displays backend exception chain diagnostics below the stable message', () => {
	for (const field of [
		'exceptionType',
		'exceptionMessage',
		'rootCauseType',
		'rootCauseMessage'
	]) {
		assert.match(adminHttp, new RegExp(`error\\.${field}\\s*=\\s*body\\.${field}`))
		assert.match(source, new RegExp(field))
	}
	assert.match(source, /message-diagnostics/)
	assert.match(source, /errorDiagnostics/)
})

test('icon library back navigation preserves a real parent and falls back from empty or duplicate stacks', async () => {
	const { leaveAdminChildPage } = await loadNavigationModule()
	const navigateBackCalls = []
	const reLaunchCalls = []
	const dependencies = pages => ({
		getPages: () => pages,
		navigateBack: options => navigateBackCalls.push(options),
		reLaunch: options => reLaunchCalls.push(options)
	})

	assert.equal(leaveAdminChildPage(dependencies([
		{ route: 'pages/index/index' },
		{ route: 'pages/ai-model-icons/index' }
	])), 'BACK')
	assert.equal(navigateBackCalls.length, 1)
	assert.equal(navigateBackCalls[0].delta, 1)
	assert.equal(reLaunchCalls.length, 0)

	assert.equal(leaveAdminChildPage(dependencies([
		{ route: 'pages/ai-model-icons/index' }
	])), 'DASHBOARD')
	assert.deepEqual(reLaunchCalls.pop(), { url: '/pages/index/index' })

	assert.equal(leaveAdminChildPage(dependencies([
		{ route: 'pages/ai-model-icons/index' },
		{ route: '/pages/ai-model-icons/index' }
	])), 'DASHBOARD')
	assert.deepEqual(reLaunchCalls.pop(), { url: '/pages/index/index' })

	navigateBackCalls[0].fail()
	assert.deepEqual(reLaunchCalls.pop(), { url: '/pages/index/index' })
})

test('icon library delegates its return button to guarded admin child-page navigation', () => {
	assert.match(source, /import\s+\{\s*leaveAdminChildPage\s*\}/)
	assert.match(source, /goBack\(\)\s*\{[\s\S]*leaveAdminChildPage\(\)/)
	assert.doesNotMatch(source, /goBack\(\)\s*\{[\s\S]{0,120}uni\.navigateBack/)
})

test('administrator CSP permits HTTPS icon previews without broadening active content sources', () => {
	assert.deepEqual(cspSources('img-src'), ["'self'", 'data:', 'https:'])
	assert.deepEqual(cspSources('script-src'), [
		"'self'",
		'https://hcaptcha.com',
		'https://*.hcaptcha.com',
		'https://static.cloudflareinsights.com'
	])
	assert.deepEqual(cspSources('frame-src'), [
		'https://hcaptcha.com',
		'https://*.hcaptcha.com'
	])
	assert.deepEqual(cspSources('connect-src'), [
		"'self'",
		'https://localhost:6655',
		'https://127.0.0.1:6655',
		'wss://localhost:3001',
		'wss://127.0.0.1:3001',
		'https://hcaptcha.com',
		'https://*.hcaptcha.com'
	])
	assert.deepEqual(cspSources('style-src'), [
		"'self'",
		"'unsafe-inline'",
		'https://hcaptcha.com',
		'https://*.hcaptcha.com'
	])
	assert.doesNotMatch(cspSources('img-src').join(' '), /(?:^|\s)(?:http:|\*)(?:\s|$)/)
})
