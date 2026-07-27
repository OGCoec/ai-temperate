<template>
	<view class="turnstile-panel" :aria-busy="loading">
		<view class="turnstile-copy">
			<text class="turnstile-title">安全验证</text>
			<text class="turnstile-hint">每个流程只需验证一次，验证后才能发送验证码。</text>
		</view>
		<text v-if="loading" class="turnstile-status" role="status" aria-live="polite">正在准备安全验证…</text>
		<!-- #ifdef H5 -->
		<view
			id="ait-turnstile-widget"
			class="turnstile-widget"
			:options="renderOptions"
			:change:options="turnstile.render"
		/>
		<!-- #endif -->
		<!-- #ifdef APP-PLUS -->
		<button type="button" class="turnstile-button" :loading="loading" :disabled="loading || !siteKey" @click="openAndroidWebView">
			{{ loading ? '正在打开…' : '开始安全验证' }}
		</button>
		<!-- #endif -->
		<text v-if="retryStatus" class="turnstile-status" role="status" aria-live="polite">{{ retryStatus }}</text>
		<text v-if="error" class="turnstile-error" role="alert" aria-live="assertive">{{ error }}</text>
		<button v-if="error && !autoRetrying" class="turnstile-retry" type="button" :disabled="loading" @click="retry">重新加载</button>
	</view>
</template>

<script>
	import { authErrorMessage } from '@/common/auth/auth-error.js'
	import { AUTH_API_BASE_URL } from '@/common/auth/config.js'
	import { turnstileErrorPolicy } from '@/common/auth/turnstile-client-error.js'
	import { getTurnstileConfig } from '@/common/auth/turnstile-prewarm.js'
	const ANDROID_TURNSTILE_WEBVIEW_ID = 'ait-auth-turnstile'
	const MAX_SILENT_PROVIDER_FAILURES = 2

	function providerAutomaticallyRetries(code) {
		return /^(?:300|600)\d{3}$/.test(code)
	}

	export default {
		name: 'AuthTurnstile',
		emits: ['verified', 'visibility-change'],
		props: {
			action: { type: String, required: true },
			challenge: { type: String, required: true }
		},
		data() {
			return {
				siteKey: '',
				loading: false,
				error: '',
				renderNonce: 0,
				providerRetryCount: 0,
				autoRetrying: false,
				retryStatus: '',
				androidWebviewOpen: false,
				tokenDelivered: false
			}
		},
		computed: {
			renderOptions() {
				return this.siteKey && this.challenge
					? {
						siteKey: this.siteKey,
						action: this.action,
						challenge: this.challenge,
						nonce: this.renderNonce
					}
					: null
			}
		},
		watch: {
			action() { this.resetForNewChallenge() },
			challenge() { this.resetForNewChallenge() }
		},
		mounted() { this.loadConfig() },
		beforeUnmount() { this.closeVerification() },
		methods: {
			advanceRender() {
				this.providerRetryCount = 0
				this.autoRetrying = false
				this.retryStatus = ''
				this.tokenDelivered = false
				this.renderNonce += 1
			},
			resetForNewChallenge() {
				this.providerRetryCount = 0
				this.autoRetrying = false
				this.retryStatus = ''
				this.error = ''
				if (this.siteKey) this.advanceRender()
			},
			async loadConfig() {
				if (this.loading) return
				this.loading = true
				this.error = ''
				try {
					this.siteKey = (await getTurnstileConfig()).siteKey
					this.advanceRender()
				} catch (error) {
					this.siteKey = ''
					this.error = authErrorMessage(error, '安全验证暂时无法加载，请稍后重试。')
				} finally {
					this.loading = false
				}
			},
			retry() {
				this.error = ''
				this.providerRetryCount = 0
				this.autoRetrying = false
				this.retryStatus = ''
				if (this.siteKey) {
					this.advanceRender()
					return
				}
				this.loadConfig()
			},
			onTurnstileToken(payload) {
				if (payload?.renderNonce !== this.renderNonce) return
				if (this.tokenDelivered) return
				if (!payload?.token) return
				this.providerRetryCount = 0
				this.autoRetrying = false
				this.retryStatus = ''
				this.tokenDelivered = true
				this.error = ''
				this.$emit('verified', payload.token)
			},
			onTurnstileError(payload) {
				if (payload?.renderNonce !== this.renderNonce) return
				this.tokenDelivered = false
				const policy = turnstileErrorPolicy(payload?.code)
				const providerWillRetry = policy.retryable && providerAutomaticallyRetries(policy.code)
				if (providerWillRetry) {
					this.providerRetryCount += 1
				}
				if (providerWillRetry && this.providerRetryCount <= MAX_SILENT_PROVIDER_FAILURES) {
					this.autoRetrying = true
					this.retryStatus = `安全验证出现暂时异常（代码：${policy.code}），正在重新验证…`
					this.error = ''
					return
				}
				this.autoRetrying = false
				this.retryStatus = ''
				this.error = policy.message
			},
			onTurnstileExpired(payload) {
				if (payload?.renderNonce !== this.renderNonce) return
				this.autoRetrying = false
				this.retryStatus = ''
				this.tokenDelivered = false
				this.error = '安全验证已过期，请重新验证。'
			},
			onTurnstileTimeout(payload) {
				if (payload?.renderNonce !== this.renderNonce) return
				this.autoRetrying = false
				this.retryStatus = ''
				this.tokenDelivered = false
				this.error = '安全验证等待超时，请重新验证。'
			},
			resetAfterServerRejection(message) {
				this.providerRetryCount = 0
				this.autoRetrying = false
				this.retryStatus = ''
				this.tokenDelivered = false
				this.error = message || '验证结果未被服务器确认，请重新验证。'
				this.advanceRender()
				this.closeVerification()
			},
			openAndroidWebView() {
				if (this.loading || !this.siteKey || this.androidWebviewOpen) return
				this.loading = true
				this.error = ''
				let webview = null
				try {
					const url = `${AUTH_API_BASE_URL}/api/auth/turnstile/page?challenge=${encodeURIComponent(this.challenge)}&action=${encodeURIComponent(this.action)}`
					webview = plus.webview.create(url, ANDROID_TURNSTILE_WEBVIEW_ID, {
						top: '0px',
						bottom: '0px',
						background: '#0b0d0c'
					})
					this.androidWebviewOpen = true
					this.$emit('visibility-change', true)
					webview.overrideUrlLoading({ mode: 'reject', match: 'aiturnstile://*' }, (event) => {
						const match = String(event.url || '').match(/[?&]token=([^&]+)/)
						if (match && !this.tokenDelivered) {
							this.tokenDelivered = true
							this.$emit('verified', decodeURIComponent(match[1]))
						}
						this.closeVerification()
					})
					webview.addEventListener('close', () => {
						this.androidWebviewOpen = false
						this.loading = false
						this.$emit('visibility-change', false)
					})
					webview.show('slide-in-bottom', 180)
				} catch (error) {
					if (webview) webview.close('none')
					this.androidWebviewOpen = false
					this.loading = false
					this.$emit('visibility-change', false)
					this.error = '无法打开安全验证，请稍后重试。'
				}
			},
			closeVerification() {
				// #ifdef APP-PLUS
				if (!this.androidWebviewOpen) return false
				const webview = plus.webview.getWebviewById(ANDROID_TURNSTILE_WEBVIEW_ID)
				this.androidWebviewOpen = false
				if (webview) webview.close('slide-out-bottom', 180)
				this.loading = false
				this.$emit('visibility-change', false)
				return true
				// #endif
				// #ifndef APP-PLUS
				return false
				// #endif
			}
		}
	}
</script>

<!-- #ifdef H5 -->
<script module="turnstile" lang="renderjs">
	const TURNSTILE_SCRIPT_ID = 'ait-turnstile-sdk'
	const TURNSTILE_READY_CALLBACK = 'aitTurnstileSdkReady'
	const TURNSTILE_SDK_PROMISE_KEY = '__AIT_TURNSTILE_SDK_PROMISE__'
	const SDK_READY_TIMEOUT_MS = 15_000

	export default {
		data() { return { widgetId: null, scriptPromise: null, renderGeneration: 0 } },
		methods: {
			load() {
				if (window.turnstile?.render) return Promise.resolve(window.turnstile)
				if (this.scriptPromise) return this.scriptPromise
				const prewarmedPromise = window[TURNSTILE_SDK_PROMISE_KEY]
				if (prewarmedPromise) {
					this.scriptPromise = Promise.resolve(prewarmedPromise)
						.then(api => {
							if (!api?.render) throw new Error('Turnstile SDK prewarm did not expose render.')
							return api
						})
						.catch(() => {
							this.scriptPromise = null
							return this.loadFresh()
						})
					return this.scriptPromise
				}
				return this.loadFresh()
			},
			loadFresh() {
				let sharedPromise
				sharedPromise = new Promise((resolve) => {
					const staleScript = document.getElementById(TURNSTILE_SCRIPT_ID)
					if (staleScript) staleScript.remove()
					const script = document.createElement('script')
					script.id = TURNSTILE_SCRIPT_ID
					script.src = `https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=${TURNSTILE_READY_CALLBACK}`
					script.async = true
					script.defer = true
					let settled = false
					let readyTimeout
					const removeReadyCallback = () => {
						if (window[TURNSTILE_READY_CALLBACK] === ready) window[TURNSTILE_READY_CALLBACK] = undefined
					}
					const fail = error => {
						if (settled) return
						settled = true
						clearTimeout(readyTimeout)
						script.remove()
						removeReadyCallback()
						if (window[TURNSTILE_SDK_PROMISE_KEY] === sharedPromise) {
							window[TURNSTILE_SDK_PROMISE_KEY] = undefined
						}
						resolve(null)
					}
					const ready = () => {
						if (settled) return
						if (!window.turnstile?.render) {
							fail(new Error('Turnstile SDK ready callback did not expose render.'))
							return
						}
						settled = true
						clearTimeout(readyTimeout)
						removeReadyCallback()
						resolve(window.turnstile)
					}
					// 必须先注册供应商 ready 回调再插入脚本，避免首次下载完成与全局 API 初始化之间的竞态。
					window[TURNSTILE_READY_CALLBACK] = ready
					script.onerror = () => fail(new Error('Turnstile SDK script failed to load.'))
					readyTimeout = setTimeout(
						() => fail(new Error('Turnstile SDK ready timeout.')),
						SDK_READY_TIMEOUT_MS
					)
					document.head.appendChild(script)
				})
				window[TURNSTILE_SDK_PROMISE_KEY] = sharedPromise
				this.scriptPromise = sharedPromise
					.then(api => {
						if (!api?.render) throw new Error('Turnstile SDK failed to load.')
						return api
					})
					.catch(error => {
						this.scriptPromise = null
						throw error
					})
				return this.scriptPromise
			},
			async render(options) {
				if (!options?.siteKey || !options?.challenge) return
				const generation = ++this.renderGeneration
				try {
					const api = await this.load()
					if (generation !== this.renderGeneration) return
					if (this.widgetId !== null) api.remove(this.widgetId)
					this.widgetId = api.render('#ait-turnstile-widget', {
						sitekey: options.siteKey,
						action: options.action,
						cData: options.challenge,
						theme: 'dark',
						retry: 'auto',
						'retry-interval': 8000,
						callback: token => {
							if (generation !== this.renderGeneration) return
							this.$ownerInstance.callMethod('onTurnstileToken', { token, renderNonce: options.nonce })
						},
						'error-callback': code => {
							if (generation !== this.renderGeneration) return
							this.$ownerInstance.callMethod('onTurnstileError', { code, renderNonce: options.nonce })
						},
						'expired-callback': () => {
							if (generation !== this.renderGeneration) return
							this.$ownerInstance.callMethod('onTurnstileExpired', { renderNonce: options.nonce })
						},
						'timeout-callback': () => {
							if (generation !== this.renderGeneration) return
							this.$ownerInstance.callMethod('onTurnstileTimeout', { renderNonce: options.nonce })
						}
					})
				} catch (error) {
					if (generation !== this.renderGeneration) return
					this.$ownerInstance.callMethod('onTurnstileError', { code: '200500', renderNonce: options.nonce })
				}
			}
		}
	}
</script>
<!-- #endif -->

<style scoped>
	.turnstile-panel { padding: 18px; border: 1px solid #303733; border-radius: 12px; background: #151816; }
	.turnstile-copy { margin-bottom: 16px; display: flex; flex-direction: column; gap: 5px; }
	.turnstile-title { color: #f3f5f4; font-size: 16px; font-weight: 700; }
	.turnstile-hint { color: #8b9690; font-size: 13px; line-height: 1.5; }
	.turnstile-status { display: block; margin-bottom: 12px; color: #a9b5af; font-size: 13px; }
	.turnstile-widget { min-height: 66px; }
	.turnstile-button {
		height: 48px;
		margin: 0;
		border: 0;
		border-radius: 10px;
		background: #26312c;
		color: #dce5e0;
		font-size: 15px;
		line-height: 48px;
		transition: background-color 180ms ease, box-shadow 180ms ease, transform 180ms ease;
	}
	.turnstile-button::after,
	.turnstile-retry::after { border: 0; }
	.turnstile-button:active { transform: scale(.98); background: #304039; }
	.turnstile-retry:active { color: #2dbb86; transform: scale(.98); }
	.turnstile-error { display: block; margin-top: 12px; color: #ff9292; font-size: 13px; line-height: 1.45; }
	.turnstile-retry {
		min-height: 44px;
		margin: 6px 0 0;
		padding: 0;
		border: 0;
		background: transparent;
		color: #37d39a;
		font-size: 14px;
		line-height: 44px;
		transition: color 180ms ease, box-shadow 180ms ease, transform 180ms ease;
	}
	.turnstile-button:focus-visible,
	.turnstile-retry:focus-visible { outline: none; box-shadow: 0 0 0 3px rgba(55, 211, 154, .18); }
	@media (prefers-reduced-motion: reduce) {
		.turnstile-button,
		.turnstile-retry { transition: none; }
	}
</style>
