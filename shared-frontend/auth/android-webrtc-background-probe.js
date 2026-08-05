const RESULT_SCHEME = 'aitwebrtc://result'
const MAX_ENCRYPTED_PAYLOAD_LENGTH = 4096

/**
 * 在 Android 的屏幕外本地 WebView 中执行 WebRTC，并只通过自定义 scheme 回传一次性 AES-GCM 密文。
 */
export async function collectAndroidWebRtcIpsInBackground(options = {}) {
	if (typeof plus === 'undefined'
		|| !plus.webview
		|| !globalThis.crypto?.subtle
		|| !globalThis.crypto?.getRandomValues) return []

	const nonce = randomHex(16)
	const channelId = randomHex(16)
	const rawKey = randomBytes(32)
	const key = await globalThis.crypto.subtle.importKey(
		'raw',
		rawKey,
		{ name: 'AES-GCM' },
		false,
		['decrypt']
	)
	const timeoutMillis = boundedTimeout(options.timeoutMillis)
	const attemptId = String(options.attemptId || 'discover')
		.replace(/[^A-Za-z0-9:_-]/g, '')
		.slice(0, 64)
	const webviewId = `${String(options.webviewId || 'ait-webrtc')}-${attemptId}-${nonce}`
	let webview = null
	let timer = null
	let settled = false

	return new Promise(resolve => {
		const finish = value => {
			if (settled) return
			settled = true
			if (timer) clearTimeout(timer)
			try {
				webview?.close?.('none')
			} catch (_) {
				// 已销毁 WebView 的重复 close 不改变探测结果。
			}
			webview = null
			resolve(Array.isArray(value) ? value.slice(0, 8) : [])
		}

		try {
			webview = plus.webview.create(
				String(options.resourcePath || '/hybrid/html/webrtc-probe.html'),
				webviewId,
				{
					left: '-10000px',
					top: '-10000px',
					width: '1px',
					height: '1px',
					opacity: 0,
					render: 'always',
					background: 'transparent'
				}
			)
			webview.overrideUrlLoading(
				{ mode: 'reject', match: 'aitwebrtc://*' },
				event => {
					void decryptResult(event?.url, key, channelId, nonce)
						.then(finish)
						.catch(() => finish([]))
				}
			)
			webview.addEventListener('loaded', () => {
				const configuration = {
					channelId,
					nonce,
					key: toBase64Url(rawKey),
					stunUrls: Array.isArray(options.stunUrls) ? options.stunUrls.slice(0, 4) : [],
					timeoutMillis
				}
				webview.evalJS(`window.startWebRtcProbe(${JSON.stringify(configuration)})`)
			})
			webview.addEventListener('error', () => finish([]))
			webview.addEventListener('close', () => finish([]))
			// offscreen 加 render=always 后仍需 show，确保系统真正创建渲染上下文并执行 WebRTC。
			webview.show('none', 0)
			timer = setTimeout(() => finish([]), timeoutMillis + 250)
		} catch (_) {
			finish([])
		}
	})
}

async function decryptResult(rawUrl, key, expectedChannel, expectedNonce) {
	const parsed = new URL(String(rawUrl || ''))
	if (`${parsed.protocol}//${parsed.host}` !== RESULT_SCHEME) return []
	if (parsed.searchParams.get('channel') !== expectedChannel) return []
	if (parsed.searchParams.get('error')) return []
	const ivText = parsed.searchParams.get('iv') || ''
	const payloadText = parsed.searchParams.get('payload') || ''
	if (!/^[A-Za-z0-9_-]{16}$/.test(ivText)
		|| !/^[A-Za-z0-9_-]+$/.test(payloadText)
		|| payloadText.length > MAX_ENCRYPTED_PAYLOAD_LENGTH) return []
	const plaintext = await globalThis.crypto.subtle.decrypt(
		{
			name: 'AES-GCM',
			iv: fromBase64Url(ivText),
			additionalData: new TextEncoder().encode(`${expectedChannel}|${expectedNonce}`)
		},
		key,
		fromBase64Url(payloadText)
	)
	const value = JSON.parse(new TextDecoder().decode(plaintext))
	if (value?.channelId !== expectedChannel || value?.nonce !== expectedNonce) return []
	return Array.isArray(value.webRtcIps)
		? value.webRtcIps.filter(item => typeof item === 'string' && item.length <= 64).slice(0, 8)
		: []
}

function boundedTimeout(value) {
	const numeric = Number(value)
	return Number.isFinite(numeric) && numeric > 0 ? Math.min(numeric, 15000) : 15000
}

function randomBytes(length) {
	const bytes = new Uint8Array(length)
	globalThis.crypto.getRandomValues(bytes)
	return bytes
}

function randomHex(length) {
	return [...randomBytes(length)]
		.map(value => value.toString(16).padStart(2, '0'))
		.join('')
}

function toBase64Url(bytes) {
	let binary = ''
	bytes.forEach(value => { binary += String.fromCharCode(value) })
	return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

function fromBase64Url(value) {
	const normalized = String(value).replace(/-/g, '+').replace(/_/g, '/')
	const padded = normalized + '='.repeat((4 - normalized.length % 4) % 4)
	const binary = atob(padded)
	return Uint8Array.from(binary, character => character.charCodeAt(0))
}
