const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const frontendRoot = path.resolve(__dirname, '../..')

function source(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

async function loadSourceModule(relativePath) {
	const encoded = Buffer.from(source(relativePath), 'utf8').toString('base64')
	return import(`data:text/javascript;base64,${encoded}`)
}

test('declares mutually exclusive H5, Android and WeChat Mini Program platform branches', () => {
	const configSource = source('common/auth/config.js')

	assert.match(
		configSource,
		/#ifdef MP-WEIXIN[\s\S]*?return ClientPlatform\.WECHAT_MINI_PROGRAM[\s\S]*?#endif/
	)
	assert.match(
		configSource,
		/#ifdef APP-PLUS[\s\S]*?return resolveClientPlatform\([\s\S]*?#endif/
	)
	assert.match(
		configSource,
		/#ifdef H5[\s\S]*?return ClientPlatform\.H5[\s\S]*?#endif/
	)
	assert.doesNotMatch(
		configSource,
		/#ifndef APP-PLUS[\s\S]*?return ['"]H5['"]/
	)
})

test('classifies browser-cookie and explicit-token transport capabilities independently', async () => {
	const {
		ClientPlatform,
		usesBrowserCookieTransport,
		usesExplicitTokenTransport
	} = await loadSourceModule('common/auth/config.js')

	assert.deepEqual(ClientPlatform, {
		H5: 'H5',
		ANDROID: 'ANDROID',
		WECHAT_MINI_PROGRAM: 'WECHAT_MINI_PROGRAM'
	})
	assert.equal(usesBrowserCookieTransport(ClientPlatform.H5), true)
	assert.equal(usesExplicitTokenTransport(ClientPlatform.H5), false)
	assert.equal(usesBrowserCookieTransport(ClientPlatform.ANDROID), false)
	assert.equal(usesExplicitTokenTransport(ClientPlatform.ANDROID), true)
	assert.equal(usesBrowserCookieTransport(ClientPlatform.WECHAT_MINI_PROGRAM), false)
	assert.equal(usesExplicitTokenTransport(ClientPlatform.WECHAT_MINI_PROGRAM), true)
})

test('creates a lowercase UUIDv4 from WeChat cryptographically secure random bytes', async () => {
	const { createMpWeixinInstallationId } = await loadSourceModule(
		'common/auth/device-installation-mp-weixin.js'
	)
	const wxApi = {
		getRandomValues(options) {
			assert.equal(options.length, 16)
			const bytes = Uint8Array.from({ length: 16 }, (_, index) => index)
			options.success({ randomValues: bytes.buffer })
		}
	}

	const installationId = await createMpWeixinInstallationId(wxApi)

	assert.equal(installationId, '00010203-0405-4607-8809-0a0b0c0d0e0f')
	assert.match(
		installationId,
		/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
	)
})
test('fails closed when the WeChat secure-random API is missing or rejects the request', async () => {
	const { createMpWeixinInstallationId } = await loadSourceModule(
		'common/auth/device-installation-mp-weixin.js'
	)

	await assert.rejects(
		createMpWeixinInstallationId({}),
		error => error?.code === 'DEVICE_INSTALLATION_ID_UNAVAILABLE'
	)
	await assert.rejects(
		createMpWeixinInstallationId({
			getRandomValues({ fail }) {
				fail({ errMsg: 'secure random unavailable' })
			}
		}),
		error => error?.code === 'DEVICE_INSTALLATION_ID_UNAVAILABLE'
	)
	await assert.rejects(
		createMpWeixinInstallationId({
			getRandomValues({ success }) {
				success({ randomValues: new Uint8Array(15).buffer })
			}
		}),
		error => error?.code === 'DEVICE_INSTALLATION_ID_INVALID'
	)
	assert.doesNotMatch(
		source('common/auth/device-installation-mp-weixin.js'),
		/Math\.random/
	)
})

test('initializes the installation ID before session restoration and request headers', () => {
	const deviceInstallation = source('common/auth/device-installation.js')
	const sessionGate = source('pages/launch/session-gate.vue')
	const httpClient = source('common/auth/http-client.js')
	const preAuth = source('common/auth/pre-auth.js')
	const restoreMethod = sessionGate.slice(
		sessionGate.indexOf('async restoreSession()'),
		sessionGate.indexOf('\n\t\t\tgo(url)')
	)

	assert.match(deviceInstallation, /export async function ensureDeviceInstallationId\(\)/)
	assert.match(deviceInstallation, /#ifdef MP-WEIXIN[\s\S]*createMpWeixinInstallationId\(wx\)/)
	assert.match(deviceInstallation, /DEVICE_INSTALLATION_ID_NOT_READY/)
	assert.ok(
		restoreMethod.indexOf('await ensureDeviceInstallationId()')
			< restoreMethod.indexOf('restorePersistedSession(')
	)
	assert.match(httpClient, /export async function publicRequest\([\s\S]*?await ensureDeviceInstallationId\(\)/)
	assert.match(preAuth, /async function bootstrapPreAuth\([\s\S]*?await ensureDeviceInstallationId\(\)/)
})

test('WeChat cold start does not fall through to AndroidKeyStore session access', () => {
	const sessionVault = source('common/auth/session-vault.js')
	const currentSession = sessionVault.slice(
		sessionVault.indexOf('export function currentSession()'),
		sessionVault.indexOf('export function saveSession(')
	)

	assert.match(
		currentSession,
		/platform === ClientPlatform\.WECHAT_MINI_PROGRAM[\s\S]*?emptySessionCredentials\(\)/
	)
	assert.match(
		currentSession,
		/platform === ClientPlatform\.ANDROID[\s\S]*?loadAndroidSessionCredentials\(\)/
	)
})

test('WeChat Mini Program skips WebRTC failure refresh to prevent unnecessary start requests', () => {
	const webrtcSource = source('common/auth/webrtc-verification.js')
	assert.match(
		webrtcSource,
		/export async function refreshWebRtcFailure\(\)\s*\{[\s\S]*?if\s*\(clientPlatform\(\)\s*===\s*['"]WECHAT_MINI_PROGRAM['"]\)\s*\{\s*return null\s*\}/
	)
})
