<template>
	<!-- #ifdef H5 -->
	<view class="generated-image-ripple-stage" :class="{ 'is-visible': canvasVisible }" aria-hidden="true">
		<view
			ref="canvasHost"
			class="generated-image-ripple-native-host"
			aria-hidden="true"
		></view>
	</view>
	<!-- #endif -->
</template>

<script>
	import {
		buildGeneratedImageRipplePath,
		createGeneratedImageRippleRenderer,
		ensureGeneratedImageRippleCanvas,
		generatedImageRippleFinalSource,
		generatedImageRippleSource,
		GENERATED_IMAGE_RIPPLE_PATH_DURATION_MS,
		GENERATED_IMAGE_RIPPLE_TEXTURE_DURATION_MS
	} from './user-generated-image-ripple-render.js'

	export default {
		name: 'UserGeneratedImageRippleStage',
		props: {
			items: { type: Array, default: () => [] },
			activeIdentity: { type: String, default: '' },
			reducedMotion: { type: Boolean, default: false }
		},
		emits: ['visual-change', 'transitioning-change', 'settled', 'failure'],
		data() {
			return {
				renderer: null,
				nativeCanvasElement: null,
				resizeObserver: null,
				resizeListener: null,
				visibilityListener: null,
				currentTargetIdentity: '',
				visualIdentity: '',
				settledSource: '',
				requestSequence: 0,
				canvasReady: false,
				webglFailed: false,
				transitioning: false,
				reprimeWhenVisible: false
			}
		},
		computed: {
			rippleFrames() {
				return (Array.isArray(this.items) ? this.items : [])
					.map(item => Object.freeze({
						identity: String(item?.identity || '').trim(),
						source: generatedImageRippleSource(item),
						finalSource: generatedImageRippleFinalSource(item),
						width: Number(item?.attachment?.width || item?.attachment?.metadata?.width || 0),
						height: Number(item?.attachment?.height || item?.attachment?.metadata?.height || 0)
					}))
					.filter(frame => frame.identity && frame.source)
			},
			canvasVisible() {
				return this.canvasReady && !this.webglFailed && !this.reducedMotion
			}
		},
		watch: {
			activeIdentity(nextIdentity) {
				this.requestIdentity(nextIdentity)
			},
			items() {
				this.reconcileItems()
			},
			reducedMotion(reduced) {
				if (reduced) this.snapToActiveIdentity()
				else this.primeActiveIdentity()
			}
		},
		mounted() {
			// #ifdef H5
			this.$nextTick(() => this.initializeRenderer())
			// #endif
		},
		beforeUnmount() {
			this.destroyRenderer()
		},
		methods: {
			frameByIdentity(identity, useFinalSource = false) {
				const frame = this.rippleFrames.find(candidate => candidate.identity === identity)
				if (!frame) return null
				return Object.freeze({
					identity: frame.identity,
					source: useFinalSource ? (frame.finalSource || frame.source) : frame.source,
					width: frame.width,
					height: frame.height
				})
			},
			setTransitioning(value) {
				const next = Boolean(value)
				if (next === this.transitioning) return
				this.transitioning = next
				this.$emit('transitioning-change', next)
			},
			setVisualIdentity(identity) {
				const next = String(identity || '')
				if (!next || next === this.visualIdentity) return
				this.visualIdentity = next
				this.$emit('visual-change', next)
			},
			resolveCanvasElement() {
				const host = this.$refs.canvasHost?.$el || this.$refs.canvasHost
				const canvas = ensureGeneratedImageRippleCanvas(
					host,
					typeof document !== 'undefined' ? document : null
				)
				this.nativeCanvasElement = canvas
				return canvas
			},
			removeNativeCanvasElement() {
				const canvas = this.nativeCanvasElement
				this.nativeCanvasElement = null
				if (typeof canvas?.remove === 'function') {
					canvas?.remove?.()
					return
				}
				canvas?.parentNode?.removeChild?.(canvas)
			},
			initializeRenderer() {
				if (this.renderer || this.webglFailed) return
				const canvas = this.resolveCanvasElement()
				if (!canvas) {
					this.handleRendererFailure(new Error('Ripple canvas is unavailable'))
					return
				}
				try {
					this.renderer = createGeneratedImageRippleRenderer({
						canvas,
						onVisualChange: frame => this.setVisualIdentity(frame?.identity),
						onSettled: frame => {
							this.canvasReady = true
							this.settledSource = String(frame?.source || '')
							this.setVisualIdentity(frame?.identity)
							this.$emit('settled', frame?.identity || '')
						},
						onFailure: error => this.handleRendererFailure(error)
					})
					this.observeStageSize()
					this.observeVisibility()
					this.primeActiveIdentity()
				} catch (error) {
					this.handleRendererFailure(error)
				}
			},
			observeStageSize() {
				if (typeof ResizeObserver === 'function') {
					this.resizeObserver = new ResizeObserver(() => {
						try { this.renderer?.resize?.() }
						catch (error) { this.handleRendererFailure(error) }
					})
					this.resizeObserver.observe(this.$el)
					return
				}
				this.resizeListener = () => {
					try { this.renderer?.resize?.() }
					catch (error) { this.handleRendererFailure(error) }
				}
				globalThis.addEventListener?.('resize', this.resizeListener)
			},
			observeVisibility() {
				this.visibilityListener = () => {
					if (document.visibilityState === 'hidden') {
						this.requestSequence += 1
						this.renderer?.cancel?.()
						this.canvasReady = false
						this.reprimeWhenVisible = true
						this.setTransitioning(false)
						this.setVisualIdentity(this.activeIdentity)
						return
					}
					if (this.reprimeWhenVisible) {
						this.reprimeWhenVisible = false
						this.primeActiveIdentity()
					}
				}
				document.addEventListener('visibilitychange', this.visibilityListener)
			},
			async primeActiveIdentity() {
				const identity = String(this.activeIdentity || '').trim()
				const frame = this.frameByIdentity(identity, true)
				this.currentTargetIdentity = identity
				this.setVisualIdentity(identity)
				this.setTransitioning(false)
				if (!this.renderer || !frame || this.reducedMotion || this.webglFailed) {
					this.canvasReady = false
					return
				}
				const sequence = ++this.requestSequence
				this.canvasReady = false
				const result = await this.renderer.prime(frame)
				if (sequence !== this.requestSequence || !result) return
				this.canvasReady = true
			},
			async requestIdentity(value) {
				const identity = String(value || '').trim()
				if (!identity) return
				if (!this.renderer || this.reducedMotion || this.webglFailed) {
					this.currentTargetIdentity = identity
					this.snapToActiveIdentity()
					return
				}
				if (!this.currentTargetIdentity || !this.canvasReady) {
					this.currentTargetIdentity = identity
					await this.primeActiveIdentity()
					return
				}
				if (identity === this.currentTargetIdentity) return

				// 参考项目从上一段纹理正在进入的目标继续计算路径，而不是退回最初点击的位置。
				const startIdentity = this.visualIdentity || this.currentTargetIdentity
				const framesSnapshot = this.rippleFrames.map(frame => Object.freeze({
					identity: frame.identity,
					source: frame.source,
					width: frame.width,
					height: frame.height
				}))
				const path = buildGeneratedImageRipplePath(
					framesSnapshot, startIdentity, identity)
				const finalFrame = this.frameByIdentity(identity, true)
				if (path.length && finalFrame) path[path.length - 1] = finalFrame
				this.currentTargetIdentity = identity
				if (!path.length) {
					await this.primeActiveIdentity()
					return
				}

				const sequence = ++this.requestSequence
				this.setTransitioning(true)
				const result = await this.renderer.transitionPath(path, {
					pathDuration: GENERATED_IMAGE_RIPPLE_PATH_DURATION_MS,
					textureDuration: GENERATED_IMAGE_RIPPLE_TEXTURE_DURATION_MS
				})
				if (sequence !== this.requestSequence) return
				this.setTransitioning(false)
				if (result?.identity === identity) {
					this.canvasReady = true
					this.setVisualIdentity(identity)
					this.reprimeIfFinalSourceChanged(result.source)
				}
			},
			reprimeIfFinalSourceChanged(renderedSource = this.settledSource) {
				const finalFrame = this.frameByIdentity(this.activeIdentity, true)
				if (!finalFrame || !this.canvasReady || this.transitioning
					|| finalFrame.source === renderedSource) return
				void this.primeActiveIdentity()
			},
			snapToActiveIdentity() {
				this.requestSequence += 1
				this.renderer?.cancel?.()
				this.currentTargetIdentity = String(this.activeIdentity || '')
				this.canvasReady = false
				this.settledSource = ''
				this.setTransitioning(false)
				this.setVisualIdentity(this.activeIdentity)
			},
			reconcileItems() {
				const active = String(this.activeIdentity || '')
				if (!active || !this.frameByIdentity(active)) {
					this.snapToActiveIdentity()
					return
				}
				if (!this.frameByIdentity(this.visualIdentity)) {
					this.setVisualIdentity(active)
				}
				if (!this.currentTargetIdentity) this.currentTargetIdentity = active
				this.reprimeIfFinalSourceChanged()
			},
			handleRendererFailure(error) {
				if (this.webglFailed) return
				const message = error?.message || 'WebGL ripple unavailable'
				globalThis.console?.warn?.(
					'[GeneratedImageRipple] Falling back to the static image:',
					message
				)
				this.webglFailed = true
				this.canvasReady = false
				this.settledSource = ''
				this.setTransitioning(false)
				this.releaseObservers()
				const renderer = this.renderer
				this.renderer = null
				renderer?.destroy?.()
				this.removeNativeCanvasElement()
				this.setVisualIdentity(this.activeIdentity)
				this.$emit('failure', message)
			},
			releaseObservers() {
				this.resizeObserver?.disconnect?.()
				this.resizeObserver = null
				if (this.resizeListener) {
					globalThis.removeEventListener?.('resize', this.resizeListener)
					this.resizeListener = null
				}
				if (this.visibilityListener && typeof document !== 'undefined') {
					document.removeEventListener('visibilitychange', this.visibilityListener)
					this.visibilityListener = null
				}
			},
			destroyRenderer() {
				this.requestSequence += 1
				this.releaseObservers()
				const renderer = this.renderer
				this.renderer = null
				renderer?.destroy?.()
				this.removeNativeCanvasElement()
				this.setTransitioning(false)
			}
		}
	}
</script>

<style lang="scss" scoped>
	.generated-image-ripple-stage { position: absolute; inset: 0; z-index: 2; overflow: hidden; background: #000; opacity: 0; pointer-events: none; transition: opacity 120ms ease; }
	.generated-image-ripple-stage.is-visible { opacity: 1; }
	.generated-image-ripple-native-host { width: 100%; height: 100%; display: block; }
	.generated-image-ripple-native-host :deep(.generated-image-ripple-native-canvas) { width: 100%; height: 100%; display: block; background: #000; pointer-events: none; }

	@media (prefers-reduced-motion: reduce) {
		.generated-image-ripple-stage { display: none; transition: none; }
	}
</style>
