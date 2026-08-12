const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const source = file => fs.readFileSync(path.join(root, file), 'utf8')
let coordinatorModuleSequence = 0

async function loadPolicy() {
	const policy = source('common/auth/android-edge-challenge-policy.js')
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(policy).toString('base64')}`
	return import(sourceUrl)
}

async function loadCoordinator() {
	const policy = source('common/auth/android-edge-challenge-policy.js')
	const policyUrl = dataModuleUrl(policy)
	const configUrl = dataModuleUrl([
		"export const AUTH_API_BASE_URL = 'https://niko000o.site'",
		"export function clientPlatform() { return 'ANDROID' }"
	].join('\n'))
	const coordinator = source('common/auth/android-edge-challenge.js')
		.replace("'./config.js'", JSON.stringify(configUrl))
		.replace("'./android-edge-challenge-policy.js'", JSON.stringify(policyUrl))
	coordinatorModuleSequence += 1
	return import(`${dataModuleUrl(coordinator)}#${coordinatorModuleSequence}`)
}

function dataModuleUrl(value) {
	return `data:text/javascript;base64,${Buffer.from(value).toString('base64')}`
}

function edgeChallenge() {
	const error = new Error('challenge')
	error.code = 'EDGE_CHALLENGE'
	error.cfMitigated = 'challenge'
	error.cfRay = 'test-ray-ord'
	return error
}

function flushMicrotasks() {
	return new Promise(resolve => setImmediate(resolve))
}

function installAndroidRuntime(options = {}) {
	const previous = {
		plus: global.plus,
		uni: global.uni,
		setTimeout: global.setTimeout,
		clearTimeout: global.clearTimeout
	}
	const calls = []
	const listeners = new Map()
	const requests = []
	const timers = new Map()
	const statusResponses = [...(options.statusResponses || [])]
	let cookieHeader = String(options.cookieHeader || '')
	let overrideOptions = null
	let overrideHandler = null
	let timerSequence = 0
	let closeCount = 0

	const webview = {
		overrideUrlLoading(value, handler) {
			calls.push('overrideUrlLoading')
			overrideOptions = value
			overrideHandler = handler
		},
		addEventListener(name, handler) {
			calls.push(`listen:${name}`)
			listeners.set(name, handler)
		},
		loadURL(url) {
			calls.push(`loadURL:${url}`)
		},
		show() {
			calls.push('show')
		},
		close() {
			closeCount += 1
			calls.push('close')
			listeners.get('close')?.()
		}
	}
	const cookieManager = {}
	global.plus = {
		android: {
			importClass() { return {} },
			invoke(_target, method) {
				if (method === 'getInstance') return cookieManager
				if (method === 'getCookie') return cookieHeader
				if (method === 'flush') calls.push('flushCookies')
				return undefined
			}
		},
		webview: {
			create(url) {
				calls.push(`create:${url}`)
				return webview
			}
		}
	}
	global.uni = {
		request(requestOptions) {
			const response = statusResponses.shift() || { statusCode: 204 }
			const request = {
				options: requestOptions,
				aborted: false,
				abort() { this.aborted = true }
			}
			requests.push(request)
			if (response.pending !== true) {
				queueMicrotask(() => {
					if (request.aborted) return
					if (response.fail === true) requestOptions.fail?.({ errMsg: 'network failure' })
					else requestOptions.success?.({ statusCode: response.statusCode })
					requestOptions.complete?.()
				})
			}
			return request
		},
		showToast(toastOptions) {
			calls.push(`toast:${toastOptions.title}`)
		}
	}
	global.setTimeout = (callback, delay) => {
		timerSequence += 1
		timers.set(timerSequence, { callback, delay })
		return timerSequence
	}
	global.clearTimeout = handle => {
		timers.delete(handle)
	}

	return {
		calls,
		requests,
		closeCount: () => closeCount,
		overrideOptions: () => overrideOptions,
		emitUrl(url) { overrideHandler?.({ url }) },
		emit(name) { listeners.get(name)?.() },
		setCookie(value) { cookieHeader = String(value || '') },
		async fireTimer(delay) {
			const entry = [...timers.entries()].find(([, timer]) => timer.delay === delay)
			assert.ok(entry, `expected a ${delay}ms timer`)
			timers.delete(entry[0])
			entry[1].callback()
			await flushMicrotasks()
		},
		restore() {
			global.plus = previous.plus
			global.uni = previous.uni
			global.setTimeout = previous.setTimeout
			global.clearTimeout = previous.clearTimeout
		}
	}
}

test('extracts only a bounded cf_clearance pair from the WebView cookie jar', async () => {
	const { extractAndroidClearanceCookie } = await loadPolicy()

	assert.equal(
		extractAndroidClearanceCookie(
			'access_token=secret; cf_clearance=valid.value-_123; XSRF-TOKEN=secret'),
		'cf_clearance=valid.value-_123'
	)
	assert.equal(extractAndroidClearanceCookie('access_token=secret'), '')
	assert.equal(extractAndroidClearanceCookie('cf_clearance=bad\r\nvalue'), '')
	assert.equal(extractAndroidClearanceCookie(`cf_clearance=${'a'.repeat(4097)}`), '')
})

test('replays a confirmed Android edge challenge exactly once', async () => {
	const { executeWithAndroidEdgeChallengeRecovery } = await loadPolicy()
	let attempts = 0
	let clearanceAttempts = 0

	const result = await executeWithAndroidEdgeChallengeRecovery(
		async () => {
			attempts += 1
			if (attempts === 1) throw edgeChallenge()
			return 'ok'
		},
		async () => { clearanceAttempts += 1 }
	)

	assert.equal(result, 'ok')
	assert.equal(attempts, 2)
	assert.equal(clearanceAttempts, 1)
})

test('does not replay ordinary failures and fails closed on a repeated challenge', async () => {
	const { executeWithAndroidEdgeChallengeRecovery } = await loadPolicy()
	let clearanceAttempts = 0
	const ordinary = Object.assign(new Error('backend rejection'), {
		code: 'ACCOUNT_UNAVAILABLE'
	})

	await assert.rejects(
		() => executeWithAndroidEdgeChallengeRecovery(
			async () => { throw ordinary },
			async () => { clearanceAttempts += 1 }
		),
		error => error === ordinary
	)
	await assert.rejects(
		() => executeWithAndroidEdgeChallengeRecovery(
			async () => { throw edgeChallenge() },
			async () => { clearanceAttempts += 1 }
		),
		error => error?.code === 'EDGE_CHALLENGE_REPEATED'
			&& error?.cfRay === 'test-ray-ord'
	)
	assert.equal(clearanceAttempts, 1)
})

test('Android coordinator uses a single full-screen managed challenge without persisting credentials', () => {
	const coordinator = source('common/auth/android-edge-challenge.js')
	const policy = source('common/auth/android-edge-challenge-policy.js')
	const http = source('common/auth/http-client.js')
	const preAuth = source('common/auth/pre-auth.js')
	const app = source('App.vue')

	assert.match(coordinator, /\/__edge\/android-clearance/)
	assert.match(coordinator, /\/__edge\/android-clearance\/status/)
	assert.match(coordinator, /ait-edge:\/\/verified/)
	assert.match(coordinator, /plus\.webview\.create/)
	assert.match(coordinator, /overrideUrlLoading/)
	assert.match(coordinator, /android\.webkit\.CookieManager/)
	assert.match(coordinator, /clearanceInFlight/)
	assert.match(policy, /EDGE_CHALLENGE_TIMEOUT/)
	assert.match(policy, /EDGE_CHALLENGE_CANCELLED/)
	assert.match(policy, /EDGE_CLEARANCE_NOT_SHARED/)
	assert.doesNotMatch(`${coordinator}\n${policy}`,
		/localStorage|sessionStorage|setStorage|saveAndroidSessionCredentials|console\./)
	assert.match(http, /runAndroidRequestWithEdgeRecovery/)
	assert.match(http, /androidEdgeRequestHeaders/)
	assert.match(preAuth, /runAndroidRequestWithEdgeRecovery/)
	assert.match(preAuth, /androidEdgeRequestHeaders/)
	assert.match(app, /presentAndroidEdgeChallengeFailure/)
})

test('registers an exact completion interceptor before loading the challenge page', async () => {
	const runtime = installAndroidRuntime()
	try {
		const coordinator = await loadCoordinator()
		const pending = coordinator.ensureAndroidEdgeClearance()

		assert.deepEqual(runtime.overrideOptions(), {
			mode: 'reject',
			match: '^ait-edge://verified$',
			effect: 'instant',
			exclude: 'none'
		})
		assert.ok(runtime.calls.indexOf('overrideUrlLoading') < runtime.calls.findIndex(
			call => call.startsWith('loadURL:')))
		assert.equal(runtime.calls[0], 'create:')

		runtime.emit('close')
		await assert.rejects(pending, error => error?.code === 'EDGE_CHALLENGE_CANCELLED')
	} finally {
		runtime.restore()
	}
})

test('ignores completion schemes that are not an exact string match', async () => {
	const runtime = installAndroidRuntime({
		cookieHeader: 'cf_clearance=verified-value',
		statusResponses: [{ statusCode: 204 }]
	})
	try {
		const coordinator = await loadCoordinator()
		const pending = coordinator.ensureAndroidEdgeClearance()

		runtime.emitUrl('ait-edge://verified?source=unexpected')
		await flushMicrotasks()

		assert.equal(runtime.requests.length, 0)
		assert.equal(runtime.closeCount(), 0)
		runtime.emit('close')
		await assert.rejects(pending, error => error?.code === 'EDGE_CHALLENGE_CANCELLED')
	} finally {
		runtime.restore()
	}
})

test('confirms the exact scheme through status 204 and closes immediately', async () => {
	const runtime = installAndroidRuntime({
		cookieHeader: 'cf_clearance=verified-value',
		statusResponses: [{ statusCode: 204 }]
	})
	try {
		const coordinator = await loadCoordinator()
		const pending = coordinator.ensureAndroidEdgeClearance()

		runtime.emitUrl('ait-edge://verified')
		await pending

		assert.equal(runtime.requests.length, 1)
		assert.equal(runtime.requests[0].options.url,
			'https://niko000o.site/__edge/android-clearance/status')
		assert.equal(runtime.requests[0].options.timeout, 2000)
		assert.equal(runtime.requests[0].options.header.Cookie, 'cf_clearance=verified-value')
		assert.equal(runtime.closeCount(), 1)
	} finally {
		runtime.restore()
	}
})

test('uses the loaded event as a fallback when the custom scheme is not delivered', async () => {
	const runtime = installAndroidRuntime({
		cookieHeader: 'cf_clearance=verified-value',
		statusResponses: [{ statusCode: 204 }]
	})
	try {
		const coordinator = await loadCoordinator()
		const pending = coordinator.ensureAndroidEdgeClearance()

		runtime.emit('loaded')
		await pending

		assert.equal(runtime.requests.length, 1)
		assert.equal(runtime.closeCount(), 1)
	} finally {
		runtime.restore()
	}
})

test('keeps the challenge open when an initial loaded event still returns 428', async () => {
	const runtime = installAndroidRuntime({
		cookieHeader: 'cf_clearance=not-ready',
		statusResponses: Array.from({ length: 5 }, () => ({ statusCode: 428 }))
	})
	try {
		const coordinator = await loadCoordinator()
		const pending = coordinator.ensureAndroidEdgeClearance()

		runtime.emit('loaded')
		await flushMicrotasks()
		for (const delay of [150, 400, 800, 1500]) {
			await runtime.fireTimer(delay)
		}

		assert.equal(runtime.closeCount(), 0)
		assert.equal(runtime.requests.length, 5)
		runtime.emit('close')
		await assert.rejects(pending, error => error?.code === 'EDGE_CHALLENGE_CANCELLED')
	} finally {
		runtime.restore()
	}
})

test('retries bounded cookie propagation and succeeds when clearance becomes visible', async () => {
	const runtime = installAndroidRuntime({ statusResponses: [{ statusCode: 204 }] })
	try {
		const coordinator = await loadCoordinator()
		const pending = coordinator.ensureAndroidEdgeClearance()

		runtime.emitUrl('ait-edge://verified')
		await flushMicrotasks()
		assert.equal(runtime.requests.length, 0)

		runtime.setCookie('cf_clearance=delayed-value')
		await runtime.fireTimer(150)
		await pending

		assert.equal(runtime.requests.length, 1)
		assert.equal(runtime.closeCount(), 1)
	} finally {
		runtime.restore()
	}
})

test('retries a transient status network failure within the bounded schedule', async () => {
	const runtime = installAndroidRuntime({
		cookieHeader: 'cf_clearance=verified-value',
		statusResponses: [{ fail: true }, { statusCode: 204 }]
	})
	try {
		const coordinator = await loadCoordinator()
		const pending = coordinator.ensureAndroidEdgeClearance()

		runtime.emitUrl('ait-edge://verified')
		await flushMicrotasks()
		await runtime.fireTimer(150)
		await pending

		assert.equal(runtime.requests.length, 2)
		assert.equal(runtime.closeCount(), 1)
	} finally {
		runtime.restore()
	}
})

test('fails closed when an authoritative scheme never obtains shared clearance', async () => {
	const runtime = installAndroidRuntime()
	try {
		const coordinator = await loadCoordinator()
		const pending = coordinator.ensureAndroidEdgeClearance()
		const rejected = assert.rejects(
			pending,
			error => error?.code === 'EDGE_CLEARANCE_NOT_SHARED')

		runtime.emitUrl('ait-edge://verified')
		await flushMicrotasks()
		for (const delay of [150, 400, 800, 1500]) {
			await runtime.fireTimer(delay)
		}

		await rejected
		assert.equal(runtime.requests.length, 0)
		assert.equal(runtime.closeCount(), 1)
	} finally {
		runtime.restore()
	}
})

test('coalesces simultaneous scheme and loaded completion signals', async () => {
	const runtime = installAndroidRuntime({
		cookieHeader: 'cf_clearance=verified-value',
		statusResponses: [{ statusCode: 204 }]
	})
	try {
		const coordinator = await loadCoordinator()
		const pending = coordinator.ensureAndroidEdgeClearance()

		runtime.emitUrl('ait-edge://verified')
		runtime.emit('loaded')
		await pending

		assert.equal(runtime.requests.length, 1)
		assert.equal(runtime.closeCount(), 1)
	} finally {
		runtime.restore()
	}
})

test('cancels an in-flight confirmation when the user closes the WebView', async () => {
	const runtime = installAndroidRuntime({
		cookieHeader: 'cf_clearance=verified-value',
		statusResponses: [{ pending: true }]
	})
	try {
		const coordinator = await loadCoordinator()
		const pending = coordinator.ensureAndroidEdgeClearance()

		runtime.emitUrl('ait-edge://verified')
		await flushMicrotasks()
		runtime.emit('close')

		await assert.rejects(pending, error => error?.code === 'EDGE_CHALLENGE_CANCELLED')
		assert.equal(runtime.requests[0].aborted, true)
	} finally {
		runtime.restore()
	}
})

test('treats the 120 second fallback as a timeout rather than successful completion', async () => {
	const runtime = installAndroidRuntime()
	try {
		const coordinator = await loadCoordinator()
		const pending = coordinator.ensureAndroidEdgeClearance()
		const rejected = assert.rejects(
			pending,
			error => error?.code === 'EDGE_CHALLENGE_TIMEOUT')

		await runtime.fireTimer(120000)

		await rejected
		assert.equal(runtime.closeCount(), 1)
	} finally {
		runtime.restore()
	}
})

test('presents stable Android edge failures without exposing sensitive values', async () => {
	const runtime = installAndroidRuntime()
	try {
		const coordinator = await loadCoordinator()
		const sensitive = 'cf_clearance=must-not-appear'

		assert.equal(coordinator.presentAndroidEdgeChallengeFailure({
			code: 'EDGE_CHALLENGE_TIMEOUT',
			message: sensitive
		}), true)
		assert.equal(coordinator.presentAndroidEdgeChallengeFailure({
			code: 'EDGE_CLEARANCE_NOT_SHARED',
			message: sensitive
		}), true)
		assert.equal(coordinator.presentAndroidEdgeChallengeFailure({
			code: 'EDGE_CHALLENGE_REPEATED',
			message: sensitive
		}), true)
		assert.equal(coordinator.presentAndroidEdgeChallengeFailure({
			code: 'EDGE_CHALLENGE_CANCELLED',
			message: sensitive
		}), true)
		assert.equal(coordinator.presentAndroidEdgeChallengeFailure({
			code: 'NETWORK_ERROR',
			message: sensitive
		}), false)

		assert.deepEqual(runtime.calls.filter(call => call.startsWith('toast:')), [
			'toast:安全验证等待超时，请重新验证。',
			'toast:安全验证状态未同步，请重新验证。',
			'toast:安全验证后请求仍被拦截，请稍后重试。'
		])
		assert.equal(runtime.calls.join('\n').includes(sensitive), false)
	} finally {
		runtime.restore()
	}
})
