<template>
	<view
		class="android-chat-video"
		:style="videoFrameStyle"
		:aria-busy="String(mediaPhase === MEDIA_PHASES.LOADING)"
	>
		<view
			class="android-video-render-host"
			:media-config="renderConfig"
			:change:media-config="androidVideoRenderer.updateMedia"
		></view>
		<view
			v-if="showLoadingOverlay"
			class="android-video-overlay"
			role="status"
		>
			<uni-icons type="videocam" size="28" color="#8fdcbe" aria-hidden="true" />
			<text>{{ loadingLabel }}</text>
		</view>
		<view v-else-if="mediaPhase === MEDIA_PHASES.METADATA_READY" class="android-video-overlay is-ready" role="status">
			<uni-icons type="videocam" size="28" color="#8fdcbe" aria-hidden="true" />
			<text>视频信息已加载</text>
			<button class="android-video-action" type="button" @click="requestPlay">点击播放</button>
		</view>
		<view v-else-if="mediaPhase === MEDIA_PHASES.ERROR" class="android-video-overlay is-error" role="alert">
			<uni-icons type="info" size="28" color="#ff9b94" aria-hidden="true" />
			<text>{{ errorLabel }}</text>
			<view class="android-video-actions">
				<button class="android-video-action" type="button" @click="retry">重新加载</button>
				<button class="android-video-action" type="button" @click="$emit('download', attachment)">下载文件</button>
			</view>
		</view>
		<view v-else-if="mediaPhase === MEDIA_PHASES.WAITING" class="android-video-buffer" role="status" aria-label="视频缓冲中">
			<view class="android-video-buffer-dot"></view>
		</view>
	</view>
</template>

<script>
	import {
		createMediaDescriptor,
		MEDIA_PHASES,
		resolveMediaAspectRatio
	} from '@/common/aichat/ai-media-presentation.js'

	const MEDIA_STAGE_BY_PHASE = Object.freeze({
		LOADING: 'media_loading',
		METADATA_READY: 'media_metadata_ready',
		PLAYING: 'media_playing',
		WAITING: 'media_waiting',
		ERROR: 'media_error'
	})

	export default {
		name: 'UserAndroidChatVideo',
		props: {
			attachment: { type: Object, required: true },
			metadata: { type: Object, default: null },
			active: { type: Boolean, default: true }
		},
		emits: ['state-change', 'layout-change', 'download'],
		data() {
			return {
				MEDIA_PHASES,
				revision: 1,
				mediaPhase: MEDIA_PHASES.IDLE,
				errorCategory: '',
				observedAspectRatio: null,
				pauseRequest: 0,
				playRequest: 0,
				disposed: false
			}
		},
		computed: {
			descriptor() {
				try {
					return createMediaDescriptor(this.attachment, this.metadata)
				} catch (_) {
					return null
				}
			},
			aspectRatio() {
				return this.observedAspectRatio
					|| resolveMediaAspectRatio(this.descriptor, 16 / 9)
			},
			videoFrameStyle() {
				return { '--android-video-aspect': String(this.aspectRatio) }
			},
			renderConfig() {
				return Object.freeze({
					key: this.descriptor?.key || '',
					src: this.descriptor?.src || '',
					revision: this.revision,
					pauseRequest: this.pauseRequest,
					playRequest: this.playRequest,
					active: this.active && !this.disposed,
					disposed: this.disposed
				})
			},
			showLoadingOverlay() {
				return [
					MEDIA_PHASES.IDLE,
					MEDIA_PHASES.OBSERVING,
					MEDIA_PHASES.LOADING
				].includes(this.mediaPhase)
			},
			loadingLabel() {
				if (this.mediaPhase === MEDIA_PHASES.OBSERVING) return '靠近视频后加载播放信息'
				return '正在准备视频…'
			},
			errorLabel() {
				return ({
					NETWORK: '视频加载失败，请检查网络后重试',
					DECODE: '设备无法解码该视频',
					UNSUPPORTED: '当前设备不支持该视频格式',
					TIMEOUT: '视频加载超时，请重试',
					ABORTED: '视频加载已中止，请重试',
					INVALID_SOURCE: '视频地址无效',
					UNKNOWN: '视频暂时无法播放'
				})[this.errorCategory] || '视频暂时无法播放'
			}
		},
		watch: {
			attachment() {
				this.restartForSourceChange()
			},
			metadata() {
				this.restartForSourceChange()
			},
			active(value) {
				if (!value) this.pause()
			}
		},
		mounted() {
			if (!this.descriptor) this.acceptSafeState(MEDIA_PHASES.ERROR, 'INVALID_SOURCE')
		},
		beforeUnmount() {
			this.dispose()
		},
		methods: {
			restartForSourceChange() {
				this.revision += 1
				this.mediaPhase = MEDIA_PHASES.IDLE
				this.errorCategory = ''
				this.observedAspectRatio = null
				this.disposed = false
				this.$nextTick(() => {
					if (!this.descriptor) this.acceptSafeState(MEDIA_PHASES.ERROR, 'INVALID_SOURCE')
				})
			},
			retry() {
				this.revision += 1
				this.mediaPhase = MEDIA_PHASES.IDLE
				this.errorCategory = ''
				this.disposed = false
			},
			pause() {
				this.pauseRequest += 1
			},
			requestPlay() {
				this.playRequest += 1
			},
			dispose() {
				if (this.disposed) return
				this.disposed = true
				this.revision += 1
				this.safeDiagnostic('media_destroyed')
			},
			receiveAndroidVideoEvent(payload) {
				if (!payload || this.disposed || payload.revision !== this.revision) return
				if (!this.descriptor || payload.key !== this.descriptor.key) return
				if (payload.phase === MEDIA_PHASES.METADATA_READY) {
					const width = Number(payload.width)
					const height = Number(payload.height)
					const nextRatio = Number.isFinite(width) && width > 0
						&& Number.isFinite(height) && height > 0 ? width / height : null
					if (nextRatio && Math.abs(nextRatio - this.aspectRatio) > 0.01) {
						this.observedAspectRatio = nextRatio
						this.$emit('layout-change', {
							key: payload.key,
							revision: payload.revision,
							aspectRatio: nextRatio
						})
					}
				}
				this.acceptSafeState(payload.phase, payload.errorCategory || '')
			},
			acceptSafeState(phase, errorCategory) {
				if (!Object.values(MEDIA_PHASES).includes(phase)) return
				if (this.mediaPhase === phase && this.errorCategory === errorCategory) return
				this.mediaPhase = phase
				this.errorCategory = phase === MEDIA_PHASES.ERROR
					? String(errorCategory || 'UNKNOWN') : ''
				this.$emit('state-change', {
					key: this.descriptor?.key || '',
					revision: this.revision,
					phase,
					errorCategory: this.errorCategory || null
				})
				const diagnosticStage = MEDIA_STAGE_BY_PHASE[phase]
				if (diagnosticStage) this.safeDiagnostic(diagnosticStage, this.errorCategory)
			},
			safeDiagnostic(stage, errorCategory = '') {
				const detail = { scope: 'user-flow', stage, platform: 'ANDROID' }
				if (errorCategory) detail.errorCategory = errorCategory
				console.info('[ait-media]', JSON.stringify(detail))
			}
		}
	}
</script>

<script module="androidVideoRenderer" lang="renderjs">
	import { classifyHtmlMediaError, MEDIA_PHASES } from '../../../common/aichat/ai-media-presentation.js'

	export default {
		data() {
			return {
				root: null,
				host: null,
				video: null,
				observer: null,
				loadTimer: 0,
				listeners: [],
				config: null,
				owner: null,
				lastStateKey: ''
			}
		},
		mounted(event, instance, ownerInstance) {
			this.owner = ownerInstance || this.$ownerInstance || null
			this.root = ownerInstance?.$el || instance?.$el || this.$el || null
			this.connectHost()
			if (this.config?.active && this.config?.key && this.config?.src) this.observeOrLoad()
		},
		beforeDestroy() {
			this.cleanupMedia()
		},
		beforeUnmount() {
			this.cleanupMedia()
		},
		methods: {
			updateMedia(value, oldValue, ownerInstance, instance) {
				this.owner = ownerInstance || this.owner || this.$ownerInstance || null
				this.root = this.root || ownerInstance?.$el || instance?.$el || this.$el || null
				this.connectHost()
				const next = value || null
				const pauseRequested = this.config
					&& this.config.pauseRequest !== next?.pauseRequest
				const playRequested = this.config
					&& this.config.playRequest !== next?.playRequest
				const changed = !this.config
					|| this.config.revision !== next?.revision
					|| this.config.key !== next?.key
					|| this.config.src !== next?.src
				if (changed) this.cleanupMedia()
				this.config = next
				if (pauseRequested) this.video?.pause?.()
				if (playRequested && this.video) {
					try {
						const playResult = this.video.play()
						playResult?.catch?.(() => this.emitState(MEDIA_PHASES.READY))
					} catch (_) {
						this.emitState(MEDIA_PHASES.READY)
					}
				}
				if (!next || next.disposed) {
					this.cleanupMedia()
					return
				}
				if (!next.active) {
					this.video?.pause?.()
					return
				}
				if (changed && next.key && next.src) this.observeOrLoad()
			},
			connectHost() {
				if (!this.root) return
				const direct = this.root.matches?.('.android-video-render-host') ? this.root : null
				this.host = direct || this.root.querySelector?.('.android-video-render-host') || this.host
			},
			observeOrLoad() {
				if (!this.host || !this.config) return
				this.emitState(MEDIA_PHASES.OBSERVING)
				if (typeof IntersectionObserver === 'undefined') {
					this.startLoad()
					return
				}
				this.observer = new IntersectionObserver(entries => {
					if (!entries.some(entry => entry.isIntersecting)) return
					this.observer?.disconnect?.()
					this.observer = null
					this.startLoad()
				}, { root: null, rootMargin: '320px 0px', threshold: 0 })
				this.observer.observe(this.host)
			},
			startLoad() {
				if (!this.host || !this.config || this.video) return
				const video = document.createElement('video')
				video.className = 'android-rendered-video-element'
				video.controls = true
				video.playsInline = true
				video.preload = 'metadata'
				video.autoplay = false
				video.loop = false
				video.muted = false
				video.style.width = '100%'
				video.style.height = '100%'
				video.style.display = 'block'
				video.style.objectFit = 'contain'
				this.video = video

				const listen = (name, handler) => {
					video.addEventListener(name, handler)
					this.listeners.push([name, handler])
				}
				listen('loadedmetadata', () => {
					if (this.loadTimer) clearTimeout(this.loadTimer)
					this.loadTimer = 0
					this.emitState(MEDIA_PHASES.METADATA_READY, '', {
						width: Number(video.videoWidth || 0),
						height: Number(video.videoHeight || 0)
					})
				})
				listen('loadeddata', () => this.emitState(MEDIA_PHASES.READY))
				listen('canplay', () => this.emitState(MEDIA_PHASES.READY))
				listen('play', () => this.emitState(MEDIA_PHASES.PLAYING))
				listen('waiting', () => this.emitState(MEDIA_PHASES.WAITING))
				listen('pause', () => {
					if (!video.ended && video.readyState >= 2) this.emitState(MEDIA_PHASES.READY)
				})
				listen('ended', () => this.emitState(MEDIA_PHASES.READY))
				listen('error', () => this.emitState(
					MEDIA_PHASES.ERROR,
					classifyHtmlMediaError(video.error?.code)
				))

				this.host.appendChild(video)
				this.emitState(MEDIA_PHASES.LOADING)
				this.loadTimer = setTimeout(() => {
					if (video.readyState < 1) this.emitState(MEDIA_PHASES.ERROR, 'TIMEOUT')
				}, 20000)
				// 所有监听器和超时保护必须先建立，再暴露远端地址并触发加载，避免缓存命中时丢失首个事件。
				video.src = this.config.src
				video.load()
			},
			emitState(phase, errorCategory = '', extra = null) {
				if (!this.config || this.config.disposed) return
				const stateKey = `${this.config.revision}:${phase}:${errorCategory}`
				if (stateKey === this.lastStateKey && phase !== MEDIA_PHASES.METADATA_READY) return
				this.lastStateKey = stateKey
				this.owner?.callMethod?.('receiveAndroidVideoEvent', {
					key: this.config.key,
					revision: this.config.revision,
					phase,
					errorCategory: errorCategory || null,
					...(extra || {})
				})
			},
			cleanupMedia() {
				this.observer?.disconnect?.()
				this.observer = null
				if (this.loadTimer) clearTimeout(this.loadTimer)
				this.loadTimer = 0
				const video = this.video
				if (video) {
					video.pause()
					for (const [name, handler] of this.listeners) video.removeEventListener(name, handler)
					video.removeAttribute('src')
					video.load()
					video.remove()
				}
				this.listeners = []
				this.video = null
				this.lastStateKey = ''
			}
		}
	}
</script>

<style lang="scss" scoped>
	.android-chat-video { position: relative; width: 100%; max-width: 100%; aspect-ratio: var(--android-video-aspect, 1.7778); max-height: 56vh; overflow: hidden; border: 1px solid #313a35; border-radius: 12px; background: #101412; box-sizing: border-box; }
	.android-video-render-host { position: absolute; inset: 0; width: 100%; height: 100%; }
	.android-video-overlay { position: absolute; inset: 0; z-index: 2; padding: 20px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 9px; background: linear-gradient(145deg, #111714, #0b0f0d); color: #aebbb4; font-size: 13px; line-height: 1.5; text-align: center; box-sizing: border-box; }
	.android-video-overlay.is-error { color: #ffd0cc; }
	.android-video-overlay.is-ready { background: linear-gradient(145deg, rgba(17, 23, 20, .92), rgba(11, 15, 13, .9)); }
	.android-video-actions { display: flex; flex-wrap: wrap; justify-content: center; gap: 8px; }
	.android-video-action { min-height: 36px; margin: 0; padding: 0 13px; border: 1px solid #3d4b44; border-radius: 9px; background: #18201c; color: #dce5e0; font-size: 12px; line-height: 34px; }
	.android-video-action::after { border: 0; }
	.android-video-buffer { position: absolute; top: 10px; right: 10px; z-index: 3; width: 28px; height: 28px; display: flex; align-items: center; justify-content: center; border-radius: 50%; background: rgba(7, 12, 9, .76); }
	.android-video-buffer-dot { width: 12px; height: 12px; border: 2px solid rgba(143, 220, 190, .35); border-top-color: #8fdcbe; border-radius: 50%; animation: android-video-spin .8s linear infinite; }
	@keyframes android-video-spin { to { transform: rotate(360deg); } }
	@media (prefers-reduced-motion: reduce) { .android-video-buffer-dot { animation: none; } }
</style>
