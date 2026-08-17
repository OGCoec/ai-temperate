<template>
	<view v-if="open" class="api-key-secret-layer" role="presentation" tabindex="-1" @click.self.stop @keydown.esc.stop.prevent="remind" @keydown.tab="trapFocus">
		<view ref="dialog" class="api-key-secret-dialog" role="dialog" aria-modal="true" aria-labelledby="api-key-secret-title" tabindex="-1">
			<view class="api-key-secret-icon" aria-hidden="true">✓</view>
			<text id="api-key-secret-title" class="api-key-secret-title">API Key 创建成功</text>
			<text class="api-key-secret-warning">完整 API Key 只显示这一次。关闭后无法再次查看或找回。</text>

			<view class="api-key-secret-field">
				<text class="api-key-secret-label">API Key</text>
				<text class="api-key-secret-value" selectable>{{ secret }}</text>
				<button type="button" @click="copySecret">复制 API Key</button>
			</view>
			<view class="api-key-secret-field">
				<text class="api-key-secret-label">Base URL</text>
				<text class="api-key-secret-value" selectable>{{ baseUrl }}</text>
				<button type="button" @click="copyBaseUrl">复制 Base URL</button>
			</view>

			<text class="api-key-secret-note">不要把 API Key 写入浏览器页面、公开仓库、日志或聊天记录。</text>
			<button ref="acknowledge" class="api-key-secret-acknowledge" type="button" @click="$emit('acknowledge')">我已保存，关闭</button>
		</view>
	</view>
</template>

<script>
	import { API_KEY_COMPATIBLE_BASE_URL } from '@/common/user/api-key-config.js'

	export default {
		props: {
			open: { type: Boolean, default: false },
			secret: { type: String, default: '' }
		},
		computed: {
			baseUrl() { return API_KEY_COMPATIBLE_BASE_URL }
		},
		watch: {
			open(value) {
				if (!value) return
				// #ifdef H5
				if (typeof document === 'undefined') return
				this.$nextTick(() => {
					const acknowledge = this.$refs.acknowledge?.$el || this.$refs.acknowledge
					acknowledge?.focus?.({ preventScroll: true })
				})
				// #endif
			}
		},
		methods: {
			remind() {
				uni.showToast({ title: '请先确认已经保存 API Key', icon: 'none' })
			},
			copySecret() {
				this.copy(this.secret, 'API Key 已复制')
			},
			copyBaseUrl() {
				this.copy(this.baseUrl, 'Base URL 已复制')
			},
			copy(value, successTitle) {
				uni.setClipboardData({
					data: value,
					success: () => uni.showToast({ title: successTitle, icon: 'none' }),
					fail: () => uni.showToast({ title: '复制失败，请重试', icon: 'none' })
				})
			},
			trapFocus(event) {
				// #ifdef H5
				if (typeof document === 'undefined') return
				const dialog = this.$refs.dialog?.$el || this.$refs.dialog
				const focusable = Array.from(dialog?.querySelectorAll?.('button:not([disabled]), [tabindex]:not([tabindex="-1"])') || [])
				if (!focusable.length) return
				const first = focusable[0]
				const last = focusable[focusable.length - 1]
				if (event.shiftKey && document.activeElement === first) {
					event.preventDefault(); last.focus()
				} else if (!event.shiftKey && document.activeElement === last) {
					event.preventDefault(); first.focus()
				}
				// #endif
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';
	.api-key-secret-layer { position: fixed; inset: 0; z-index: 60; display: flex; align-items: center; justify-content: center; padding: 22px; box-sizing: border-box; background: rgba(0, 0, 0, .82); }
	.api-key-secret-dialog { @include user-frosted-surface; width: min(620px, 100%); max-height: calc(100dvh - 44px); overflow-y: auto; padding: 28px; box-sizing: border-box; border-color: rgba(55, 211, 154, .34); border-radius: 22px; color: #f3f5f4; }
	.api-key-secret-icon { width: 46px; height: 46px; display: flex; align-items: center; justify-content: center; border-radius: 15px; background: rgba(55, 211, 154, .13); color: #75dfb7; font-size: 22px; font-weight: 800; }
	.api-key-secret-title { display: block; margin-top: 18px; font-size: 25px; font-weight: 780; }
	.api-key-secret-warning { display: block; margin-top: 8px; color: #efc18a; font-size: 14px; line-height: 1.6; }
	.api-key-secret-field { margin-top: 20px; padding: 16px; border: 1px solid rgba(151, 170, 160, .2); border-radius: 15px; background: #101310; }
	.api-key-secret-label { display: block; color: #8f9b95; font-size: 12px; }
	.api-key-secret-value { display: block; margin-top: 8px; overflow-wrap: anywhere; color: #e8efeb; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 13px; line-height: 1.65; }
	.api-key-secret-field button, .api-key-secret-acknowledge { @include user-frosted-control; width: 100%; margin: 14px 0 0; border-radius: 12px; color: #dce5e0; }
	.api-key-secret-note { display: block; margin-top: 18px; color: #8f9b95; font-size: 12px; line-height: 1.6; }
	.api-key-secret-acknowledge { border-color: rgba(55, 211, 154, .48); background: rgba(55, 211, 154, .13); color: #75dfb7; font-weight: 720; }
	@media screen and (max-width: 640px) { .api-key-secret-layer { padding: 0; align-items: stretch; } .api-key-secret-dialog { width: 100%; max-height: 100dvh; min-height: 100dvh; padding: calc(24px + env(safe-area-inset-top)) 16px calc(24px + env(safe-area-inset-bottom)); border-radius: 0; } }
</style>
