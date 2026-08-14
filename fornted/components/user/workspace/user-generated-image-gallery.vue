<template>
	<view
		v-if="hasPresentation"
		class="generated-image-gallery"
		:style="galleryStyle"
		role="group"
		aria-label="生成的图片"
	>
		<view
			v-if="presentation.allItems && presentation.allItems.length"
			class="generated-image-layout"
			:class="{ 'has-secondary-rail': railItems.length > 0 }"
		>
			<view
				class="generated-image-stage"
				:class="layoutClass"
			>
				<view
					v-for="attachment in stageItems"
					:key="attachment.attachmentId || imageIdentity(attachment)"
					class="generated-image-tile"
					:class="tileClasses(attachment)"
				>
					<!-- #ifndef APP-PLUS -->
					<button
						class="generated-image-action"
						type="button"
						:aria-label="imageAccessibleName(attachment)"
						@click="emitOpen(attachment)"
					>
						<image
							class="generated-image-element"
							:src="attachment.url"
							mode="aspectFill"
							aria-hidden="true"
							@load="$emit('image-load', { attachment, event: $event })"
						/>
					</button>
					<!-- #endif -->
					<!-- #ifdef APP-PLUS -->
					<user-android-chat-image
						:attachment="attachment"
						:local-src="androidSource(attachment).src"
						:source-status="androidSource(attachment).status"
						:diagnostic-run-id="androidSource(attachment).diagnosticRunId"
						:managed-local-source="true"
						variant="THUMBNAIL"
						:aspect-ratio="aspectRatio"
						@layout-change="$emit('android-layout-change', $event)"
						@preview="emitOpen(attachment, $event)"
						@retry="$emit('android-retry', { message, attachment })"
					/>
					<!-- #endif -->
				</view>
			</view>

			<scroll-view
				v-if="railItems.length"
				class="generated-image-secondary"
				scroll-x
				scroll-y
				:show-scrollbar="false"
				aria-label="更多生成图片"
			>
				<view class="generated-image-secondary-track">
					<view
						v-for="attachment in railItems"
						:key="attachment.attachmentId || imageIdentity(attachment)"
						class="generated-image-tile is-secondary"
						:class="tileClasses(attachment)"
					>
						<!-- #ifndef APP-PLUS -->
						<button
							class="generated-image-action"
							type="button"
							:aria-label="imageAccessibleName(attachment)"
							@click="emitOpen(attachment)"
						>
							<image
								class="generated-image-element"
								:src="attachment.url"
								mode="aspectFill"
								aria-hidden="true"
								@load="$emit('image-load', { attachment, event: $event })"
							/>
						</button>
						<!-- #endif -->
						<!-- #ifdef APP-PLUS -->
						<user-android-chat-image
							:attachment="attachment"
							:local-src="androidSource(attachment).src"
							:source-status="androidSource(attachment).status"
							:diagnostic-run-id="androidSource(attachment).diagnosticRunId"
							:managed-local-source="true"
							variant="THUMBNAIL"
							:aspect-ratio="1"
							@layout-change="$emit('android-layout-change', $event)"
							@preview="emitOpen(attachment, $event)"
							@retry="$emit('android-retry', { message, attachment })"
						/>
						<!-- #endif -->
					</view>
					<button
						v-if="hiddenSecondaryCount > 0"
						class="generated-image-more"
						type="button"
						:aria-label="`查看其余 ${hiddenSecondaryCount} 张图片`"
						@click.stop="openOverflow"
					>
						<text>+{{ hiddenSecondaryCount }}</text>
					</button>
				</view>
			</scroll-view>
		</view>
		<text
			v-if="presentation.progressLabel"
			class="generated-image-progress"
			role="status"
			aria-live="polite"
		>
			{{ presentation.progressLabel }}
		</text>
	</view>
</template>

<script>
	import { generatedImageIdentity } from '@/common/aichat/ai-conversation-image-viewer.js'
	// #ifdef APP-PLUS
	import UserAndroidChatImage from './user-android-chat-image.vue'
	// #endif

	const LAYOUT_CLASSES = Object.freeze({
		SINGLE: 'is-single',
		PAIR: 'is-pair',
		HERO_TWO: 'is-hero-two',
		HERO_THREE: 'is-hero-three',
		DUAL_WITH_RAIL: 'is-dual-with-rail'
	})

	export default {
		name: 'UserGeneratedImageGallery',
		components: {
			// #ifdef APP-PLUS
			UserAndroidChatImage
			// #endif
		},
		props: {
			message: { type: Object, required: true },
			presentation: { type: Object, required: true },
			aspectRatio: { type: Number, default: 1 },
			androidSources: { type: Object, default: () => ({}) }
		},
		emits: ['open', 'image-load', 'android-layout-change', 'android-retry'],
		computed: {
			hasPresentation() {
				return Boolean(this.presentation?.allItems?.length
					|| this.presentation?.progressLabel)
			},
			layoutClass() {
				return LAYOUT_CLASSES[this.presentation?.layout] || 'is-single'
			},
			stageItems() {
				if (['HERO_TWO', 'HERO_THREE'].includes(this.presentation?.layout)) {
					return [
						...(this.presentation?.primaryItems || []),
						...(this.presentation?.secondaryItems || [])
					]
				}
				return this.presentation?.primaryItems || []
			},
			railItems() {
				return this.presentation?.layout === 'DUAL_WITH_RAIL'
					? this.presentation?.visibleSecondaryItems || []
					: []
			},
			hiddenSecondaryCount() {
				return Number(this.presentation?.hiddenSecondaryCount) || 0
			},
			galleryStyle() {
				const aspect = Number(this.aspectRatio)
				const safeAspect = Number.isFinite(aspect) && aspect > 0 ? aspect : 1
				return {
					'--image-gallery-aspect': String(safeAspect),
					'--image-gallery-mosaic-aspect': String(safeAspect * 1.52)
				}
			}
		},
		methods: {
			imageIdentity(attachment) {
				return generatedImageIdentity(this.message, attachment)
			},
			imageAccessibleName(attachment) {
				const outputIndex = Number(attachment?.outputIndex)
				return Number.isSafeInteger(outputIndex)
					? `查看第 ${outputIndex + 1} 张生成图片`
					: '查看生成图片'
			},
			tileClasses(attachment) {
				return {
					'is-partial': String(attachment?.phase || '').toUpperCase() === 'PARTIAL',
					'is-exiting': attachment?.galleryExiting === true
				}
			},
			androidSource(attachment) {
				return this.androidSources[this.imageIdentity(attachment)] || {
					src: '',
					status: 'WAITING_REMOTE',
					diagnosticRunId: ''
				}
			},
			emitOpen(attachment, sourceState = null) {
				this.$emit('open', {
					message: this.message,
					attachment,
					identity: this.imageIdentity(attachment),
					sourceState
				})
			},
			openOverflow() {
				const hiddenAttachment = this.presentation?.secondaryItems?.[
					this.presentation?.visibleSecondaryItems?.length || 0
				]
				if (hiddenAttachment) this.emitOpen(hiddenAttachment)
			}
		}
	}
</script>

<style lang="scss" scoped>
	.generated-image-gallery { --gallery-gap: 8px; --gallery-thumb-size: clamp(56px, 12vw, 72px); width: min(100%, 720px); margin-top: 10px; }
	.generated-image-layout { width: 100%; }
	.generated-image-layout.has-secondary-rail { position: relative; padding-right: calc(var(--gallery-thumb-size) + var(--gallery-gap)); box-sizing: border-box; }
	.generated-image-stage { width: 100%; display: grid; gap: var(--gallery-gap); }
	.generated-image-stage.is-single { grid-template-columns: minmax(0, 1fr); }
	.generated-image-stage.is-pair,
	.generated-image-stage.is-dual-with-rail { grid-template-columns: repeat(2, minmax(0, 1fr)); }
	.generated-image-stage.is-hero-two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
	.generated-image-stage.is-hero-three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
	.generated-image-stage.is-hero-two .generated-image-tile:first-child,
	.generated-image-stage.is-hero-three .generated-image-tile:first-child { grid-column: 1 / -1; }
	.generated-image-tile { min-width: 0; aspect-ratio: var(--image-gallery-aspect, 1); position: relative; overflow: hidden; border: 1px solid rgba(104, 136, 121, .42); border-radius: 14px; background: #141816; box-shadow: 0 8px 24px rgba(0, 0, 0, .14); box-sizing: border-box; }
	.generated-image-action { width: 100%; height: 100%; min-height: 0; margin: 0; padding: 0; display: block; overflow: hidden; border: 0; border-radius: inherit; background: transparent; line-height: 1; }
	.generated-image-action::after { border: 0; }
	.generated-image-action:focus-visible { outline: 3px solid #75dfb7; outline-offset: -3px; }
	.generated-image-element { width: 100%; height: 100%; display: block; transition: filter 160ms ease, opacity 180ms ease, transform 160ms ease; }
	.generated-image-action:active .generated-image-element { transform: scale(.985); }
	.generated-image-tile.is-partial .generated-image-element { filter: blur(2px) saturate(.9); transform: scale(1.018); }
	.generated-image-tile.is-exiting { animation: generated-image-gallery-exit 180ms ease-in forwards; }
	.generated-image-layout.has-secondary-rail .generated-image-secondary { width: var(--gallery-thumb-size); position: absolute; top: 0; right: 0; bottom: 0; overflow: hidden; }
	.generated-image-secondary-track { min-height: 100%; display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 2px; box-sizing: border-box; }
	.generated-image-secondary .generated-image-tile,
	.generated-image-more { width: var(--gallery-thumb-size); height: var(--gallery-thumb-size); min-width: var(--gallery-thumb-size); min-height: var(--gallery-thumb-size); flex: 0 0 var(--gallery-thumb-size); aspect-ratio: 1; border-radius: 12px; }
	.generated-image-more { margin: 0; padding: 0; border: 1px solid rgba(117, 223, 183, .42); background: rgba(11, 18, 15, .82); color: #f3f7f5; font-size: 20px; font-weight: 760; line-height: var(--gallery-thumb-size); }
	.generated-image-more::after { border: 0; }
	.generated-image-more:focus-visible { outline: 2px solid #75dfb7; outline-offset: 2px; }
	.generated-image-progress { display: block; margin-top: 8px; color: #8fdcbe; font-size: 12px; font-variant-numeric: tabular-nums; }
	@keyframes generated-image-gallery-exit { to { opacity: 0; transform: scale(.975); } }

	@media screen and (max-width: 767px) {
		.generated-image-layout.has-secondary-rail { padding-right: 0; }
		.generated-image-layout.has-secondary-rail .generated-image-secondary { width: 100%; height: auto; position: static; margin-top: 8px; white-space: nowrap; }
		.generated-image-secondary-track { min-width: max-content; min-height: 0; flex-direction: row; justify-content: flex-start; }
	}

	@media screen and (min-width: 768px) {
		.generated-image-stage.is-hero-two { grid-template-columns: minmax(0, 1.18fr) minmax(0, .82fr); grid-template-rows: repeat(2, minmax(0, 1fr)); aspect-ratio: var(--image-gallery-mosaic-aspect); }
		.generated-image-stage.is-hero-three { grid-template-columns: minmax(0, 1.18fr) minmax(0, .82fr); grid-template-rows: repeat(3, minmax(0, 1fr)); aspect-ratio: var(--image-gallery-mosaic-aspect); }
		.generated-image-stage.is-hero-two .generated-image-tile,
		.generated-image-stage.is-hero-three .generated-image-tile { aspect-ratio: auto; }
		.generated-image-stage.is-hero-two .generated-image-tile:first-child,
		.generated-image-stage.is-hero-three .generated-image-tile:first-child { grid-column: 1; grid-row: 1 / -1; }
	}

	@media (prefers-reduced-motion: reduce) {
		.generated-image-element { transition: none; }
		.generated-image-tile.is-exiting { animation: none; }
	}
</style>
