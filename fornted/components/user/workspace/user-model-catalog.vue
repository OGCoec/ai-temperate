<template>
	<view class="catalog-page" :class="{ 'is-android-client': androidClient }">
		<scroll-view class="catalog-scroll" scroll-y>
			<view class="catalog-shell" :aria-busy="!authenticated || initialLoading || refreshing || appending">
				<view class="catalog-heading-row">
					<button
						v-if="androidClient"
						class="workspace-panel-menu"
						type="button"
						aria-label="打开导航"
						@click="$emit('open-conversation-drawer')"
					>
						<uni-icons type="bars" size="18" color="#dce5e0" aria-hidden="true" />
					</button>
					<view class="catalog-heading">
						<text class="catalog-kicker">MODEL LIBRARY</text>
						<text class="catalog-title">模型</text>
						<text class="catalog-subtitle">查看当前已启用模型及项目内的计费倍率。</text>
					</view>
					<button
						class="catalog-refresh"
						type="button"
						:disabled="!authenticated || initialLoading || refreshing || appending"
						aria-label="刷新模型目录"
						@click="refreshCatalog"
					>
						<uni-icons type="refreshempty" size="19" color="#dce5e0" aria-hidden="true" />
						<text>{{ refreshing ? '刷新中' : '刷新' }}</text>
					</button>
				</view>

				<view class="catalog-search" role="search" aria-label="搜索已启用模型">
					<input
						v-model="keywordDraft"
						class="catalog-search-input"
						type="text"
						maxlength="128"
						aria-label="按模型名称词元、描述词元或完整厂商名搜索"
						placeholder="例如 mini、5.4 或 openai"
						:disabled="!authenticated || initialLoading || refreshing || appending"
						@confirm="submitSearch"
					/>
					<button
						class="catalog-search-submit"
						type="button"
						:disabled="!authenticated || initialLoading || refreshing || appending"
						@click="submitSearch"
					>搜索</button>
					<button
						v-if="activeKeyword || keywordDraft"
						class="catalog-search-clear"
						type="button"
						:disabled="initialLoading || refreshing || appending"
						@click="clearSearch"
					>清空</button>
				</view>

				<view v-if="!authenticated" class="catalog-skeleton catalog-session-pending" role="status">
					<view v-for="index in 4" :key="index" class="catalog-skeleton-row">
						<view class="catalog-skeleton-icon"></view>
						<view class="catalog-skeleton-copy">
							<view class="catalog-skeleton-line catalog-skeleton-line-title"></view>
							<view class="catalog-skeleton-line catalog-skeleton-line-copy"></view>
							<view class="catalog-skeleton-line catalog-skeleton-line-copy short"></view>
						</view>
					</view>
					<text class="catalog-state-copy">正在确认当前会话…</text>
				</view>

				<view v-else-if="initialLoading && !models.length" class="catalog-skeleton" role="status">
					<view v-for="index in 4" :key="index" class="catalog-skeleton-row">
						<view class="catalog-skeleton-icon"></view>
						<view class="catalog-skeleton-copy">
							<view class="catalog-skeleton-line catalog-skeleton-line-title"></view>
							<view class="catalog-skeleton-line catalog-skeleton-line-copy"></view>
							<view class="catalog-skeleton-line catalog-skeleton-line-copy short"></view>
						</view>
					</view>
					<text class="catalog-state-copy">正在读取可用模型…</text>
				</view>

				<view v-else-if="initialError && !models.length" class="catalog-error" role="alert">
					<uni-icons type="info" size="24" color="#65c7c2" aria-hidden="true" />
					<text class="catalog-state-title">模型目录暂时无法加载</text>
					<text class="catalog-state-copy">{{ initialError }}</text>
					<button class="catalog-retry" type="button" @click="refreshCatalog">重新加载</button>
				</view>

				<view v-else-if="hasLoaded && !models.length" class="catalog-empty" role="status">
					<uni-icons type="list" size="28" color="#65c7c2" aria-hidden="true" />
					<text class="catalog-state-title">{{ activeKeyword ? '没有匹配的模型' : '当前没有已启用模型' }}</text>
					<text class="catalog-state-copy">{{ activeKeyword ? '请调整关键词或清空搜索。' : '模型启用后会显示在这里。' }}</text>
					<button class="catalog-retry" type="button" @click="refreshCatalog">刷新目录</button>
				</view>

				<template v-else-if="models.length">
					<view class="catalog-summary" aria-live="polite">
						<text>{{ activeKeyword ? `匹配 ${total} 个已启用模型` : `已启用 ${total} 个模型` }}</text>
						<text v-if="refreshError" class="catalog-summary-warning">{{ refreshError }}</text>
					</view>

					<view class="catalog-list">
						<button
							v-for="model in models"
							:key="model.publicId"
							class="catalog-model-row"
							type="button"
							@click="openModel(model.publicId)"
						>
							<image
								v-if="hasModelIcon(model)"
								class="catalog-model-icon catalog-model-icon-image"
								:src="model.icon"
								mode="aspectFit"
								@error="markIconFailed(model.publicId)"
							/>
							<view v-else class="catalog-model-icon" aria-hidden="true">
								<uni-icons type="star" size="20" color="#37d39a" />
							</view>
							<view class="catalog-model-copy">
								<view class="catalog-model-title-row">
									<text class="catalog-model-name">
										<text
											v-for="(segment, segmentIndex) in model.modelNameSegments"
											:key="`${model.publicId}-name-${segmentIndex}`"
											:class="{ 'catalog-text-match': segment.matched }"
										>{{ segment.text }}</text>
									</text>
									<text class="catalog-model-vendor">{{ model.vendor }}</text>
								</view>
								<text class="catalog-model-description">
									<text
									v-for="(segment, segmentIndex) in model.descriptionSegments"
									:key="`${model.publicId}-description-${segmentIndex}`"
									:class="{ 'catalog-text-match': segment.matched }"
									>{{ segment.text }}</text>
								</text>
								<view v-if="model.tags.length" class="catalog-model-tags" aria-label="模型标签">
									<text v-for="tag in model.tags.slice(0, 2)" :key="tag" class="catalog-model-tag">{{ tag }}</text>
								</view>
								<view class="catalog-ratio-grid" aria-label="计费倍率">
									<view class="catalog-ratio-cell">
										<text>输入</text>
										<text>{{ formatRatio(model.inputRatio) }}</text>
									</view>
									<view class="catalog-ratio-cell">
										<text>缓存输入</text>
										<text>{{ formatRatio(model.cachedInputRatio) }}</text>
									</view>
									<view class="catalog-ratio-cell">
										<text>输出</text>
										<text>{{ formatRatio(model.outputRatio) }}</text>
									</view>
								</view>
							</view>
							<uni-icons class="catalog-model-arrow" type="right" size="18" color="#8b9690" aria-hidden="true" />
						</button>
					</view>

					<view class="catalog-footer">
						<text v-if="appendError" class="catalog-footer-error" role="alert">{{ appendError }}</text>
						<button
							v-if="hasNext"
							class="catalog-load-more"
							type="button"
							:disabled="appending || refreshing"
							@click="loadNextPage"
						>
							{{ appending ? '正在加载…' : appendError ? '重试加载更多' : '加载更多' }}
						</button>
						<text v-else class="catalog-finished">已经到底了</text>
					</view>
				</template>
			</view>
		</scroll-view>
	</view>
</template>

<script>
	import { aiModelApi } from '@/common/aimodel/ai-model-api.js'
	import { clientPlatform } from '@/common/auth/config.js'
	import { buildTextHighlightSegments } from '@/common/aimodel/description-highlight.js'
	import {
		loadNextAiModelCatalog,
		markAiModelIconFailed,
		readAiModelCatalog,
		refreshAiModelCatalog,
		setAiModelCatalogKeyword
	} from '@/common/aimodel/ai-model-catalog-store.js'

	const PAGE_SIZE = 20

	export default {
		props: {
			authenticated: {
				type: Boolean,
				default: false
			}
		},
		data() {
			const catalog = readAiModelCatalog()
			return { ...catalog, keywordDraft: catalog.activeKeyword }
		},
		watch: {
			authenticated(value) {
				if (value) this.onAuthenticatedPageReady()
			}
		},
		mounted() {
			if (this.authenticated) this.onAuthenticatedPageReady()
		},
		computed: {
			androidClient() {
				return clientPlatform() === 'ANDROID'
			}
		},
		methods: {
			onAuthenticatedPageReady() {
				const snapshot = readAiModelCatalog()
				this.keywordDraft = snapshot.activeKeyword
				this.applyCatalogSnapshot(snapshot)
				if (!this.hasLoaded && !this.initialLoading && !this.initialError) {
					this.refreshCatalog()
				}
			},
			applyCatalogSnapshot(snapshot) {
				Object.assign(this, {
				...snapshot,
				models: snapshot.models.map(model => ({
					...model,
					modelNameSegments: buildTextHighlightSegments(
						model.modelName,
						model.modelNameMatchedTokens
					),
					descriptionSegments: buildTextHighlightSegments(
						model.description,
						model.descriptionMatchedTokens,
						'暂无模型说明。'
					)
					}))
				})
			},
			submitSearch() {
				if (this.initialLoading || this.refreshing || this.appending) return
				this.keywordDraft = this.keywordDraft.trim()
				this.applyCatalogSnapshot(setAiModelCatalogKeyword(this.keywordDraft))
				this.refreshCatalog()
			},
			clearSearch() {
				if (this.initialLoading || this.refreshing || this.appending) return
				this.keywordDraft = ''
				this.applyCatalogSnapshot(setAiModelCatalogKeyword(''))
				this.refreshCatalog()
			},
			formatRatio(value) {
				return value ? `×${value}` : '未配置'
			},
			hasModelIcon(model) {
				return Boolean(model?.icon) && this.failedIconIds[model.publicId] !== true
			},
			markIconFailed(publicId) {
				this.applyCatalogSnapshot(markAiModelIconFailed(publicId))
			},
			async refreshCatalog() {
				this.failedIconIds = {}
				const keyword = this.activeKeyword
				const request = refreshAiModelCatalog(
					() => aiModelApi.list({ pageNum: 1, pageSize: PAGE_SIZE, keyword })
				)
				this.applyCatalogSnapshot(readAiModelCatalog())
				this.applyCatalogSnapshot(await request)
			},
			async loadNextPage() {
				const keyword = this.activeKeyword
				const request = loadNextAiModelCatalog(
					(pageNum) => aiModelApi.list({ pageNum, pageSize: PAGE_SIZE, keyword })
				)
				this.applyCatalogSnapshot(readAiModelCatalog())
				this.applyCatalogSnapshot(await request)
			},
			openModel(modelPublicId) {
				this.$emit('open-model', modelPublicId)
			}
		}
	}
</script>

<style lang="scss">
	@import '@/common/ui/user-material.scss';

	.catalog-page { min-width: 0; min-height: 0; height: 100%; display: flex; background: #0b0d0c; color: #f3f5f4; }
	.catalog-scroll { height: 100%; min-height: 0; min-width: 0; flex: 1; }
	.catalog-shell { max-width: 800px; min-height: 100%; margin: 0 auto; padding: 32px 16px calc(108px + env(safe-area-inset-bottom)); box-sizing: border-box; }
	.catalog-page.is-android-client .catalog-shell { padding: max(14px, env(safe-area-inset-top)) 12px calc(20px + env(safe-area-inset-bottom)); }
	.catalog-page.is-android-client .catalog-heading-row { align-items: center; gap: 8px; margin-bottom: 18px; }
	.catalog-page.is-android-client .catalog-kicker { font-size: 11px; letter-spacing: 1.5px; }
	.catalog-page.is-android-client .catalog-title { margin-top: 5px; font-size: 26px; }
	.catalog-page.is-android-client .catalog-subtitle { margin-top: 5px; font-size: 14px; line-height: 1.5; }
	.catalog-page.is-android-client .catalog-refresh { min-width: 64px; min-height: 44px; }
	.catalog-heading-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; }
	.workspace-panel-menu { @include user-android-compact-control(32px, 32px, 10px); width: 44px; height: 44px; min-height: 44px; margin: 0; padding: 0; flex: 0 0 44px; }
	.workspace-panel-menu::after { border: 0; }
	.catalog-heading { min-width: 0; display: flex; flex-direction: column; }
	.catalog-kicker { color: #37d39a; font-size: 13px; font-weight: 700; letter-spacing: 2px; }
	.catalog-title { margin-top: 8px; color: #f3f5f4; font-size: 32px; font-weight: 760; line-height: 1.2; letter-spacing: -.45px; }
	.catalog-subtitle { margin-top: 8px; color: #a0aaa5; font-size: 15px; line-height: 1.6; }
	.catalog-search { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; gap: 8px; margin-bottom: 20px; }
	.catalog-search-input { min-width: 0; height: 48px; padding: 0 14px; border: 1px solid #303733; border-radius: 12px; background: #151816; color: #f3f5f4; font-size: 16px; box-sizing: border-box; }
	.catalog-search-input::placeholder { color: #69736d; }
	.catalog-search-submit, .catalog-search-clear { min-width: 72px; height: 48px; margin: 0; padding: 0 14px; display: flex; align-items: center; justify-content: center; border-radius: 12px; font-size: 14px; font-weight: 700; }
	.catalog-search-submit { border: 1px solid #37d39a; background: rgba(55, 211, 154, .12); color: #a8e7ca; }
	.catalog-search-clear { border: 1px solid #4d6258; background: #202520; color: #dce5e0; }
	.catalog-search-submit::after, .catalog-search-clear::after { border: 0; }
	.catalog-refresh, .catalog-retry, .catalog-load-more { @include user-frosted-control; min-height: 48px; box-sizing: border-box; font-size: 14px; font-weight: 650; line-height: 1.2; }
	.catalog-refresh { min-width: 72px; margin: 0; padding: 0 12px; gap: 6px; border-radius: 12px; color: #dce5e0; }
	.catalog-refresh::after, .catalog-retry::after, .catalog-load-more::after, .catalog-model-row::after { border: 0; }
	.catalog-model-row:active { transform: scale(.985); }
	.catalog-refresh:disabled, .catalog-load-more:disabled { opacity: .55; }
	.catalog-skeleton, .catalog-error, .catalog-empty { @include user-frosted-surface; min-height: 250px; box-sizing: border-box; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 28px; border-radius: 16px; text-align: center; }
	.catalog-skeleton { align-items: stretch; gap: 14px; }
	.catalog-skeleton-row { display: flex; align-items: center; gap: 14px; padding: 14px 0; }
	.catalog-skeleton-icon, .catalog-skeleton-line { background: #202520; animation: catalog-skeleton-pulse 1.2s ease-in-out infinite alternate; }
	.catalog-skeleton-icon { width: 48px; height: 48px; flex-shrink: 0; border-radius: 14px; }
	.catalog-skeleton-copy { flex: 1; display: flex; flex-direction: column; gap: 8px; }
	.catalog-skeleton-line { height: 11px; border-radius: 6px; }
	.catalog-skeleton-line-title { width: 42%; }
	.catalog-skeleton-line-copy { width: 88%; }
	.catalog-skeleton-line-copy.short { width: 62%; }
	.catalog-state-title { margin-top: 14px; color: #f3f5f4; font-size: 18px; font-weight: 700; }
	.catalog-state-copy { margin-top: 8px; color: #8b9690; font-size: 14px; line-height: 1.6; }
	.catalog-retry { min-width: 120px; margin-top: 18px; padding: 0 18px; border: 1px solid #37d39a; border-radius: 12px; background: rgba(55, 211, 154, .12); color: #a8e7ca; }
	.catalog-summary { margin: 0 4px 10px; display: flex; align-items: center; justify-content: space-between; gap: 12px; color: #8b9690; font-size: 13px; }
	.catalog-summary-warning { color: #f2a24d; text-align: right; }
	.catalog-list { @include user-frosted-surface; overflow: hidden; border-radius: 16px; }
	.catalog-model-row { width: 100%; min-height: 118px; margin: 0; padding: 16px; display: flex; align-items: flex-start; gap: 12px; border: 0; border-bottom: 1px solid rgba(151, 170, 160, .16); border-radius: 0; background: transparent; color: inherit; text-align: left; box-sizing: border-box; transition: transform 100ms ease-out, background-color 140ms ease-out; }
	.catalog-model-row:last-child { border-bottom: 0; }
	.catalog-model-icon { width: 48px; height: 48px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; overflow: hidden; border-radius: 14px; background: #1b211d; }
	.catalog-model-icon-image { display: block; }
	.catalog-model-copy { min-width: 0; flex: 1; }
	.catalog-model-title-row { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
	.catalog-model-name { min-width: 0; overflow: hidden; color: #f3f5f4; font-size: 16px; font-weight: 700; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
	.catalog-model-vendor { flex-shrink: 0; color: #65c7c2; font-size: 12px; line-height: 1.35; }
	.catalog-model-description { display: -webkit-box; margin-top: 5px; overflow: hidden; color: #a0aaa5; font-size: 13px; line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
	.catalog-text-match { color: #f2a24d; font-weight: 700; }
	.catalog-model-tags { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 6px; }
	.catalog-model-tag { padding: 4px 7px; border-radius: 8px; background: #202520; color: #b8c3bd; font-size: 11px; line-height: 1; }
	.catalog-ratio-grid { margin-top: 12px; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); border-top: 1px solid #303733; }
	.catalog-ratio-cell { min-width: 0; padding-top: 8px; display: flex; flex-direction: column; gap: 3px; color: #8b9690; font-size: 11px; line-height: 1.25; }
	.catalog-ratio-cell + .catalog-ratio-cell { padding-left: 8px; border-left: 1px solid #303733; }
	.catalog-ratio-cell text:last-child { overflow: hidden; color: #dce5e0; font-size: 12px; font-variant-numeric: tabular-nums; text-overflow: ellipsis; white-space: nowrap; }
	.catalog-model-arrow { align-self: center; flex-shrink: 0; }
	.catalog-footer { min-height: 88px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; }
	.catalog-footer-error { color: #f2a24d; font-size: 13px; text-align: center; }
	.catalog-load-more { min-width: 150px; padding: 0 18px; border-radius: 12px; color: #dce5e0; }
	.catalog-finished { color: #69736d; font-size: 13px; }
	.catalog-search-input:focus-visible, .catalog-search-submit:focus-visible, .catalog-search-clear:focus-visible, .catalog-refresh:focus-visible, .catalog-retry:focus-visible, .catalog-load-more:focus-visible, .catalog-model-row:focus-visible { outline: 3px solid rgba(55, 211, 154, .28); outline-offset: -3px; }
	@keyframes catalog-skeleton-pulse { from { opacity: .45; } to { opacity: .95; } }
	@media (hover: hover) and (pointer: fine) {
		.catalog-refresh:hover, .catalog-load-more:hover { border-color: #4d6258; background: #202520; }
		.catalog-retry:hover { background: rgba(55, 211, 154, .2); }
		.catalog-model-row:hover { background: rgba(243, 245, 244, .035); }
	}
	@media screen and (min-width: 768px) { .catalog-shell { padding-top: 48px; } }
	@media screen and (max-width: 520px) { .catalog-search { grid-template-columns: minmax(0, 1fr) auto; } .catalog-search-clear { grid-column: 1 / -1; } }
	@media screen and (min-width: 1024px) {
		.catalog-scroll { height: 100%; }
		.catalog-shell { max-width: 800px; padding: 48px 24px; }
	}
	@media (prefers-reduced-motion: reduce) {
		.catalog-refresh, .catalog-retry, .catalog-load-more, .catalog-model-row { transition: none; }
		.catalog-skeleton-icon, .catalog-skeleton-line { animation: none; }
	}
</style>
