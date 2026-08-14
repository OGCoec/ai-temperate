const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('../aichat/ai-code-test-loader.cjs')

test('defaults to full motion and ignores the retired manual preference storage', async () => {
	const previousWindow = global.window
	const previousPlus = global.plus
	const previousUni = global.uni
	try {
		global.window = {
			matchMedia: () => ({ matches: false })
		}
		delete global.plus
		global.uni = {
			getStorageSync() {
				throw new Error('retired motion preference storage must not be read')
			}
		}

		const api = await loadEsmModule(path.join(__dirname, 'ai-motion-preference.js'))
		const snapshots = []
		const controller = api.createAiMotionPreferenceController(snapshot => {
			snapshots.push(snapshot)
		})

		assert.deepEqual(snapshots, [{ systemReduced: false, reduced: false }])
		assert.equal(controller.isReduced(), false)
		assert.equal(controller.toggleManualReduce, undefined)
		assert.equal(controller.setPreference, undefined)
		controller.destroy()
	} finally {
		if (previousWindow === undefined) delete global.window
		else global.window = previousWindow
		if (previousPlus === undefined) delete global.plus
		else global.plus = previousPlus
		if (previousUni === undefined) delete global.uni
		else global.uni = previousUni
	}
})

test('uses the browser reduced-motion preference without disabling the controller', async () => {
	const previousWindow = global.window
	const previousPlus = global.plus
	try {
		global.window = {
			matchMedia: () => ({ matches: true })
		}
		delete global.plus

		const api = await loadEsmModule(path.join(__dirname, 'ai-motion-preference.js'))
		const snapshots = []
		const controller = api.createAiMotionPreferenceController(snapshot => {
			snapshots.push(snapshot)
		})

		assert.deepEqual(snapshots, [{ systemReduced: true, reduced: true }])
		assert.equal(controller.isReduced(), true)
		controller.destroy()
	} finally {
		if (previousWindow === undefined) delete global.window
		else global.window = previousWindow
		if (previousPlus === undefined) delete global.plus
		else global.plus = previousPlus
	}
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
