<template>
	<view class="api-key-page">
		<scroll-view class="api-key-scroll" scroll-y>
			<view class="api-key-shell" :aria-busy="loading || appendLoading">
				<view class="api-key-heading-row">
					<view class="api-key-heading">
						<text class="api-key-kicker">DEVELOPER TOOLS</text>
						<text class="api-key-title">管理我的 API Key</text>
						<text class="api-key-subtitle">为外部 Agent 和 OpenAI 兼容客户端管理访问凭证。</text>
					</view>
					<view class="api-key-heading-actions">
						<button class="api-key-refresh" type="button" :disabled="loading" @click="refreshKeys">
							<uni-icons type="refreshempty" size="18" color="#dce5e0" aria-hidden="true" />
							<text>刷新</text>
						</button>
						<button ref="createButton" class="api-key-create" type="button" @click="openCreateDialog">
							<uni-icons type="plusempty" size="19" color="#75dfb7" aria-hidden="true" />
							<text>创建 API Key</text>
						</button>
					</view>
				</view>

				<view class="api-key-security-card" role="note">
					<uni-icons type="locked" size="20" color="#efc18a" aria-hidden="true" />
					<view>
						<text class="api-key-security-title">请像保护密码一样保护 API Key</text>
						<text class="api-key-security-copy">完整内容只在创建成功后显示一次。不要把它放入浏览器页面、公开仓库、日志或聊天记录。</text>
					</view>
				</view>

				<view class="api-key-section">
					<text class="api-key-section-title">连接信息</text>
					<view class="api-key-connection-card">
						<view class="api-key-connection-row">
							<view><text class="label">Base URL</text><text class="value">{{ compatibleBaseUrl }}</text></view>
							<button type="button" aria-label="复制 Base URL" @click="copyBaseUrl">复制</button>
						</view>
						<view class="api-key-connection-row"><view><text class="label">Endpoint</text><text class="value">{{ chatEndpoint }}</text></view></view>
						<view class="api-key-connection-row"><view><text class="label">第一版要求</text><text class="value">stream=true</text></view></view>
					</view>
				</view>

				<view class="api-key-section">
					<view class="api-key-section-heading">
						<text class="api-key-section-title">API Key</text>
						<text v-if="items.length" class="api-key-count">已加载 {{ items.length }} 个</text>
					</view>

					<view v-if="loading && !items.length" class="api-key-list" aria-label="正在加载 API Key">
						<view v-for="index in 3" :key="index" class="api-key-skeleton" aria-hidden="true">
							<view class="line wide"></view><view class="line"></view><view class="line short"></view>
						</view>
					</view>

					<view v-else-if="listError && !items.length" class="api-key-state" role="alert">
						<uni-icons type="info" size="24" color="#efb0aa" aria-hidden="true" />
						<text>{{ listError }}</text>
						<button type="button" @click="refreshKeys">重新加载</button>
					</view>

					<view v-else-if="!items.length" class="api-key-state api-key-empty" role="status">
						<view class="api-key-empty-icon" aria-hidden="true">K</view>
						<text class="api-key-empty-title">还没有 API Key</text>
						<text class="api-key-empty-copy">创建后即可用于 Codex、Claude Code、Apifox 和 OpenAI 兼容客户端。</text>
						<button type="button" @click="openCreateDialog">创建第一个 API Key</button>
					</view>

					<view v-else class="api-key-list">
						<view v-for="item in items" :key="item.id" class="api-key-list-card">
							<view class="api-key-list-topline">
								<text class="api-key-masked">{{ item.maskedKey }}</text>
								<text class="api-key-status" :class="`is-${statusOf(item).tone}`">{{ statusOf(item).label }}</text>
							</view>
							<view class="api-key-list-meta">
								<text>过期时间：{{ expiryText(item.expiresAt) }}</text>
								<text>最近使用：{{ timeText(item.lastUsedAt, '尚未使用') }}</text>
								<text>创建时间：{{ timeText(item.createdAt) }}</text>
							</view>
							<button class="api-key-manage" type="button" :data-api-key-id="item.id" :aria-label="`管理 ${item.maskedKey}`" @click="openEditor(item)">管理 <text aria-hidden="true">›</text></button>
						</view>

						<view v-if="appendError" class="api-key-append-error" role="alert">
							<text>{{ appendError }}</text>
							<button type="button" @click="loadMore">重试</button>
						</view>
						<button v-if="nextCursor && !appendError" class="api-key-load-more" type="button" :disabled="appendLoading" @click="loadMore">
							{{ appendLoading ? '正在加载…' : '加载更多' }}
						</button>
					</view>
				</view>
			</view>
		</scroll-view>

		<user-api-key-create-dialog
			:open="createOpen"
			:busy="createBusy"
			:error="createError"
			@close="closeCreateDialog"
			@submit="createKey"
		/>
		<user-api-key-secret-dialog
			:open="Boolean(createdSecret)"
			:secret="createdSecret"
			@acknowledge="clearCreatedSecret"
		/>
		<user-api-key-editor-sheet
			ref="editorSheet"
			:open="Boolean(editorId)"
			:api-key-public-id="editorId"
			:summary="editorSummary"
			@close="closeEditor"
			@updated="applyEditorUpdate"
			@deleted="applyEditorRemoval"
		/>
	</view>
</template>

<script>
	import { formatLocalDateTimeZhCn } from '@/common/platform/date-time.js'
	import { apiKeyApi } from '@/common/user/api-key-api.js'
	import {
		API_KEY_CHAT_ENDPOINT,
		API_KEY_COMPATIBLE_BASE_URL
	} from '@/common/user/api-key-config.js'
	import {
		apiKeyStatusPresentation,
		mergeApiKeyPageItems,
		summaryFromCreatedKey
	} from '@/common/user/api-key-state.js'
	import UserApiKeyCreateDialog from './user-api-key-create-dialog.vue'
	import UserApiKeyEditorSheet from './user-api-key-editor-sheet.vue'
	import UserApiKeySecretDialog from './user-api-key-secret-dialog.vue'

	function readableError(error, fallback) {
		if (error?.code === 'API_KEY_RESPONSE_INVALID') return '响应数据无效，请刷新后重试。'
		if (Number(error?.statusCode) === 503 || error?.code === 'HTTP_503') {
			return '服务暂时不可用，当前内容已保留，请稍后手动重试。'
		}
		return fallback
	}

	export default {
		components: {
			UserApiKeyCreateDialog,
			UserApiKeyEditorSheet,
			UserApiKeySecretDialog
		},
		props: {
			authenticated: { type: Boolean, default: false }
		},
		data() {
			return {
				items: [],
				listLoaded: false,
				nextCursor: null,
				loading: false,
				appendLoading: false,
				listError: '',
				appendError: '',
				requestGeneration: 0,
				createOpen: false,
				createBusy: false,
				createError: '',
				createdSecret: '',
				editorId: '',
				editorSummary: null
			}
		},
		computed: {
			compatibleBaseUrl() { return API_KEY_COMPATIBLE_BASE_URL },
			chatEndpoint() { return API_KEY_CHAT_ENDPOINT }
		},
		watch: {
			authenticated(value) {
				if (value) this.onAuthenticatedPageReady()
				else this.releasePageState()
			}
		},
		mounted() {
			if (this.authenticated) this.onAuthenticatedPageReady()
		},
		beforeDestroy() {
			this.releasePageState()
		},
		beforeUnmount() {
			this.releasePageState()
		},
		methods: {
			onAuthenticatedPageReady() {
				if (this.authenticated && !this.loading && !this.listLoaded && !this.listError) {
					this.refreshKeys()
				}
			},
			handlePageShow() {
				this.onAuthenticatedPageReady()
			},
			handlePageUnload() {
				this.releasePageState()
			},
			releasePageState() {
				this.requestGeneration += 1
				this.createdSecret = ''
				this.items = []
				this.listLoaded = false
				this.nextCursor = null
				this.loading = false
				this.appendLoading = false
				this.listError = ''
				this.appendError = ''
				this.createOpen = false
				this.createBusy = false
				this.createError = ''
				this.editorId = ''
				this.editorSummary = null
			},
			async refreshKeys() {
				if (!this.authenticated) return
				this.requestGeneration += 1
				this.loading = false
				this.appendLoading = false
				this.items = []
				this.listLoaded = false
				this.nextCursor = null
				this.listError = ''
				this.appendError = ''
				await this.loadPage(false)
			},
			async loadPage(append) {
				if (!this.authenticated || (append ? this.appendLoading : this.loading)) return
				const generation = this.requestGeneration
				if (append) this.appendLoading = true
				else this.loading = true
				try {
					const page = await apiKeyApi.list({
						cursor: append ? this.nextCursor : null,
						pageSize: 20
					})
					if (generation !== this.requestGeneration) return
					this.items = append
						? mergeApiKeyPageItems(this.items, page.items)
						: mergeApiKeyPageItems([], page.items)
					this.nextCursor = page.nextCursor
					this.listLoaded = true
					this.listError = ''
					this.appendError = ''
				} catch (error) {
					if (generation !== this.requestGeneration) return
					const message = readableError(error, 'API Key 列表暂时无法加载，请重试。')
					if (append) this.appendError = message
					else this.listError = message
				} finally {
					if (generation === this.requestGeneration) {
						if (append) this.appendLoading = false
						else this.loading = false
					}
				}
			},
			loadMore() {
				if (this.nextCursor) this.loadPage(true)
			},
			openCreateDialog() {
				this.createError = ''
				this.createOpen = true
			},
			closeCreateDialog() {
				if (!this.createBusy) {
					this.createOpen = false
					this.createError = ''
					this.$nextTick(() => {
						const button = this.$refs.createButton?.$el || this.$refs.createButton
						button?.focus?.({ preventScroll: true })
					})
				}
			},
			async createKey(command) {
				if (this.createBusy) return
				const generation = this.requestGeneration
				this.createBusy = true
				this.createError = ''
				try {
					const created = await apiKeyApi.create(command)
					// 页面已离开时不得把一次性完整 Key 重新挂回组件状态。
					if (generation !== this.requestGeneration) return
					this.createdSecret = created.value.apiKey
					this.items = mergeApiKeyPageItems(
						[summaryFromCreatedKey(created.value)],
						this.items
					)
					this.createOpen = false
				} catch (error) {
					if (generation !== this.requestGeneration) return
					this.createError = error?.code === 'NETWORK_ERROR'
						? '创建结果暂时无法确认。请刷新列表；如果出现了一个无法取得完整内容的新 Key，请先撤销它，再重新创建。'
						: readableError(error, 'API Key 创建失败，请检查表单后重试。')
				} finally {
					this.createBusy = false
				}
			},
			clearCreatedSecret() {
				this.createdSecret = ''
				this.$nextTick(() => {
					const button = this.$refs.createButton?.$el || this.$refs.createButton
					button?.focus?.({ preventScroll: true })
				})
			},
			openEditor(item) {
				this.editorId = item.id
				this.editorSummary = item
			},
			closeEditor() {
				const publicId = this.editorId
				this.editorId = ''
				this.editorSummary = null
				this.$nextTick(() => {
					const button = this.$el?.querySelector?.(`[data-api-key-id="${publicId}"]`)
					button?.focus?.({ preventScroll: true })
				})
			},
			applyEditorUpdate(detail) {
				const summary = summaryFromCreatedKey(detail)
				this.items = this.items.map(item => item.id === summary.id ? summary : item)
				this.editorSummary = summary
			},
			applyEditorRemoval(publicId) {
				this.items = this.items.filter(item => item.id !== publicId)
				this.closeEditor()
			},
			statusOf(item) {
				return apiKeyStatusPresentation(item)
			},
			expiryText(value) {
				return value == null ? '永久有效' : this.timeText(value)
			},
			timeText(value, empty = '时间无效') {
				if (value == null) return empty
				return formatLocalDateTimeZhCn(value) || '时间无效'
			},
			copyBaseUrl() {
				uni.setClipboardData({
					data: API_KEY_COMPATIBLE_BASE_URL,
					success: () => uni.showToast({ title: 'Base URL 已复制', icon: 'none' }),
					fail: () => uni.showToast({ title: '复制失败，请重试', icon: 'none' })
				})
			},
			closeIfOpen() {
				if (this.createdSecret) {
					uni.showToast({ title: '请先确认已经保存 API Key', icon: 'none' })
					return true
				}
				if (this.editorId) {
					this.$refs.editorSheet?.closeIfOpen()
					return true
				}
				if (this.createOpen) {
					if (this.createBusy) {
						uni.showToast({ title: '请等待创建请求完成', icon: 'none' })
					} else {
						this.closeCreateDialog()
					}
					return true
				}
				return false
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.api-key-page, .api-key-scroll { width: 100%; min-width: 0; height: 100%; background: #0b0d0c; color: #f3f5f4; }
	.api-key-shell { width: 100%; max-width: 920px; min-height: 100%; margin: 0 auto; padding: 34px 24px calc(54px + env(safe-area-inset-bottom)); box-sizing: border-box; }
	.api-key-heading-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 22px; }
	.api-key-heading { min-width: 0; display: flex; flex-direction: column; }
	.api-key-kicker { color: #43d6a1; font-size: 13px; font-weight: 800; letter-spacing: 2px; }
	.api-key-title { margin-top: 12px; font-size: clamp(28px, 4vw, 38px); font-weight: 790; line-height: 1.15; }
	.api-key-subtitle { margin-top: 10px; color: #929e98; font-size: 14px; line-height: 1.6; }
	.api-key-heading-actions { display: flex; align-items: center; gap: 10px; flex: 0 0 auto; }
	.api-key-refresh, .api-key-create, .api-key-load-more, .api-key-state button, .api-key-empty button { @include user-frosted-control; margin: 0; padding: 0 16px; border-radius: 12px; color: #dce5e0; }
	.api-key-refresh, .api-key-create { display: flex; align-items: center; gap: 8px; }
	.api-key-create { border-color: rgba(55, 211, 154, .4); background: rgba(55, 211, 154, .1); color: #75dfb7; }
	.api-key-refresh:focus-visible, .api-key-create:focus-visible, .api-key-manage:focus-visible, .api-key-load-more:focus-visible, .api-key-connection-row button:focus-visible { outline: 2px solid rgba(55, 211, 154, .78); outline-offset: 2px; }
	.api-key-security-card { display: flex; align-items: flex-start; gap: 12px; margin-top: 26px; padding: 16px 18px; border: 1px solid rgba(221, 157, 83, .28); border-radius: 15px; background: rgba(201, 130, 47, .08); }
	.api-key-security-title, .api-key-security-copy { display: block; }
	.api-key-security-title { color: #efc18a; font-size: 13px; font-weight: 720; }
	.api-key-security-copy { margin-top: 5px; color: #b9aaa0; font-size: 12px; line-height: 1.6; }
	.api-key-section { margin-top: 28px; }
	.api-key-section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0 4px 10px; }
	.api-key-section-title { display: block; margin: 0 0 10px 4px; color: #929e98; font-size: 13px; font-weight: 700; }
	.api-key-section-heading .api-key-section-title { margin: 0; }
	.api-key-count { color: #6f7b75; font-size: 12px; }
	.api-key-connection-card, .api-key-list-card, .api-key-skeleton { @include user-frosted-surface; border-radius: 16px; }
	.api-key-connection-card { overflow: hidden; }
	.api-key-connection-row { min-height: 68px; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 13px 18px; border-bottom: 1px solid rgba(151, 170, 160, .13); box-sizing: border-box; }
	.api-key-connection-row:last-child { border-bottom: 0; }
	.api-key-connection-row > view { min-width: 0; display: flex; flex-direction: column; }
	.api-key-connection-row .label { color: #839088; font-size: 12px; }
	.api-key-connection-row .value { margin-top: 5px; overflow-wrap: anywhere; color: #e4ebe7; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 13px; }
	.api-key-connection-row button { min-width: 70px; min-height: 42px; margin: 0; border: 1px solid rgba(151, 170, 160, .2); border-radius: 10px; background: #171b18; color: #b7c2bc; }
	.api-key-list { display: grid; gap: 12px; }
	.api-key-list-card { position: relative; padding: 18px 118px 18px 18px; }
	.api-key-list-topline { display: flex; align-items: center; gap: 10px; }
	.api-key-masked { overflow-wrap: anywhere; color: #edf2ef; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 15px; font-weight: 700; }
	.api-key-status { padding: 5px 9px; border-radius: 999px; font-size: 11px; font-weight: 750; }
	.api-key-status.is-enabled { background: rgba(55, 211, 154, .13); color: #75dfb7; }
	.api-key-status.is-disabled { background: rgba(151, 170, 160, .12); color: #a8b3ad; }
	.api-key-status.is-expired { background: rgba(201, 130, 47, .14); color: #efb36e; }
	.api-key-list-meta { display: flex; flex-direction: column; gap: 6px; margin-top: 14px; color: #929e98; font-size: 12px; font-variant-numeric: tabular-nums; }
	.api-key-manage { position: absolute; right: 16px; top: 50%; min-width: 84px; min-height: 44px; margin: 0; transform: translateY(-50%); border: 1px solid rgba(151, 170, 160, .2); border-radius: 11px; background: #171b18; color: #c8d1cc; }
	.api-key-manage:active { transform: translateY(-50%) scale(.97); }
	.api-key-state { @include user-frosted-surface; min-height: 230px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; padding: 28px; border-radius: 16px; color: #aab5af; text-align: center; }
	.api-key-empty-icon { width: 48px; height: 48px; display: flex; align-items: center; justify-content: center; border: 1px solid rgba(55, 211, 154, .3); border-radius: 15px; background: rgba(55, 211, 154, .08); color: #75dfb7; font-weight: 800; }
	.api-key-empty-title { color: #e5ebe8; font-size: 18px; font-weight: 740; }
	.api-key-empty-copy { max-width: 430px; color: #8f9b95; font-size: 13px; line-height: 1.6; }
	.api-key-skeleton { padding: 20px; }
	.api-key-skeleton .line { width: 56%; height: 11px; margin-top: 12px; border-radius: 999px; background: #252b27; animation: api-key-pulse 1.4s ease-in-out infinite; }
	.api-key-skeleton .line:first-child { margin-top: 0; }
	.api-key-skeleton .line.wide { width: 72%; }
	.api-key-skeleton .line.short { width: 34%; }
	.api-key-load-more { width: 100%; }
	.api-key-append-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 15px; border: 1px solid rgba(217, 93, 89, .24); border-radius: 12px; color: #efb0aa; font-size: 12px; }
	.api-key-append-error button { min-height: 42px; margin: 0; border: 0; background: transparent; color: #75dfb7; }
	@keyframes api-key-pulse { 50% { opacity: .48; } }
	@media screen and (min-width: 1024px) { .api-key-shell { padding-top: 48px; } }
	@media screen and (max-width: 700px) {
		.api-key-shell { padding: 72px 16px calc(38px + env(safe-area-inset-bottom)); }
		.api-key-heading-row { flex-direction: column; }
		.api-key-heading-actions { width: 100%; }
		.api-key-refresh, .api-key-create { min-width: 0; flex: 1; justify-content: center; }
		.api-key-list-card { padding: 17px; }
		.api-key-list-topline { flex-wrap: wrap; }
		.api-key-manage { width: 100%; position: static; margin-top: 16px; transform: none; }
		.api-key-manage:active { transform: scale(.98); }
	}
	/* #ifdef H5 */
	// H5 管理页占满剩余工作区，列表按 CSS 可视宽度增列，分页与错误反馈始终横跨整行。
	.api-key-shell {
		width: 100%;
		max-width: none;
		margin: 0;
		padding-right: var(--workspace-content-gutter, 16px);
		padding-left: var(--workspace-content-gutter, 16px);
	}
	.api-key-list {
		grid-template-columns: minmax(0, 1fr);
		gap: var(--workspace-layout-gap, 16px);
	}
	.api-key-list-card, .api-key-skeleton { min-width: 0; }
	.api-key-append-error, .api-key-load-more { grid-column: 1 / -1; }
	@media screen and (min-width: 1200px) {
		.api-key-list { grid-template-columns: repeat(2, minmax(0, 1fr)); }
	}
	@media screen and (min-width: 1920px) {
		.api-key-list { grid-template-columns: repeat(3, minmax(0, 1fr)); }
	}
	/* #endif */
	@media (prefers-reduced-motion: reduce) { .api-key-skeleton .line { animation: none; } .api-key-refresh, .api-key-create, .api-key-manage { transition: none; } }
	@media (prefers-contrast: more) { .api-key-list-card, .api-key-connection-card, .api-key-security-card { border-color: #6d7b73; } }
</style>
