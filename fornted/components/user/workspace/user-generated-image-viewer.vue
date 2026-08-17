<template>
	<!-- #ifdef H5 -->
	<view
		v-if="open"
		ref="dialog"
		class="generated-image-viewer is-h5"
		role="dialog"
		aria-modal="true"
		aria-label="图片查看器"
		tabindex="-1"
		@keydown.esc.prevent="$emit('close')"
		@keydown.left.prevent="selectPrevious"
		@keydown.right.prevent="selectNext"
		@keydown.tab.prevent="trapFocus"
	>
		<view class="viewer-toolbar">
			<button class="viewer-button" type="button" aria-label="关闭图片查看器" @click="$emit('close')">
				<text aria-hidden="true">×</text>
			</button>
			<text class="viewer-counter" role="status">{{ presentedIndex + 1 }} / {{ items.length }}</text>
			<button
				class="viewer-button is-download"
				type="button"
				:disabled="downloadBusy || rippleTransitioning || !activeDownloadReady"
				aria-label="下载当前图片"
				@click="$emit('download', activeItem)"
			>
				<text>{{ downloadBusy ? '下载中' : '下载' }}</text>
			</button>
		</view>

		<view class="viewer-body">
			<scroll-view class="viewer-thumbnails" scroll-x scroll-y aria-label="会话图片">
				<view class="viewer-thumbnail-list">
					<button
						v-if="hasMoreBefore"
						class="viewer-load-older"
						type="button"
						:disabled="loadingBefore"
						@click="$emit('request-older')"
					>
						{{ loadingBefore ? '加载中' : '更早图片' }}
					</button>
					<button
						v-for="(item, index) in items"
						:key="item.identity"
						class="viewer-thumbnail"
						:class="{ 'is-active': item.identity === presentedIdentity }"
						type="button"
						:aria-label="`第 ${index + 1} 张图片，共 ${items.length} 张`"
						:aria-current="item.identity === presentedIdentity ? 'true' : undefined"
						@click="selectIdentity(item.identity, index)"
					>
						<image :src="displaySource(item)" mode="aspectFill" lazy-load aria-hidden="true" />
					</button>
				</view>
			</scroll-view>

			<view class="viewer-stage" @wheel="handleH5Wheel">
				<button
					class="viewer-navigation is-previous"
					type="button"
					:disabled="!canSelectPrevious"
					aria-label="上一张图片"
					@click="selectPrevious"
				>
					<svg class="viewer-navigation-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
						<path
							fill="none"
							stroke="currentColor"
							stroke-linecap="round"
							stroke-linejoin="round"
							stroke-width="2"
							d="m15 18l-6-6l6-6"
						/>
					</svg>
				</button>
				<view class="viewer-media-frame">
					<image
						v-if="activeItem && !activeImageFailed"
						:key="activeImageRenderKey"
						class="viewer-active-image"
						:src="displaySource(activeItem)"
						mode="aspectFit"
						:aria-label="`第 ${activeIndex + 1} 张图片，共 ${items.length} 张`"
						@error="handleViewerImageError(activeItem)"
					/>
					<user-generated-image-ripple-stage
						v-if="activeItem && !activeImageFailed"
						:items="items"
						:active-identity="activeIdentity"
						:reduced-motion="reducedMotion"
						@visual-change="handleRippleVisualChange"
						@transitioning-change="handleRippleTransitioningChange"
						@settled="handleRippleSettled"
						@failure="handleRippleFailure"
					/>
					<view v-else class="viewer-empty" role="status">
						<text>{{ activeItem ? '图片加载失败' : '暂无可查看图片' }}</text>
						<button v-if="activeItem" type="button" @click="retryViewerImage(activeItem)">重新加载</button>
					</view>
				</view>
				<button
					class="viewer-navigation is-next"
					type="button"
					:disabled="!canSelectNext"
					aria-label="下一张图片"
					@click="selectNext"
				>
					<svg class="viewer-navigation-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
						<path
							fill="none"
							stroke="currentColor"
							stroke-linecap="round"
							stroke-linejoin="round"
							stroke-width="2"
							d="m9 18l6-6l-6-6"
						/>
					</svg>
				</button>
			</view>
		</view>
		<view v-if="loadingBefore || error" class="viewer-status" :role="error ? 'alert' : 'status'">
			<text>{{ error || '正在加载更早图片…' }}</text>
			<button v-if="error && hasMoreBefore" type="button" @click="$emit('request-older')">重试</button>
		</view>
	</view>
	<!-- #endif -->

	<!-- #ifdef APP-PLUS -->
	<view v-if="open" class="generated-image-viewer is-android" role="dialog" aria-label="图片查看器">
		<view class="viewer-toolbar is-android-safe">
			<button class="viewer-button" type="button" aria-label="关闭图片查看器" @click="$emit('close')">×</button>
			<text class="viewer-counter">{{ activeIndex + 1 }} / {{ items.length }}</text>
			<button
				class="viewer-button is-download"
				type="button"
				:disabled="downloadBusy || !activeDownloadReady"
				aria-label="下载当前图片"
				@click="$emit('download', activeItem)"
			>
				<uni-icons type="download" size="18" color="#f2fff9" aria-hidden="true" />
				<text>{{ downloadBusy ? '下载中' : '下载' }}</text>
			</button>
		</view>

		<swiper
			class="viewer-swiper"
			:current="androidSwiperCurrent"
			@change="handleAndroidSwiperChange"
		>
			<swiper-item v-for="item in androidWindowItems" :key="item.identity">
				<view class="viewer-android-stage">
					<block v-if="displaySource(item)">
						<image
							class="viewer-active-image"
							:src="displaySource(item)"
							mode="aspectFit"
						/>
						<view
							v-if="sourceStatus(item) !== 'FINAL_READY'"
							class="viewer-quality-status"
							:role="sourceStatus(item) === 'ERROR' ? 'alert' : 'status'"
						>
							<text>{{ sourceStatus(item) === 'ERROR' ? '高清图片加载失败' : '高清图片加载中…' }}</text>
							<button
								v-if="sourceStatus(item) === 'ERROR'"
								type="button"
								@click="$emit('retry', item)"
							>
								重试
							</button>
						</view>
					</block>
					<view v-else class="viewer-empty" role="status">
						<text>{{ sourceStatus(item) === 'ERROR' ? '图片加载失败' : '图片加载中…' }}</text>
						<button
							v-if="sourceStatus(item) === 'ERROR'"
							type="button"
							@click="$emit('retry', item)"
						>
							重新加载
						</button>
					</view>
				</view>
			</swiper-item>
		</swiper>

		<view class="viewer-android-footer">
			<scroll-view class="viewer-mobile-thumbnails" scroll-x :show-scrollbar="false">
				<view class="viewer-mobile-thumbnail-track">
					<button
						v-for="item in androidThumbnailItems"
						:key="item.identity"
						class="viewer-thumbnail"
						:class="{ 'is-active': item.identity === activeIdentity }"
						type="button"
						@click="selectIdentity(item.identity, items.indexOf(item))"
					>
						<image v-if="displaySource(item)" :src="displaySource(item)" mode="aspectFill" />
					</button>
				</view>
			</scroll-view>
			<button
				v-if="hasMoreBefore"
				class="viewer-load-older is-android"
				type="button"
				:disabled="loadingBefore"
				@click="$emit('request-older')"
			>
				{{ loadingBefore ? '加载中' : '更早图片' }}
			</button>
			<text v-if="error" class="viewer-android-error" role="alert">{{ error }}</text>
		</view>
	</view>
	<!-- #endif -->
</template>

<script>
	import { activeGeneratedImageIndex } from '@/common/aichat/ai-conversation-image-viewer.js'
	import UserGeneratedImageRippleStage from './user-generated-image-ripple-stage.vue'

	const ANDROID_WINDOW_RADIUS = 2
	const ANDROID_THUMBNAIL_RADIUS = 4
	const H5_WHEEL_SWITCH_THRESHOLD = 72
	const H5_WHEEL_RESET_DELAY_MS = 160
	const H5_WHEEL_NAVIGATION_COOLDOWN_MS = 260

	export default {
		name: 'UserGeneratedImageViewer',
		components: { UserGeneratedImageRippleStage },
		props: {
			open: { type: Boolean, default: false },
			items: { type: Array, default: () => [] },
			activeIdentity: { type: String, default: '' },
			sourceByIdentity: { type: Object, default: () => ({}) },
			androidClient: { type: Boolean, default: false },
			hasMoreBefore: { type: Boolean, default: false },
			loadingBefore: { type: Boolean, default: false },
			downloadBusy: { type: Boolean, default: false },
			reducedMotion: { type: Boolean, default: false },
			error: { type: String, default: '' }
		},
		emits: ['close', 'select', 'request-older', 'download', 'retry'],
		data() {
			return {
				previouslyFocusedElement: null,
				previousBodyOverflow: '',
				olderRequestKey: '',
				wheelDeltaY: 0,
				wheelResetTimer: null,
				lastWheelNavigationAt: 0,
				presentedIdentity: '',
				rippleTransitioning: false,
				failedIdentities: Object.freeze({}),
				imageRetryRevisions: Object.freeze({})
			}
		},
		computed: {
			activeIndex() {
				const index = activeGeneratedImageIndex(this.items, this.activeIdentity)
				return index >= 0 ? index : 0
			},
			activeItem() {
				return this.items[this.activeIndex] || null
			},
			presentedIndex() {
				const index = activeGeneratedImageIndex(this.items, this.presentedIdentity)
				return index >= 0 ? index : this.activeIndex
			},
			activeImageFailed() {
				return Boolean(this.activeItem
					&& this.failedIdentities[this.activeItem.identity])
			},
			activeImageRenderKey() {
				const identity = this.activeItem?.identity || 'empty'
				return `${identity}:${this.imageRetryRevisions[identity] || 0}`
			},
			canSelectPrevious() {
				return this.activeIndex > 0
			},
			canSelectNext() {
				return this.activeIndex >= 0 && this.activeIndex < this.items.length - 1
			},
			androidWindowStart() {
				return Math.max(0, this.activeIndex - ANDROID_WINDOW_RADIUS)
			},
			androidWindowItems() {
				return this.items.slice(
					this.androidWindowStart,
					Math.min(this.items.length, this.activeIndex + ANDROID_WINDOW_RADIUS + 1)
				)
			},
			androidSwiperCurrent() {
				return this.activeIndex - this.androidWindowStart
			},
			androidThumbnailItems() {
				return this.items.slice(
					Math.max(0, this.activeIndex - ANDROID_THUMBNAIL_RADIUS),
					Math.min(this.items.length, this.activeIndex + ANDROID_THUMBNAIL_RADIUS + 1)
				)
			},
			activeDownloadReady() {
				if (!this.activeItem || !this.displaySource(this.activeItem)) return false
				if (this.androidClient) {
					return this.sourceStatus(this.activeItem) === 'FINAL_READY'
				}
				return String(this.activeItem.attachment?.phase || '').toUpperCase() === 'FINAL'
			}
		},
		watch: {
			open(nextOpen) {
				if (nextOpen) {
					this.presentedIdentity = this.activeIdentity
					this.prepareH5Dialog()
					this.requestOlderNearBoundary()
				}
				else this.releaseH5Dialog()
			},
			activeIdentity() {
				if (this.reducedMotion || !this.presentedIdentity) {
					this.presentedIdentity = this.activeIdentity
				}
				this.requestOlderNearBoundary()
			},
			items() {
				if (!this.items.some(item => item.identity === this.presentedIdentity)) {
					this.presentedIdentity = this.activeIdentity
				}
				this.requestOlderNearBoundary()
			},
			reducedMotion(value) {
				if (!value) return
				this.presentedIdentity = this.activeIdentity
				this.rippleTransitioning = false
			}
		},
		mounted() {
			if (this.open) this.prepareH5Dialog()
		},
		beforeUnmount() {
			this.releaseH5Dialog()
		},
		methods: {
			handleRippleVisualChange(identity) {
				if (!this.items.some(item => item.identity === identity)) return
				this.presentedIdentity = identity
			},
			handleRippleTransitioningChange(value) {
				this.rippleTransitioning = Boolean(value)
			},
			handleRippleSettled(identity) {
				this.handleRippleVisualChange(identity)
			},
			handleRippleFailure() {
				this.presentedIdentity = this.activeIdentity
				this.rippleTransitioning = false
			},
			displaySource(item) {
				if (!item) return ''
				if (this.androidClient) {
					return String(this.sourceByIdentity[item.identity]?.src || '')
				}
				return String(item.displaySrc || item.attachment?.url || '')
			},
			sourceStatus(item) {
				return String(this.sourceByIdentity[item?.identity]?.status || 'WAITING_REMOTE')
			},
			handleViewerImageError(item) {
				if (!item?.identity) return
				this.failedIdentities = Object.freeze({
					...this.failedIdentities,
					[item.identity]: true
				})
			},
			retryViewerImage(item) {
				if (!item?.identity) return
				const failedIdentities = { ...this.failedIdentities }
				delete failedIdentities[item.identity]
				this.failedIdentities = Object.freeze(failedIdentities)
				this.imageRetryRevisions = Object.freeze({
					...this.imageRetryRevisions,
					[item.identity]: Number(this.imageRetryRevisions[item.identity] || 0) + 1
				})
				this.$emit('retry', item)
			},
			selectIdentity(identity, index = -1) {
				if (!identity || identity === this.activeIdentity) return
				this.$emit('select', identity)
				if (index >= 0 && index <= 2) this.requestOlderNearBoundary(index)
			},
			selectPrevious() {
				if (!this.canSelectPrevious) return
				const index = this.activeIndex - 1
				this.selectIdentity(this.items[index]?.identity, index)
			},
			selectNext() {
				if (!this.canSelectNext) return
				const index = this.activeIndex + 1
				this.selectIdentity(this.items[index]?.identity, index)
			},
			handleH5Wheel(event) {
				// Ctrl/Command + 滚轮属于浏览器缩放，查看器不能阻止或复用该手势。
				if (event?.ctrlKey || event?.metaKey) return
				const deltaY = Number(event?.deltaY)
				const deltaX = Number(event?.deltaX)
				if (!Number.isFinite(deltaY) || Math.abs(deltaY) <= Math.abs(deltaX)) return

				event?.preventDefault?.()
				const now = Date.now()
				if (now - this.lastWheelNavigationAt < H5_WHEEL_NAVIGATION_COOLDOWN_MS) return

				this.wheelDeltaY += deltaY
				if (this.wheelResetTimer) clearTimeout(this.wheelResetTimer)
				this.wheelResetTimer = setTimeout(() => {
					this.wheelDeltaY = 0
					this.wheelResetTimer = null
				}, H5_WHEEL_RESET_DELAY_MS)
				if (Math.abs(this.wheelDeltaY) < H5_WHEEL_SWITCH_THRESHOLD) return

				this.wheelDeltaY = 0
				this.lastWheelNavigationAt = now
				const navigation = deltaY > 0 ? this.selectNext : this.selectPrevious
				navigation.call(this)
			},
			handleAndroidSwiperChange(event) {
				const localIndex = Number(event?.detail?.current)
				if (!Number.isInteger(localIndex)) return
				const globalIndex = this.androidWindowStart + localIndex
				this.selectIdentity(this.items[globalIndex]?.identity, globalIndex)
			},
			requestOlderNearBoundary(forcedIndex = this.activeIndex) {
				if (!this.open || forcedIndex > 2 || !this.hasMoreBefore || this.loadingBefore) return
				const key = `${this.items.length}:${this.activeIdentity}`
				if (this.olderRequestKey === key) return
				this.olderRequestKey = key
				this.$emit('request-older')
			},
			prepareH5Dialog() {
				// #ifdef H5
				if (!this.previouslyFocusedElement) {
					this.previouslyFocusedElement = document.activeElement
					this.previousBodyOverflow = document.body.style.overflow
					document.body.style.overflow = 'hidden'
				}
				this.$nextTick(() => {
					const dialog = this.$refs.dialog?.$el || this.$refs.dialog
					dialog?.focus?.({ preventScroll: true })
					this.requestOlderNearBoundary()
				})
				// #endif
			},
			releaseH5Dialog() {
				// #ifdef H5
				if (this.previouslyFocusedElement) {
					document.body.style.overflow = this.previousBodyOverflow
					this.previouslyFocusedElement.focus?.({ preventScroll: true })
				}
				this.previouslyFocusedElement = null
				this.previousBodyOverflow = ''
				// #endif
				this.olderRequestKey = ''
				if (this.wheelResetTimer) clearTimeout(this.wheelResetTimer)
				this.wheelDeltaY = 0
				this.wheelResetTimer = null
				this.lastWheelNavigationAt = 0
				this.presentedIdentity = ''
				this.rippleTransitioning = false
				this.failedIdentities = Object.freeze({})
				this.imageRetryRevisions = Object.freeze({})
			},
			trapFocus(event) {
				// #ifdef H5
				const dialog = this.$refs.dialog?.$el || this.$refs.dialog
				const focusable = Array.from(dialog?.querySelectorAll?.(
					'button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])'
				) || [])
				if (!focusable.length) {
					dialog?.focus?.()
					return
				}
				const first = focusable[0]
				const last = focusable[focusable.length - 1]
				if (event.shiftKey && document.activeElement === first) last.focus()
				else if (!event.shiftKey && document.activeElement === last) first.focus()
				else if (event.shiftKey) {
					const current = Math.max(0, focusable.indexOf(document.activeElement))
					focusable[Math.max(0, current - 1)].focus()
				} else {
					const current = Math.max(-1, focusable.indexOf(document.activeElement))
					focusable[Math.min(focusable.length - 1, current + 1)].focus()
				}
				// #endif
			}
		}
	}
</script>

<style lang="scss" scoped>
	.generated-image-viewer { --viewer-control-background: rgba(24, 57, 46, .88); --viewer-control-background-hover: rgba(34, 78, 62, .94); --viewer-control-border: rgba(117, 223, 183, .38); position: fixed; inset: 0; z-index: 120; overflow: hidden; background: #000; color: #f5f7f6; }
	.generated-image-viewer.is-h5 { display: grid; grid-template-rows: auto minmax(0, 1fr); }
	.viewer-toolbar { min-height: 72px; padding: 12px 18px; display: grid; grid-template-columns: minmax(88px, 1fr) auto minmax(88px, 1fr); align-items: center; gap: 12px; border-bottom: 1px solid rgba(255, 255, 255, .1); background: rgba(0, 0, 0, .88); box-sizing: border-box; }
	.viewer-toolbar.is-android-safe { padding-top: calc(12px + env(safe-area-inset-top)); }
	.viewer-button { min-width: 48px; min-height: 44px; margin: 0; padding: 0 14px; justify-self: start; border: 1px solid var(--viewer-control-border); border-radius: 999px; background: var(--viewer-control-background); color: #f2fff9; box-shadow: 0 8px 24px rgba(0, 0, 0, .24), inset 0 1px 0 rgba(255, 255, 255, .06); font-size: 18px; line-height: 42px; backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px); transition: background-color 140ms ease, border-color 140ms ease, box-shadow 140ms ease, transform 140ms ease; }
	.viewer-button.is-download { justify-self: end; font-size: 14px; }
	.generated-image-viewer.is-android .viewer-button.is-download { display: inline-flex; align-items: center; justify-content: center; gap: 6px; line-height: 1; }
	.viewer-button::after { border: 0; }
	.viewer-button:active { transform: scale(.96); }
	.viewer-button:disabled { opacity: .42; }
	.generated-image-viewer.is-android .viewer-button.is-download:disabled { opacity: .62; }
	.viewer-button:focus-visible,
	.viewer-thumbnail:focus-visible,
	.viewer-navigation:focus-visible,
	.viewer-load-older:focus-visible { outline: 2px solid #fff; outline-offset: 2px; }
	.viewer-counter { color: #d7ddda; font-size: 13px; font-variant-numeric: tabular-nums; text-align: center; }
	.viewer-body { min-width: 0; min-height: 0; position: relative; overflow: hidden; }
	.viewer-thumbnails { width: 92px; position: absolute; top: 0; bottom: 0; left: 0; z-index: 4; border-right: 1px solid rgba(255, 255, 255, .1); background: #080808; }
	.viewer-thumbnail-list { padding: 12px; display: flex; flex-direction: column; align-items: center; gap: 10px; box-sizing: border-box; }
	.viewer-thumbnail { width: 58px; height: 58px; min-height: 58px; margin: 0; padding: 3px; overflow: hidden; border: 1px solid transparent; border-radius: 12px; background: #242424; opacity: .7; box-sizing: border-box; transition: opacity 140ms ease, border-color 140ms ease, transform 140ms ease; }
	.viewer-thumbnail::after { border: 0; }
	.viewer-thumbnail.is-active { border-color: #fff; opacity: 1; transform: scale(1.06); }
	.viewer-thumbnail image { width: 100%; height: 100%; display: block; border-radius: 8px; }
	.viewer-load-older { min-height: 40px; margin: 0; padding: 4px 8px; border: 1px solid rgba(255, 255, 255, .16); border-radius: 10px; background: #1b1b1b; color: #d9dfdc; font-size: 11px; line-height: 1.25; }
	.viewer-load-older::after { border: 0; }
	.viewer-stage { width: 100%; height: 100%; min-width: 0; min-height: 0; position: relative; display: grid; place-items: center; overflow: hidden; padding: 24px 80px; box-sizing: border-box; }
	.viewer-media-frame { width: min(calc(100% - 224px), 1440px); height: min(calc(100% - 48px), 900px); min-width: 0; min-height: 0; position: relative; display: grid; place-items: center; overflow: hidden; background: #000; }
	.viewer-active-image { width: 100%; height: 100%; display: block; object-fit: contain; }
	.generated-image-viewer.is-h5 .viewer-media-frame > .viewer-active-image { position: relative; z-index: 1; }
	.viewer-navigation { width: 48px; height: 48px; min-height: 48px; position: absolute; top: 50%; z-index: 2; margin: 0; padding: 0; display: flex; align-items: center; justify-content: center; border: 1px solid var(--viewer-control-border); border-radius: 50%; background: var(--viewer-control-background); color: #f2fff9; box-shadow: 0 8px 24px rgba(0, 0, 0, .24), inset 0 1px 0 rgba(255, 255, 255, .06); line-height: 1; transform: translateY(-50%); backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px); transition: background-color 140ms ease, border-color 140ms ease, box-shadow 140ms ease, transform 140ms ease; }
	.viewer-navigation-icon { width: 24px; height: 24px; display: block; flex: 0 0 24px; }
	.viewer-navigation::after { border: 0; }
	.viewer-navigation.is-previous { left: 110px; }
	.viewer-navigation.is-next { right: 18px; }
	.viewer-navigation:disabled { opacity: .28; }
	.viewer-navigation:not(:disabled):active { transform: translateY(-50%) scale(.96); }
	.viewer-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; color: #aeb5b1; }
	.viewer-status { min-height: 42px; position: absolute; right: 18px; bottom: 18px; left: 110px; z-index: 3; display: flex; align-items: center; justify-content: center; gap: 10px; border-radius: 12px; background: rgba(24, 24, 24, .92); color: #dfe4e1; font-size: 12px; }
	.viewer-status button { min-height: 34px; margin: 0; padding: 0 12px; border-radius: 9px; }
	.viewer-swiper { height: calc(100vh - 72px - 112px - env(safe-area-inset-top) - env(safe-area-inset-bottom)); }
	.viewer-android-stage { width: 100%; height: 100%; position: relative; display: flex; align-items: center; justify-content: center; }
	.viewer-android-stage > .viewer-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; }
	.viewer-quality-status { position: absolute; right: 16px; bottom: 14px; left: 16px; display: flex; align-items: center; justify-content: center; gap: 8px; padding: 8px 12px; border-radius: 12px; background: rgba(20, 20, 20, .86); color: #e6ebe8; font-size: 12px; }
	.viewer-quality-status button { min-height: 34px; margin: 0; padding: 0 12px; border-radius: 9px; }
	.viewer-android-footer { min-height: 112px; padding: 10px 12px calc(10px + env(safe-area-inset-bottom)); display: flex; flex-direction: column; align-items: stretch; gap: 8px; box-sizing: border-box; }
	.viewer-mobile-thumbnails { width: 100%; height: 64px; }
	.viewer-mobile-thumbnail-track { min-width: 100%; display: inline-flex; justify-content: flex-start; gap: 8px; }
	.viewer-mobile-thumbnail-track .viewer-thumbnail { width: 56px; height: 56px; min-width: 56px; min-height: 56px; flex: 0 0 56px; }
	.viewer-load-older.is-android { align-self: center; }
	.viewer-android-error { color: #ffaaa4; font-size: 12px; text-align: center; }

	@media (hover: hover) {
		.viewer-button:not(:disabled):hover,
		.viewer-navigation:not(:disabled):hover { border-color: rgba(143, 232, 197, .62); background: var(--viewer-control-background-hover); box-shadow: 0 10px 28px rgba(0, 0, 0, .3), 0 0 0 1px rgba(117, 223, 183, .08); }
	}

	@media screen and (max-width: 767px) {
		.viewer-toolbar { min-height: 64px; padding: 10px 12px; }
		.viewer-stage { padding: 12px 48px 98px; }
		.viewer-media-frame { width: min(100%, 720px); height: min(100%, 720px); }
		.viewer-thumbnails { width: auto; height: 86px; top: auto; right: 0; bottom: 0; border-top: 1px solid rgba(255, 255, 255, .1); border-right: 0; }
		.viewer-thumbnail-list { min-width: max-content; padding: 12px; flex-direction: row; }
		.viewer-navigation { width: 40px; height: 40px; min-height: 40px; }
		.viewer-navigation.is-previous { left: 6px; }
		.viewer-navigation.is-next { right: 6px; }
		.viewer-status { right: 12px; bottom: 96px; left: 12px; }
	}

	@media (prefers-reduced-motion: reduce) {
		.viewer-thumbnail { transition: none; }
	}
</style>
