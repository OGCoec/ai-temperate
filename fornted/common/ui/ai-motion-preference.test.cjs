const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('../aichat/ai-code-test-loader.cjs')

test('normalizes unknown motion values to system following', async () => {
	const api = await loadEsmModule(path.join(__dirname, 'ai-motion-preference.js'))
	assert.equal(api.normalizeAiMotionPreference('anything'), 'SYSTEM')
	assert.equal(api.normalizeAiMotionPreference('REDUCE'), 'REDUCE')
})

test('manual reduce remains enabled even when the system allows animation', async () => {
	const api = await loadEsmModule(path.join(__dirname, 'ai-motion-preference.js'))
	assert.equal(api.resolveAiMotionReduced({ preference: 'REDUCE', systemReduced: false }), true)
})

test('uses the Android animation scale even when the WebView media query is available', async () => {
	const previousWindow = global.window
	const previousPlus = global.plus
	try {
		global.window = {
			matchMedia: () => ({ matches: false })
		}
		global.plus = {
			android: {
				importClass: () => ({
					getFloat: () => 0
				}),
				runtimeMainActivity: () => ({
					getContentResolver: () => ({})
				})
			}
		}

		const api = await loadEsmModule(path.join(__dirname, 'ai-motion-preference.js'))
		assert.equal(api.readAiSystemReducedMotion(), true)
	} finally {
		if (previousWindow === undefined) delete global.window
		else global.window = previousWindow
		if (previousPlus === undefined) delete global.plus
		else global.plus = previousPlus
	}
})
