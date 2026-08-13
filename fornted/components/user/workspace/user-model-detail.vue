<template>
	<view class="model-detail-page" :class="{ 'is-android-client': androidClient }">
		<scroll-view class="model-detail-scroll" scroll-y>
			<view class="model-detail-shell" :aria-busy="loading">
				<view class="model-detail-topbar">
					<button
						v-if="androidClient"
						class="workspace-panel-menu"
						type="button"
						aria-label="打开导航"
						@click="$emit('open-conversation-drawer')"
					>
						<uni-icons type="bars" size="18" color="#dce5e0" aria-hidden="true" />
					</button>
					<button class="model-detail-back" type="button" aria-label="返回模型目录" @click="goBack">
						<uni-icons type="left" size="22" color="#dce5e0" aria-hidden="true" />
						<text>模型</text>
					</button>
				</view>

				<view v-if="!authenticated" class="model-detail-state" role="status">
					<view class="model-detail-skeleton-icon"></view>
					<view class="model-detail-skeleton-line title"></view>
					<view class="model-detail-skeleton-line"></view>
					<text>正在确认当前会话…</text>
				</view>

				<view v-else-if="loading" class="model-detail-state" role="status">
					<view class="model-detail-skeleton-icon"></view>
					<view class="model-detail-skeleton-line title"></view>
					<view class="model-detail-skeleton-line"></view>
					<view class="model-detail-skeleton-line short"></view>
					<text>正在读取模型详情…</text>
				</view>

				<view v-else-if="errorCode" class="model-detail-state model-detail-state-error" role="alert">
					<uni-icons type="info" size="26" color="#65c7c2" aria-hidden="true" />
					<text class="model-detail-state-title">{{ errorCode === 'AI_MODEL_NOT_FOUND' ? '该模型已不可用' : '模型详情暂时无法加载' }}</text>
					<text class="model-detail-state-copy">{{ errorMessage }}</text>
					<button class="model-detail-retry" type="button" @click="errorCode === 'AI_MODEL_NOT_FOUND' ? goBack() : loadModel()">
						{{ errorCode === 'AI_MODEL_NOT_FOUND' ? '返回模型目录' : '重新加载' }}
					</button>
				</view>

				<template v-else-if="model">
					<view class="model-detail-hero">
						<image
							v-if="hasModelIcon"
							class="model-detail-icon model-detail-icon-image"
							:src="model.icon"
							mode="aspectFit"
							@error="iconFailed = true"
						/>
						<view v-else class="model-detail-icon" aria-hidden="true">
							<uni-icons type="star" size="24" color="#37d39a" />
						</view>
						<view class="model-detail-hero-copy">
							<text class="model-detail-kicker">{{ model.vendor }}</text>
							<text class="model-detail-title">{{ model.modelName }}</text>
						</view>
					</view>

					<view class="model-detail-section">
						<text class="model-detail-section-title">模型说明</text>
						<view class="model-detail-panel">
							<text class="model-detail-description">{{ model.description || '暂无模型说明。' }}</text>
						</view>
					</view>

					<view v-if="model.tags.length" class="model-detail-section">
						<text class="model-detail-section-title">标签</text>
						<view class="model-detail-tags">
							<text v-for="tag in model.tags" :key="tag" class="model-detail-tag">{{ tag }}</text>
						</view>
					</view>

					<view class="model-detail-section">
						<text class="model-detail-section-title">计费倍率</text>
						<view class="model-detail-panel model-detail-ratios">
							<view class="model-detail-ratio-row">
								<text>输入</text>
								<text>{{ formatRatio(model.inputRatio) }}</text>
							</view>
							<view class="model-detail-ratio-row">
								<view class="model-detail-ratio-label">
									<text>缓存输入</text>
									<text class="model-detail-ratio-hint">对应厂商 usage 中的 cached_tokens</text>
								</view>
								<text>{{ formatRatio(model.cachedInputRatio) }}</text>
							</view>
							<view class="model-detail-ratio-row">
								<text>输出</text>
								<text>{{ formatRatio(model.outputRatio) }}</text>
							</view>
						</view>
						<text class="model-detail-rate-note">以上为本项目计费倍率，不是模型厂商美元定价；缓存输入与项目 Redis 缓存无关。</text>
					</view>

					<view v-if="model.capabilities.length" class="model-detail-section">
						<text class="model-detail-section-title">能力</text>
						<view class="model-detail-capabilities">
							<text v-for="capability in model.capabilities" :key="capability" class="model-detail-capability">{{ capability }}</text>
						</view>
					</view>
				</template>
			</view>
		</scroll-view>
	</view>
</template>

<script>
	import { aiModelApi } from '@/common/aimodel/ai-model-api.js'
	import { clientPlatform } from '@/common/auth/config.js'

	export default {
		props: {
			authenticated: {
				type: Boolean,
				default: false
			},
			modelPublicId: {
				type: String,
				required: true
			}
		},
		data() {
			return {
				model: null,
				loading: false,
				errorCode: '',
				errorMessage: '',
				iconFailed: false
			}
		},
		watch: {
			authenticated(value) {
				if (value) this.onAuthenticatedPageReady()
			},
			modelPublicId() {
				this.model = null
				this.errorCode = ''
				this.errorMessage = ''
				if (this.authenticated) this.onAuthenticatedPageReady()
			}
		},
		computed: {
			androidClient() {
				return clientPlatform() === 'ANDROID'
			},
			hasModelIcon() {
				return Boolean(this.model?.icon) && !this.iconFailed
			}
		},
		mounted() {
			if (this.authenticated) this.onAuthenticatedPageReady()
		},
		methods: {
			onAuthenticatedPageReady() {
				if (this.modelPublicId && !this.model && !this.loading) {
					this.loadModel()
				}
			},
			formatRatio(value) {
				return value ? `×${value}` : '未配置'
			},
			async loadModel() {
				if (this.loading) return
				this.loading = true
				this.errorCode = ''
				this.errorMessage = ''
				this.iconFailed = false
				try {
					this.model = await aiModelApi.detail(this.modelPublicId)
				} catch (error) {
					this.model = null
					this.errorCode = error?.code || 'AI_MODEL_REQUEST_FAILED'
					this.errorMessage = this.errorCode === 'AI_MODEL_NOT_FOUND'
						? '该模型可能已被禁用或删除。'
						: error?.message || '请检查网络后重试。'
				} finally {
					this.loading = false
				}
			},
			goBack() {
				this.$emit('back')
			}
		}
	}
</script>

<style lang="scss">
	@import '@/common/ui/user-material.scss';

	.model-detail-page { min-width: 0; min-height: 0; height: 100%; background: #0b0d0c; color: #f3f5f4; }
	.model-detail-scroll { height: 100%; min-height: 0; }
	.model-detail-shell { max-width: 800px; min-height: 100%; margin: 0 auto; padding: 20px 16px calc(40px + env(safe-area-inset-bottom)); box-sizing: border-box; }
	.model-detail-page.is-android-client .model-detail-shell { padding: max(12px, env(safe-area-inset-top)) 12px calc(20px + env(safe-area-inset-bottom)); }
	.model-detail-topbar { min-height: 48px; display: flex; align-items: center; gap: 8px; margin-bottom: 20px; }
	.workspace-panel-menu { width: 44px; height: 44px; min-height: 44px; margin: 0; padding: 0; flex: 0 0 44px; border: 0; border-radius: 13px; background: rgba(243, 245, 244, .055); }
	.workspace-panel-menu::after { border: 0; }
	.model-detail-back { @include user-frosted-control; min-width: 84px; min-height: 48px; margin: 0; padding: 0 10px; justify-content: flex-start; gap: 6px; border-radius: 12px; color: #dce5e0; font-size: 14px; font-weight: 650; line-height: 1.2; }
	.model-detail-back::after, .model-detail-retry::after { border: 0; }
	.model-detail-back:active, .model-detail-retry:active { transform: scale(.985); }
	.model-detail-hero { display: flex; align-items: center; gap: 14px; margin-bottom: 28px; }
	.model-detail-icon { width: 58px; height: 58px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; overflow: hidden; border-radius: 16px; background: #1b211d; }
	.model-detail-icon-image { display: block; }
	.model-detail-hero-copy { min-width: 0; display: flex; flex: 1; flex-direction: column; }
	.model-detail-kicker { color: #65c7c2; font-size: 13px; font-weight: 650; line-height: 1.4; }
	.model-detail-title { margin-top: 4px; overflow-wrap: anywhere; color: #f3f5f4; font-size: 28px; font-weight: 760; line-height: 1.18; letter-spacing: -.35px; }
	.model-detail-section { margin-top: 24px; }
	.model-detail-section-title { display: block; margin: 0 0 10px 4px; color: #8b9690; font-size: 13px; font-weight: 650; }
	.model-detail-panel { @include user-frosted-surface; padding: 18px; border-radius: 16px; }
	.model-detail-description { color: #dce5e0; font-size: 15px; line-height: 1.65; white-space: pre-wrap; }
	.model-detail-tags, .model-detail-capabilities { display: flex; flex-wrap: wrap; gap: 8px; }
	.model-detail-tag, .model-detail-capability { padding: 7px 10px; border-radius: 10px; background: #202520; color: #c8d2cd; font-size: 13px; line-height: 1; }
	.model-detail-capability { background: rgba(101, 199, 194, .12); color: #a8dfdc; font-variant-numeric: tabular-nums; }
	.model-detail-ratio-row { min-height: 48px; display: flex; align-items: center; justify-content: space-between; gap: 16px; color: #dce5e0; font-size: 15px; font-variant-numeric: tabular-nums; }
	.model-detail-ratio-row + .model-detail-ratio-row { border-top: 1px solid #303733; }
	.model-detail-ratio-row > text:last-child { color: #a8e7ca; font-weight: 700; white-space: nowrap; }
	.model-detail-ratio-label { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
	.model-detail-ratio-hint { color: #8b9690; font-size: 12px; line-height: 1.35; }
	.model-detail-rate-note { display: block; margin: 10px 4px 0; color: #8b9690; font-size: 12px; line-height: 1.55; }
	.model-detail-state { @include user-frosted-surface; min-height: 300px; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 28px; border-radius: 16px; color: #8b9690; text-align: center; box-sizing: border-box; }
	.model-detail-state-error { color: #8b9690; }
	.model-detail-state-title { margin-top: 14px; color: #f3f5f4; font-size: 18px; font-weight: 700; }
	.model-detail-state-copy { margin-top: 8px; font-size: 14px; line-height: 1.6; }
	.model-detail-retry { @include user-frosted-control; min-width: 132px; min-height: 48px; margin-top: 18px; padding: 0 18px; border-color: #37d39a; border-radius: 12px; background: rgba(55, 211, 154, .14); color: #a8e7ca; font-size: 14px; font-weight: 650; line-height: 1.2; }
	.model-detail-skeleton-icon, .model-detail-skeleton-line { background: #202520; animation: model-detail-pulse 1.2s ease-in-out infinite alternate; }
	.model-detail-skeleton-icon { width: 58px; height: 58px; border-radius: 16px; }
	.model-detail-skeleton-line { width: min(100%, 430px); height: 12px; margin-top: 16px; border-radius: 6px; }
	.model-detail-skeleton-line.title { width: min(70%, 260px); height: 20px; }
	.model-detail-skeleton-line.short { width: min(58%, 220px); }
	.model-detail-back:focus-visible, .model-detail-retry:focus-visible { outline: 3px solid rgba(55, 211, 154, .28); outline-offset: 3px; }
	@keyframes model-detail-pulse { from { opacity: .45; } to { opacity: .95; } }
	@media (hover: hover) and (pointer: fine) {
		.model-detail-back:hover { background: rgba(243, 245, 244, .06); }
		.model-detail-retry:hover { background: rgba(55, 211, 154, .2); }
	}
	@media screen and (min-width: 768px) { .model-detail-shell { padding-top: 32px; } }
	@media screen and (min-width: 1024px) { .model-detail-shell { max-width: 800px; padding-right: 24px; padding-left: 24px; } }
	@media (prefers-reduced-motion: reduce) {
		.model-detail-back, .model-detail-retry { transition: none; }
		.model-detail-skeleton-icon, .model-detail-skeleton-line { animation: none; }
	}
</style>
