<template>
	<view class="user-context-usage-sheet">
		<button
			ref="trigger"
			class="context-usage-trigger"
			type="button"
			aria-haspopup="dialog"
			:aria-expanded="String(opened)"
			aria-label="查看上下文使用情况"
			@click="open"
		>
			<user-thinking-orb
				v-if="compactionPresentation"
				:state="compactionPresentation.state"
				:size="20"
				:reduced="reduced"
				:aria-label="compactionPresentation.label"
			/>
			<text v-else>{{ roundedPercent }}%</text>
		</button>

		<view v-if="opened" class="context-usage-layer">
			<view class="context-usage-backdrop" aria-hidden="true" @click="close"></view>
			<view
				ref="panel"
				class="context-usage-sheet"
				role="dialog"
				aria-modal="true"
				aria-label="上下文使用情况"
				tabindex="-1"
				@keydown.esc.stop.prevent="close"
			>
				<view class="context-usage-heading">
					<view>
						<text class="context-usage-title">上下文使用情况</text>
						<text class="context-usage-summary">{{ exactPercent }}%</text>
					</view>
					<button class="context-usage-close" type="button" aria-label="关闭上下文详情" @click="close">
						<uni-icons type="closeempty" size="20" color="#dce5e0" aria-hidden="true" />
					</button>
				</view>
				<view class="context-usage-details">
					<view class="context-usage-row"><text>已使用</text><text>{{ usedTokens }}</text></view>
					<view class="context-usage-row"><text>总窗口</text><text>{{ totalTokens }}</text></view>
					<view class="context-usage-row"><text>占用</text><text>{{ exactPercent }}%</text></view>
					<view class="context-usage-row"><text>压缩状态</text><text>{{ compactionStatusLabel }}</text></view>
				</view>
			</view>
		</view>
	</view>
</template>

<script>
	import {
		formatAiConversationContextPercent,
		formatAiConversationContextTokens
	} from '@/common/aichat/ai-conversation-context-usage.js'
	import UserThinkingOrb from './user-thinking-orb.vue'

	export default {
		name: 'UserContextUsageSheet',
		components: { UserThinkingOrb },
		props: {
			usage: {
				type: Object,
				required: true
			},
			compactionPresentation: {
				type: Object,
				default: null
			},
			reduced: {
				type: Boolean,
				default: false
			}
		},
		data() {
			return { opened: false }
		},
		computed: {
			roundedPercent() {
				return Math.round(Math.max(0, Number(this.usage?.usagePercent || 0)))
			},
			exactPercent() {
				return formatAiConversationContextPercent(this.usage?.usagePercent)
			},
			usedTokens() {
				return formatAiConversationContextTokens(this.usage?.estimatedContextTokens)
			},
			totalTokens() {
				return formatAiConversationContextTokens(this.usage?.contextWindowTokens)
			},
			compactionStatusLabel() {
				return ({
					QUEUED: '等待压缩',
					RUNNING: '正在压缩',
					COMPLETED: '已完成',
					FAILED: '压缩失败'
				})[String(this.usage?.compactionStatus || '').toUpperCase()] || '正常'
			}
		},
		methods: {
			open() {
				this.opened = true
				this.$nextTick(() => {
					const panel = this.$refs.panel
					const element = panel?.$el || panel
					element?.focus?.()
				})
			},
			close() {
				if (!this.opened) return
				this.opened = false
				this.$nextTick(() => {
					const trigger = this.$refs.trigger
					trigger?.focus?.()
					trigger?.$el?.focus?.()
				})
			},
			closeIfOpen() {
				if (!this.opened) return false
				this.close()
				return true
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.user-context-usage-sheet { flex: 0 0 auto; }
	.context-usage-trigger { @include user-android-compact-control(36px, 34px, 10px); min-width: 44px; height: 44px; min-height: 44px; margin: 0; padding: 0 6px; color: #8fdcbe; font-size: 11px; font-weight: 760; font-variant-numeric: tabular-nums; }
	.context-usage-layer { position: fixed; inset: 0; z-index: 97; }
	.context-usage-backdrop { position: absolute; inset: 0; background: rgba(0, 0, 0, .6); }
	.context-usage-sheet { width: 100%; max-height: min(58dvh, 420px); padding-bottom: env(safe-area-inset-bottom); position: absolute; right: 0; bottom: 0; left: 0; overflow: hidden; border: 1px solid rgba(151, 177, 163, .24); border-right: 0; border-bottom: 0; border-left: 0; border-radius: 22px 22px 0 0; background: rgba(20, 25, 22, .98); box-shadow: 0 -14px 36px rgba(0, 0, 0, .34); box-sizing: border-box; }
	.context-usage-heading { min-height: 62px; padding: 10px 10px 10px 16px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid rgba(151, 177, 163, .15); }
	.context-usage-title { display: block; color: #f3f5f4; font-size: 16px; font-weight: 760; }
	.context-usage-summary { display: block; margin-top: 2px; color: #8fdcbe; font-size: 11px; }
	.context-usage-close { @include user-android-compact-control(34px, 34px, 11px); width: 44px; height: 44px; min-height: 44px; margin: 0; padding: 0; }
	.context-usage-close::after { border: 0; }
	.context-usage-details { padding: 8px 16px 16px; }
	.context-usage-row { min-height: 44px; display: flex; align-items: center; justify-content: space-between; gap: 16px; border-bottom: 1px solid rgba(151, 177, 163, .1); color: #98a49e; font-size: 13px; }
	.context-usage-row:last-child { border-bottom: 0; }
	.context-usage-row text:last-child { color: #e5ece8; font-variant-numeric: tabular-nums; text-align: right; }
	@media (prefers-reduced-transparency: reduce), (prefers-contrast: more) {
		.context-usage-sheet { background: #141916; }
	}
</style>
