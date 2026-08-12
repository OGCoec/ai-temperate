<template>
	<view class="turnstile-panel" :aria-busy="loading">
		<view class="turnstile-copy">
			<text class="turnstile-title">安全验证</text>
			<text class="turnstile-hint">每个流程只需验证一次，验证后才能发送验证码。</text>
		</view>
		<text class="turnstile-assistive" role="status" aria-live="polite">{{ assistiveStatus }}</text>
		<!-- #ifdef H5 -->
		<view
			id="ait-turnstile-widget"
			class="turnstile-widget"
			:options="renderOptions"
			:change:options="turnstile.render"
		/>
		<!-- #endif -->
		<!-- #ifdef APP-PLUS -->
		<view class="turnstile-widget turnstile-native-host" />
		<!-- #endif -->
		<text v-if="error" class="turnstile-error" role="alert" aria-live="assertive">{{ error }}</text>
		<button v-if="error && !autoRetrying" class="turnstile-retry" type="button" :disabled="loading" @click="retry">重新加载</button>
	</view>
</template>

<script>
	import { authErrorMessage } from '@/common/auth/auth-error.js'
	import { AUTH_API_BASE_URL } from '@/common/auth/config.js'
	import { turnstileErrorPolicy } from '@/common/auth/turnstile-client-error.js'
	import { getTurnstileConfig } from '@/common/auth/turnstile-prewarm.js'
	// #ifdef APP-PLUS
	import { createTurnstileAttemptId } from '@/common/auth/turnstile-response-diagnostics.js'
	import { ensurePreAuth } from '@/common/auth/pre-auth.js'
	import { getDeviceInstallationId } from '@/common/auth/device-installation.js'
	import {
		resolveAndroidTurnstileAnchor
	} from '@/common/auth/android-turnstile-anchor.js'
	import { createAndroidTurnstileWebViewSession } from '@/common/auth/android-turnstile-webview.js'
	import {
		loadAndroidTurnstilePage
	} from '@/common/auth/android-turnstile-navigation.js'
	// #endif
	const MAX_SILENT_PROVIDER_FAILURES = 2
	const ANDROID_TURNSTILE_TIMEOUT_MS = 120000
	const androidTurnstileSessions = new WeakMap()
	let androidTurnstileWebviewSequence = 0

	function providerAutomaticallyRetries(code) {
		return /^(?:300|600)\d{3}$/.test(code)
	}

	// #ifdef APP-PLUS
	function currentPageWebview() {
		const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
		const page = pages[pages.length - 1]
		return typeof page?.$getAppWebview === 'function' ? page.$getAppWebview() : null
	}

	function nextAndroidTurnstileWebviewId() {
		androidTurnstileWebviewSequence = androidTurnstileWebviewSequence >= 999999
			? 1
			: androidTurnstileWebviewSequence + 1
		return `ait-auth-turnstile-${Date.now().toString(36)}-${androidTurnstileWebviewSequence}`
	}
	// #endif

	export default {
		name: 'AuthTurnstile',
		emits: ['verified'],
		props: {
			action: { type: String, required: true },
			challenge: { type: String, required: true },
			pageScrollTop: { type: Number, default: 0 }
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
				androidState: 'idle',
				androidGeneration: 0,
				androidLoadingGeneration: 0,
				androidMountScheduled: false,
				androidBoundsScheduled: false,
				androidMountTimer: null,
				androidBoundsTimer: null,
				androidBoundsRevision: 0,
				androidPageScrollTop: Number.isFinite(Number(this.pageScrollTop))
					? Math.max(0, Number(this.pageScrollTop))
					: 0,
				androidDestroyed: false,
				androidTraceId: '',
				tokenDelivered: false
			}
		},
		computed: {
			assistiveStatus() {
				if (this.retryStatus) return this.retryStatus
				if (this.loading) return '正在准备安全验证。'
				return ''
			},
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
			challenge() { this.resetForNewChallenge() },
			pageScrollTop(value) {
				this.syncAndroidBounds({ scrollTop: value, reason: 'scroll' })
			}
		},
		mounted() { this.loadConfig() },
		beforeUnmount() { this.destroyAndroidVerification() },
		methods: {
			advanceRender() {
				this.providerRetryCount = 0
				this.autoRetrying = false
				this.retryStatus = ''
				this.tokenDelivered = false
				this.renderNonce += 1
				// #ifdef APP-PLUS
				this.scheduleAndroidMount()
				// #endif
			},
			resetForNewChallenge() {
				// #ifdef APP-PLUS
				this.androidGeneration += 1
				this.androidBoundsRevision += 1
				this.closeAndroidSession()
				this.androidLoadingGeneration = 0
				this.loading = false
				this.androidTraceId = createTurnstileAttemptId()
				this.androidState = 'idle'
				// #endif
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
					// #ifdef APP-PLUS
					this.androidTraceId = createTurnstileAttemptId()
					// #endif
					this.advanceRender()
					// #ifdef APP-PLUS
					this.traceAndroidStage('challenge_received')
					// #endif
				} catch (error) {
					this.siteKey = ''
					this.error = authErrorMessage(error, '安全验证暂时无法加载，请稍后重试。')
				} finally {
					this.loading = false
					// #ifdef APP-PLUS
					this.scheduleAndroidMount()
					// #endif
				}
			},
			retry() {
				// #ifdef APP-PLUS
				this.retryAndroidVerification()
				return
				// #endif
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
					return false
				}
				this.autoRetrying = false
				this.retryStatus = ''
				this.error = policy.message
				return true
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
				const rejectionMessage = message || '验证结果未被服务器确认，请重新验证。'
				// #ifdef APP-PLUS
				this.traceAndroidStage('business_verify_rejected')
				this.androidGeneration += 1
				this.androidBoundsRevision += 1
				this.closeAndroidSession()
				this.androidLoadingGeneration = 0
				this.loading = false
				this.androidTraceId = createTurnstileAttemptId()
				this.androidState = 'idle'
				// #endif
				this.providerRetryCount = 0
				this.autoRetrying = false
				this.retryStatus = ''
				this.tokenDelivered = false
				this.error = rejectionMessage
				this.advanceRender()
				this.retryStatus = `${rejectionMessage} 正在重新验证…`
			},
			markServerVerificationStarted() {
				this.retryStatus = '安全验证已完成，正在确认结果…'
				// #ifdef APP-PLUS
				this.traceAndroidStage('business_verify_started')
				// #endif
			},
			markServerAccepted() {
				// #ifdef APP-PLUS
				this.androidState = 'verified'
				this.traceAndroidStage('business_verify_accepted')
				// #endif
			},
			retryAndroidVerification() {
				// #ifdef APP-PLUS
				this.androidGeneration += 1
				this.androidBoundsRevision += 1
				this.closeAndroidSession()
				this.androidLoadingGeneration = 0
				this.loading = false
				this.androidTraceId = createTurnstileAttemptId()
				this.androidState = 'idle'
				this.error = ''
				this.providerRetryCount = 0
				this.autoRetrying = false
				this.retryStatus = ''
				this.tokenDelivered = false
				if (!this.siteKey) {
					void this.loadConfig()
					return true
				}
				this.advanceRender()
				return true
				// #endif
				// #ifndef APP-PLUS
				return false
				// #endif
			},
			scheduleAndroidMount() {
				// #ifdef APP-PLUS
				if (this.androidDestroyed || this.androidMountScheduled || !this.siteKey || !this.challenge) return false
				this.androidMountScheduled = true
				this.$nextTick(() => {
					if (this.androidDestroyed) {
						this.androidMountScheduled = false
						return
					}
					this.androidMountTimer = setTimeout(() => {
						this.androidMountTimer = null
						this.androidMountScheduled = false
						if (this.androidDestroyed) return
						void this.startAndroidVerification()
					}, 16)
				})
				return true
				// #endif
				// #ifndef APP-PLUS
				return false
				// #endif
			},
			measureAndroidBounds() {
				// #ifdef APP-PLUS
				return new Promise((resolve) => {
					uni.createSelectorQuery()
						.in(this)
						.select('.turnstile-native-host')
						.boundingClientRect((rect) => {
							if (!rect || Number(rect.width) <= 0) {
								resolve(null)
								return
							}
							resolve(resolveAndroidTurnstileAnchor(rect, this.androidPageScrollTop))
						})
						.exec()
				})
				// #endif
				// #ifndef APP-PLUS
				return Promise.resolve(null)
				// #endif
			},
			async startAndroidVerification() {
				// #ifdef APP-PLUS
				if (this.androidDestroyed || this.loading || !this.siteKey || !this.challenge || androidTurnstileSessions.has(this)) return false
				const generation = ++this.androidGeneration
				this.androidLoadingGeneration = generation
				this.loading = true
				this.androidState = 'loading'
				let session = null
				try {
					try { uni.hideKeyboard() } catch (_) {}
					const [preAuthToken, bounds] = await Promise.all([
						ensurePreAuth(),
						this.measureAndroidBounds()
					])
					if (generation !== this.androidGeneration) return false
					const deviceInstallationId = getDeviceInstallationId()
					const parentWebview = currentPageWebview()
					if (!bounds || !parentWebview) throw new Error('Turnstile host is unavailable.')
					const channel = createTurnstileAttemptId()
					session = createAndroidTurnstileWebViewSession({
						webviewManager: plus.webview,
						parentWebview,
						webviewId: nextAndroidTurnstileWebviewId(),
						channel,
						bounds,
						timeoutMillis: ANDROID_TURNSTILE_TIMEOUT_MS,
						onCreated: () => this.traceAndroidStage('webview_created'),
						onLoaded: () => {
							if (generation !== this.androidGeneration) return
							this.loading = false
							this.androidLoadingGeneration = 0
							this.androidState = 'ready'
							this.traceAndroidStage('page_loaded')
							this.$nextTick(() => this.syncAndroidBounds({ reason: 'show' }))
						},
						onResult: (result, source) => {
							if (generation !== this.androidGeneration) return
							this.traceAndroidStage('native_result_received', { source, resultType: result.type })
							this.handleAndroidResult(result)
						},
						onError: (errorCode) => {
							if (generation !== this.androidGeneration) return
							this.failAndroidVerification('无法加载安全验证，请稍后重试。', errorCode)
						},
						onClosed: () => {
							if (generation !== this.androidGeneration) return
							androidTurnstileSessions.delete(this)
							this.failAndroidVerification('安全验证页面已关闭，请重新验证。', 'TURNSTILE_WEBVIEW_CLOSED')
						},
						onTimeout: () => {
							if (generation !== this.androidGeneration) return
							this.failAndroidVerification(
								'安全验证等待超时，请重新验证。',
								'TURNSTILE_TIMEOUT',
								'timeout'
							)
						},
						load: (webview) => loadAndroidTurnstilePage(webview, {
							baseUrl: AUTH_API_BASE_URL,
							challenge: this.challenge,
							action: this.action,
							siteKey: this.siteKey,
							channel,
							preAuthToken,
							deviceInstallationId
						})
					})
					if (generation !== this.androidGeneration) {
						session.close()
						return false
					}
					androidTurnstileSessions.set(this, session)
					return true
				} catch (error) {
					session?.close()
					if (generation === this.androidGeneration) {
						this.androidLoadingGeneration = 0
						this.failAndroidVerification('无法加载安全验证，请稍后重试。', 'TURNSTILE_WEBVIEW_CREATE_FAILED')
					}
					return false
				}
				// #endif
				// #ifndef APP-PLUS
				return false
				// #endif
			},
			handleAndroidResult(result) {
				// #ifdef APP-PLUS
				this.loading = false
				this.androidLoadingGeneration = 0
				if (result?.type === 'VERIFIED') {
					this.androidState = 'verifying'
					this.traceAndroidStage('provider_verified')
					this.onTurnstileToken({ token: result.token, renderNonce: this.renderNonce })
					return true
				}
				if (result?.type === 'ERROR') {
					if (result.code === 'config_invalid') {
						this.failAndroidVerification('安全验证配置无效，请重新验证。', 'TURNSTILE_CONFIG_INVALID')
						return true
					}
					const terminal = this.onTurnstileError({ code: result.code, renderNonce: this.renderNonce })
					this.androidState = terminal ? 'error' : 'ready'
					if (terminal) this.closeAndroidSession()
					return true
				}
				if (result?.type === 'EXPIRED') {
					this.androidState = 'expired'
					this.onTurnstileExpired({ renderNonce: this.renderNonce })
					this.closeAndroidSession()
					return true
				}
				if (result?.type === 'TIMEOUT') {
					this.androidState = 'timeout'
					this.onTurnstileTimeout({ renderNonce: this.renderNonce })
					this.closeAndroidSession()
					return true
				}
				return false
				// #endif
				// #ifndef APP-PLUS
				return false
				// #endif
			},
			failAndroidVerification(message, errorCode, state = 'error') {
				// #ifdef APP-PLUS
				this.loading = false
				this.androidLoadingGeneration = 0
				this.androidState = state
				this.autoRetrying = false
				this.retryStatus = ''
				this.tokenDelivered = false
				this.error = message
				this.traceAndroidStage('verification_failed', { errorCode })
				this.closeAndroidSession()
				return true
				// #endif
				// #ifndef APP-PLUS
				return false
				// #endif
			},
			closeAndroidSession() {
				// #ifdef APP-PLUS
				const session = androidTurnstileSessions.get(this)
				androidTurnstileSessions.delete(this)
				if (!session) return false
				session.close()
				return true
				// #endif
				// #ifndef APP-PLUS
				return false
				// #endif
			},
			scheduleAndroidBoundsMeasurement() {
				// #ifdef APP-PLUS
				const session = androidTurnstileSessions.get(this)
				if (!session || this.androidDestroyed) return false
				if (this.androidBoundsScheduled) return true
				this.androidBoundsScheduled = true
				this.androidBoundsTimer = setTimeout(async () => {
					this.androidBoundsTimer = null
					const revision = this.androidBoundsRevision
					try {
						const currentSession = androidTurnstileSessions.get(this)
						if (!currentSession || currentSession !== session) return
						const bounds = await this.measureAndroidBounds()
						if (
							bounds &&
							revision === this.androidBoundsRevision &&
							androidTurnstileSessions.get(this) === session
						) session.setBounds(bounds)
					} finally {
						this.androidBoundsScheduled = false
						const latestSession = androidTurnstileSessions.get(this)
						if (
							latestSession &&
							(revision !== this.androidBoundsRevision || latestSession !== session)
						) this.scheduleAndroidBoundsMeasurement()
					}
				}, 16)
				return true
				// #endif
				// #ifndef APP-PLUS
				return false
				// #endif
			},
			syncAndroidBounds(context = {}) {
				// #ifdef APP-PLUS
				const scrollTop = Number(context?.scrollTop)
				if (Number.isFinite(scrollTop) && scrollTop >= 0) this.androidPageScrollTop = scrollTop
				const session = androidTurnstileSessions.get(this)
				if (!session) return this.scheduleAndroidMount()
				const reason = String(context?.reason || 'show')
				if (reason === 'scroll') return true
				this.androidBoundsRevision += 1
				return this.scheduleAndroidBoundsMeasurement()
				// #endif
				// #ifndef APP-PLUS
				return false
				// #endif
			},
			destroyAndroidVerification() {
				// #ifdef APP-PLUS
				this.androidGeneration += 1
				this.androidBoundsRevision += 1
				this.androidLoadingGeneration = 0
				this.androidDestroyed = true
				if (this.androidMountTimer !== null) clearTimeout(this.androidMountTimer)
				if (this.androidBoundsTimer !== null) clearTimeout(this.androidBoundsTimer)
				this.androidMountTimer = null
				this.androidBoundsTimer = null
				this.androidMountScheduled = false
				this.androidBoundsScheduled = false
				this.closeAndroidSession()
				// #endif
			},
			traceAndroidStage(stage, details = {}) {
				// #ifdef APP-PLUS
				if (typeof console === 'undefined' || typeof console.info !== 'function') return
				const payload = {
					scope: 'user-flow',
					stage,
					platform: 'ANDROID',
					traceId: this.androidTraceId || 'turnstile-unassigned'
				}
				if (/^(?:override|loading|loaded)$/.test(String(details.source || ''))) {
					payload.source = details.source
				}
				if (/^(?:VERIFIED|ERROR|EXPIRED|TIMEOUT)$/.test(String(details.resultType || ''))) {
					payload.resultType = details.resultType
				}
				if (/^[A-Z0-9_]{3,64}$/.test(String(details.errorCode || ''))) {
					payload.errorCode = details.errorCode
				}
				console.info('[ait-turnstile]', JSON.stringify(payload))
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
						size: 'normal',
						language: 'auto',
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

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.turnstile-panel { @include user-frosted-surface; padding: 18px; border-radius: 12px; overflow: visible; }
	.turnstile-copy { margin-bottom: 16px; display: flex; flex-direction: column; gap: 5px; }
	.turnstile-title { color: #f3f5f4; font-size: 16px; font-weight: 700; }
	.turnstile-hint { color: #8b9690; font-size: 13px; line-height: 1.5; }
	.turnstile-assistive {
		position: absolute;
		width: 1px;
		height: 1px;
		margin: -1px;
		padding: 0;
		overflow: hidden;
		clip: rect(0 0 0 0);
		white-space: nowrap;
	}
	.turnstile-widget { width: 300px; height: 76px; min-height: 76px; }
	.turnstile-native-host { background: transparent; }
	/* #ifdef APP-PLUS */
	.turnstile-native-host { width: 240px; }
	/* #endif */
	.turnstile-retry::after { border: 0; }
	.turnstile-retry:active { color: #2dbb86; transform: scale(.98); }
	.turnstile-error { display: block; margin-top: 12px; color: #ff9292; font-size: 13px; line-height: 1.45; }
	.turnstile-retry {
		@include user-frosted-control;
		min-height: 48px;
		margin: 6px 0 0;
		padding: 0;
		color: #37d39a;
		font-size: 14px;
		line-height: 1.2;
	}
	.turnstile-retry:focus-visible { outline: none; box-shadow: 0 0 0 3px rgba(55, 211, 154, .18); }
	@media screen and (max-width: 359px) {
		.turnstile-widget { position: relative; left: 50%; transform: translateX(-50%); }
	}
	@media (prefers-reduced-motion: reduce) {
		.turnstile-retry { transition: none; }
	}
</style>
