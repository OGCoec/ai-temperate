<template>
	<view
		v-if="turns.length"
		class="conversation-turn-rail"
		role="navigation"
		aria-label="当前会话轮次"
		@mouseleave="schedulePreviewClose"
	>
		<button
			v-if="hasMoreBefore || hasHiddenBefore"
			class="turn-continuation is-before"
			type="button"
			:disabled="loadingBefore || (!hasMoreBefore && !hasHiddenBefore)"
			:aria-label="loadingBefore ? '正在加载更早消息' : loadError ? '重试加载更早消息' : hasHiddenBefore ? '显示更早轮次' : '加载更早消息'"
			:aria-busy="String(loadingBefore)"
			@click="requestOlder"
		>
			<view class="turn-continuation-dot" aria-hidden="true"></view>
			<view class="turn-continuation-dot" aria-hidden="true"></view>
			<view class="turn-continuation-dot" aria-hidden="true"></view>
		</button>

		<view class="turn-marker-list">
			<button
				v-for="(turn, index) in turns"
				:key="turn.key"
				class="turn-marker-button"
				:class="{
					'is-active': turn.key === activeTurnKey,
					'is-previewed': index === previewIndex
				}"
				type="button"
				:tabindex="markerTabIndex(turn, index)"
				:aria-current="turn.key === activeTurnKey ? 'true' : undefined"
				:aria-label="markerLabel(turn)"
				@mouseenter="openPreview(turn, index)"
				@focus="openPreview(turn, index, true)"
				@click="selectMarker(turn, index)"
				@keydown="handleMarkerKeydown($event, index)"
			>
				<view class="turn-marker-line" :style="markerLineStyle(index)" aria-hidden="true"></view>
			</button>
		</view>

		<view v-if="hasHiddenAfter" class="turn-continuation is-after" aria-hidden="true">
			<view class="turn-continuation-dot"></view>
			<view class="turn-continuation-dot"></view>
			<view class="turn-continuation-dot"></view>
		</view>

		<button
			v-if="previewTurn"
			class="turn-preview-card"
			type="button"
			:style="previewCardStyle"
			:aria-label="`${markerLabel(previewTurn)}，点击定位到该轮正文`"
			@mouseenter="cancelPreviewClose"
			@mouseleave="schedulePreviewClose"
			@click="selectPreviewTurn"
			@keydown.esc.stop.prevent="closePreviewAndRestoreMarker"
		>
			<view class="turn-preview-meta">
				<text>{{ turnPositionLabel(previewTurn) }}</text>
				<text v-if="formattedTime(previewTurn.createdAt)">{{ formattedTime(previewTurn.createdAt) }}</text>
				<text v-if="previewStatusLabel">{{ previewStatusLabel }}</text>
			</view>
			<text class="turn-preview-question">{{ previewTurn.question }}</text>
			<text class="turn-preview-answer">{{ previewTurn.answerSummary }}</text>
			<text v-if="previewTurn.attachmentSummary" class="turn-preview-attachments">
				{{ previewTurn.attachmentSummary }}
			</text>
		</button>
	</view>
</template>

<script>
	import { turnMarkerWidth } from '@/common/aichat/ai-conversation-turn-navigation.js'

	const STATUS_LABELS = Object.freeze({
		streaming: '生成中',
		saving: '保存中',
		stopped: '已停止',
		failed: '生成失败'
	})

	export default {
		props: {
			turns: {
				type: Array,
				default: () => []
			},
			activeTurnKey: {
				type: String,
				default: ''
			},
			hasHiddenBefore: {
				type: Boolean,
				default: false
			},
			hasHiddenAfter: {
				type: Boolean,
				default: false
			},
			hasMoreBefore: {
				type: Boolean,
				default: false
			},
			positionsKnown: {
				type: Boolean,
				default: true
			},
			loadingBefore: {
				type: Boolean,
				default: false
			},
			loadError: {
				type: String,
				default: ''
			}
		},
		data() {
			return {
				previewKey: '',
				previewIndex: -1,
				keyboardKey: '',
				previewCloseTimer: null
			}
		},
		computed: {
			previewTurn() {
				return this.turns.find(turn => turn.key === this.previewKey) || null
			},
			previewStatusLabel() {
				return STATUS_LABELS[this.previewTurn?.status] || ''
			},
			previewCardStyle() {
				const lastIndex = Math.max(1, this.turns.length - 1)
				const ratio = Math.max(0, this.previewIndex) / lastIndex
				const top = Math.round(15 + ratio * 70)
				return { top: `clamp(92px, ${top}%, calc(100% - 92px))` }
			}
		},
		watch: {
			turns() {
				if (!this.turns.some(turn => turn.key === this.previewKey)) this.closePreview()
				if (!this.turns.some(turn => turn.key === this.keyboardKey)) this.keyboardKey = ''
			}
		},
		beforeUnmount() {
			this.cancelPreviewClose()
		},
		methods: {
			markerLineStyle(index) {
				return { width: `${turnMarkerWidth(index, this.previewIndex)}px` }
			},
			turnPositionLabel(turn) {
				return this.positionsKnown
					? `第 ${turn.position} 轮`
					: `已加载轮次 ${turn.position}`
			},
			markerTabIndex(turn, index) {
				const focusKey = this.keyboardKey || this.activeTurnKey || this.turns[0]?.key
				return turn.key === focusKey || (!focusKey && index === 0) ? 0 : -1
			},
			markerLabel(turn) {
				const status = STATUS_LABELS[turn.status]
				return `${this.turnPositionLabel(turn)}：${turn.question}${status ? `，${status}` : ''}`
			},
			formattedTime(value) {
				if (!value) return ''
				const date = new Date(value)
				if (Number.isNaN(date.getTime())) return ''
				return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
			},
			openPreview(turn, index, fromKeyboard = false) {
				this.cancelPreviewClose()
				this.previewKey = turn.key
				this.previewIndex = index
				if (fromKeyboard) this.keyboardKey = turn.key
			},
			closePreview() {
				this.cancelPreviewClose()
				this.previewKey = ''
				this.previewIndex = -1
			},
			schedulePreviewClose() {
				this.cancelPreviewClose()
				this.previewCloseTimer = setTimeout(() => this.closePreview(), 100)
			},
			cancelPreviewClose() {
				if (this.previewCloseTimer != null) clearTimeout(this.previewCloseTimer)
				this.previewCloseTimer = null
			},
			requestOlder() {
				if (this.loadingBefore || (!this.hasMoreBefore && !this.hasHiddenBefore)) return
				this.$emit(this.loadError ? 'retry-older' : 'request-older')
			},
			selectMarker(turn, index) {
				this.openPreview(turn, index)
				this.$emit('select-turn', turn.key)
			},
			selectPreviewTurn() {
				if (!this.previewTurn) return
				this.$emit('select-turn', this.previewTurn.key)
			},
			closePreviewAndRestoreMarker() {
				const index = this.previewIndex
				this.closePreview()
				this.$nextTick(() => {
					// #ifdef H5
					this.$el?.querySelectorAll?.('.turn-marker-button')?.[index]?.focus?.()
					// #endif
				})
			},
			handleMarkerKeydown(event, index) {
				let targetIndex = index
				switch (event.key) {
					case 'ArrowUp':
						targetIndex = Math.max(0, index - 1)
						break
					case 'ArrowDown':
						targetIndex = Math.min(this.turns.length - 1, index + 1)
						break
					case 'Home':
						targetIndex = 0
						break
					case 'End':
						targetIndex = this.turns.length - 1
						break
					case 'Enter':
					case ' ':
						event.preventDefault()
						this.$emit('select-turn', this.turns[index].key)
						return
					case 'Escape':
						event.preventDefault()
						this.closePreview()
						return
					default:
						return
				}
				event.preventDefault()
				this.focusMarker(targetIndex)
			},
			focusMarker(index) {
				const turn = this.turns[index]
				if (!turn) return
				this.keyboardKey = turn.key
				this.openPreview(turn, index, true)
				this.$nextTick(() => {
					// 当前功能只面向 H5；原生按钮维持一个 roving tabindex 停靠点。
					// #ifdef H5
					this.$el?.querySelectorAll?.('.turn-marker-button')?.[index]?.focus?.()
					// #endif
				})
			}
		}
	}
</script>

<style lang="scss" scoped>
	.conversation-turn-rail {
		display: none;
		position: absolute;
		z-index: 12;
		top: 50%;
		left: 12px;
		width: 28px;
		max-height: min(calc(100% - 32px), 460px);
		align-items: center;
		flex-direction: column;
		transform: translateY(-50%);
	}

	.turn-marker-list {
		min-height: 0;
		width: 100%;
		display: flex;
		align-items: center;
		flex: 1;
		flex-direction: column;
		justify-content: center;
	}

	.turn-marker-button,
	.turn-continuation,
	.turn-preview-card {
		margin: 0;
		border: 0;
		background: transparent;
		box-sizing: border-box;
	}

	.turn-marker-button::after,
	.turn-continuation::after,
	.turn-preview-card::after {
		border: 0;
	}

	.turn-marker-button {
		width: 28px;
		min-height: 8px;
		padding: 2px 4px;
		display: flex;
		align-items: center;
		justify-content: flex-start;
		border-radius: 4px;
	}

	.turn-marker-line {
		width: 8px;
		height: 2px;
		border-radius: 999px;
		background: rgba(183, 194, 188, .34);
		transition: width 150ms ease-out, background-color 150ms ease-out, box-shadow 150ms ease-out;
	}

	.turn-marker-button.is-active .turn-marker-line {
		background: #8fdcbe;
	}

	.turn-marker-button.is-previewed:not(.is-active) .turn-marker-line {
		background: rgba(232, 238, 235, .84);
	}

	.turn-marker-button:focus-visible,
	.turn-continuation:focus-visible,
	.turn-preview-card:focus-visible {
		outline: 2px solid rgba(55, 211, 154, .72);
		outline-offset: 1px;
	}

	.turn-continuation {
		width: 28px;
		min-height: 22px;
		padding: 3px 0;
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 2px;
		border-radius: 7px;
	}

	.turn-continuation[disabled] {
		opacity: .48;
	}

	.turn-continuation-dot {
		width: 2px;
		height: 2px;
		border-radius: 50%;
		background: #718078;
	}

	.turn-preview-card {
		position: absolute;
		left: 38px;
		width: min(288px, calc(100vw - 360px));
		max-height: 176px;
		padding: 10px 12px;
		overflow: hidden;
		border: 1px solid rgba(94, 111, 101, .58);
		border-radius: 14px;
		background: rgba(31, 36, 33, .96);
		box-shadow: 0 18px 48px rgba(0, 0, 0, .36);
		backdrop-filter: blur(20px) saturate(115%);
		color: #e8eeeb;
		cursor: pointer;
		line-height: normal;
		text-align: left;
		transform: translateY(-50%);
		animation: turn-preview-enter 140ms cubic-bezier(.23, 1, .32, 1);
		box-sizing: border-box;
	}

	.turn-preview-meta {
		display: flex;
		align-items: center;
		gap: 8px;
		color: #86938c;
		font-size: 11px;
	}

	.turn-preview-question {
		margin-top: 8px;
		display: -webkit-box;
		overflow: hidden;
		color: #f3f5f4;
		font-size: 13px;
		font-weight: 700;
		line-height: 1.55;
		-webkit-box-orient: vertical;
		-webkit-line-clamp: 2;
		word-break: break-word;
	}

	.turn-preview-answer {
		margin-top: 7px;
		display: -webkit-box;
		overflow: hidden;
		color: #aeb8b2;
		font-size: 12px;
		line-height: 1.55;
		-webkit-box-orient: vertical;
		-webkit-line-clamp: 3;
		word-break: break-word;
	}

	.turn-preview-attachments {
		margin-top: 7px;
		display: block;
		color: #8fdcbe;
		font-size: 11px;
	}

	@keyframes turn-preview-enter {
		from { opacity: 0; transform: translate(-6px, -50%); }
		to { opacity: 1; transform: translate(0, -50%); }
	}

	@media screen and (min-width: 768px) {
		.conversation-turn-rail { display: flex; }
	}

	@media screen and (min-width: 1200px) {
		.conversation-turn-rail { left: calc(50% - 438px); }
	}

	@media screen and (max-width: 767px) {
		.conversation-turn-rail { display: none !important; }
	}

	@media (prefers-reduced-motion: reduce) {
		.turn-marker-line { transition: none; }
		.turn-preview-card { animation: none; }
	}
</style>
