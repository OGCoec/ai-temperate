const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const frontendRoot = path.resolve(__dirname, '../..')
const sharedAuthRoot = path.join(frontendRoot, 'common/shared-auth')
const productionExtensions = new Set(['.js', '.ts', '.vue'])
const excludedDirectories = new Set(['node_modules', 'unpackage'])

function source(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

function productionFiles(directory) {
	const files = []
	for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
		if (entry.isDirectory() && excludedDirectories.has(entry.name)) continue
		const entryPath = path.join(directory, entry.name)
		if (entry.isDirectory()) {
			files.push(...productionFiles(entryPath))
			continue
		}
		if (entry.name.endsWith('.test.cjs')) continue
		if (productionExtensions.has(path.extname(entry.name))) files.push(entryPath)
	}
	return files
}

test('WeChat Mini Program shared authentication alias stays inside the user frontend boundary', () => {
	const vite = source('vite.config.js')
	assert.match(
		vite,
		/'@shared-auth':\s*fileURLToPath\(new URL\('\.\/common\/shared-auth', import\.meta\.url\)\)/
	)
	assert.doesNotMatch(vite, /\.\.\/shared-frontend\/auth/)
	assert.equal(fs.statSync(sharedAuthRoot).isDirectory(), true)
})

test('all production shared authentication imports resolve to files inside the user frontend', () => {
	const unresolved = []
	for (const file of productionFiles(frontendRoot)) {
		const content = fs.readFileSync(file, 'utf8')
		for (const match of content.matchAll(/(?:from\s+|import\s*)['"]@shared-auth\/([^'"]+)['"]/g)) {
			const target = path.resolve(sharedAuthRoot, match[1])
			if (!target.startsWith(`${sharedAuthRoot}${path.sep}`) || !fs.existsSync(target)) {
				unresolved.push(`${path.relative(frontendRoot, file)} -> ${match[1]}`)
			}
		}
	}
	assert.deepEqual(unresolved, [])
})

test('user frontend production files no longer reference the former external shared directory', () => {
	const offenders = productionFiles(frontendRoot)
		.filter(file => fs.readFileSync(file, 'utf8').includes('../shared-frontend/auth'))
		.map(file => path.relative(frontendRoot, file))
	assert.deepEqual(offenders, [])
})

test('manifest declares the minimal WeChat Mini Program target without disabling URL checks', () => {
	const manifest = JSON.parse(source('manifest.json'))
	assert.deepEqual(manifest['mp-weixin'], {
		appid: 'wx2f435e781c126339',
		usingComponents: true
	})
})

test('WeChat Mini Program code highlighting stays outside browser-only Shiki runtimes', () => {
	const facade = source('common/aichat/ai-code-highlighter.js')
	const miniProgramRuntime = source('common/aichat/ai-code-highlighter-mp-weixin.js')

	assert.match(
		facade,
		/#ifdef MP-WEIXIN[\s\S]*?from '\.\/ai-code-highlighter-mp-weixin\.js'[\s\S]*?#endif/
	)
	assert.match(
		facade,
		/#ifndef MP-WEIXIN[\s\S]*?from '\.\/ai-code-highlighter-shiki\.js'[\s\S]*?#endif/
	)
	assert.doesNotMatch(miniProgramRuntime, /@shikijs|from ['"]shiki(?:\/|['"])/)
	assert.doesNotMatch(
		miniProgramRuntime,
		/\b(?:TransformStream|ReadableStream|WritableStream|WebAssembly)\b/
	)
	assert.match(miniProgramRuntime, /AI_CODE_LANGUAGE_UNSUPPORTED/)
	assert.match(miniProgramRuntime, /ready:\s*false/)
})

test('WeChat Mini Program authentication adapters exclude browser and Android private runtimes', () => {
	const miniProgramInstallation = source('common/auth/device-installation-mp-weixin.js')

	assert.doesNotMatch(
		miniProgramInstallation,
		/\b(?:document|window|AndroidKeyStore|RTCPeerConnection)\b|plus\.android|@shikijs/
	)
})

test('shared control styles avoid universal child selectors unsupported by WXSS', () => {
	const materialStyles = source('common/ui/user-material.scss')
	const riskBlockedPage = source('pages/risk/blocked.vue')
	assert.doesNotMatch(materialStyles, /&\s*>\s*\*/)
	assert.doesNotMatch(riskBlockedPage, /^\s*\*(?:\s*,|::)/m)
})

test('H5 and Android WebRTC implementations remain behind their platform compiler guards', () => {
	const verification = source('common/auth/webrtc-verification.js')
	assert.match(
		verification,
		/#ifdef H5[\s\S]*?from '\.\/webrtc-verification-h5\.js'[\s\S]*?#endif/
	)
	assert.match(
		verification,
		/#ifdef APP-PLUS[\s\S]*?from '\.\/webrtc-verification-android\.js'[\s\S]*?#endif/
	)

	const failedGate = source('common/shared-auth/risk-challenge-failed-gate.vue')
	assert.match(
		failedGate,
		/#ifdef H5[\s\S]*?document\.getElementById\([\s\S]*?#endif/
	)
})
