<template>
	<view class="api-key-model-picker" :aria-busy="loading">
		<view class="model-picker-toolbar">
			<input
				v-model.trim="keywordDraft"
				class="model-picker-search"
				type="text"
				maxlength="128"
				placeholder="搜索模型名称或厂商"
				aria-label="搜索可授权模型"
				@confirm="search"
			/>
			<button class="model-picker-search-button" type="button" :disabled="loading" @click="search">搜索</button>
		</view>

		<view class="model-picker-summary">
			<text>已选择 {{ selectedCount }} / 500</text>
			<text v-if="minimumModels > 0">至少选择 {{ minimumModels }} 个模型</text>
			<text v-else>允许清空全部授权</text>
		</view>
		<text v-if="selectionLimitReached" class="model-picker-limit" role="status">已达到 500 个模型的授权上限。</text>

		<view v-if="disabledModels.length" class="model-picker-disabled" role="status">
			<text class="model-picker-disabled-title">已停用的原授权</text>
			<text v-for="model in disabledModels" :key="model.modelPublicId" class="model-picker-disabled-item">
				{{ model.modelName }} · {{ model.vendor }}（保存后移除）
			</text>
		</view>

		<view v-if="error && !models.length" class="model-picker-state model-picker-error" role="alert">
			<text>{{ error }}</text>
			<button type="button" @click="refresh">重新加载</button>
		</view>
		<view v-else-if="loading && !models.length" class="model-picker-state" role="status">正在读取可用模型…</view>
		<view v-else-if="!models.length" class="model-picker-state" role="status">没有匹配的可用模型</view>
		<view v-else class="model-picker-list" role="group" aria-label="可授权模型">
			<button
				v-for="model in models"
				:key="model.publicId"
				class="model-picker-option"
				:class="{ 'is-selected': isSelected(model.publicId) }"
				type="button"
				:disabled="selectionLimitReached && !isSelected(model.publicId)"
				:aria-pressed="String(isSelected(model.publicId))"
				@click="toggleModel(model)"
			>
				<view class="model-picker-check" aria-hidden="true">{{ isSelected(model.publicId) ? '✓' : '' }}</view>
				<view class="model-picker-copy">
					<text class="model-picker-name">{{ model.modelName }}</text>
					<text class="model-picker-vendor">{{ model.vendor }}</text>
				</view>
			</button>
		</view>

		<view v-if="error && models.length" class="model-picker-inline-error" role="alert">{{ error }}</view>
		<button
			v-if="hasNext"
			class="model-picker-more"
			type="button"
			:disabled="loading"
			@click="loadMore"
		>
			{{ loading ? '正在加载…' : '加载更多模型' }}
		</button>
	</view>
</template>

<script>
	import { aiModelApi } from '@/common/aimodel/ai-model-api.js'

	export default {
		props: {
			selectedIds: { type: Array, default: () => [] },
			minimumModels: { type: Number, default: 0 },
			disabledModels: { type: Array, default: () => [] }
		},
		data() {
			return {
				models: [],
				selectedById: new Map(),
				selectionVersion: 0,
				keywordDraft: '',
				activeKeyword: '',
				pageNum: 0,
				hasNext: false,
				loading: false,
				error: '',
				requestGeneration: 0
			}
		},
		computed: {
			selectedCount() {
				void this.selectionVersion
				return this.selectedById.size
			},
			selectionLimitReached() {
				return this.selectedCount >= 500
			}
		},
		watch: {
			selectedIds: {
				immediate: true,
				deep: true,
				handler(ids) {
					const next = new Map()
					for (const id of ids || []) {
						if (typeof id === 'string' && !next.has(id)) next.set(id, true)
					}
					this.selectedById = next
					this.selectionVersion += 1
				}
			}
		},
		mounted() {
			this.refresh()
		},
		beforeDestroy() {
			this.releasePickerState()
		},
		beforeUnmount() {
			this.releasePickerState()
		},
		methods: {
			releasePickerState() {
				this.requestGeneration += 1
				this.selectedById.clear()
			},
			isSelected(publicId) {
				void this.selectionVersion
				return this.selectedById.has(publicId)
			},
			toggleModel(model) {
				if (this.selectedById.has(model.publicId)) {
					this.selectedById.delete(model.publicId)
				} else if (!this.selectionLimitReached) {
					this.selectedById.set(model.publicId, model)
				}
				this.selectionVersion += 1
				this.$emit('change', [...this.selectedById.keys()])
			},
			search() {
				this.activeKeyword = this.keywordDraft.trim()
				this.refresh()
			},
			refresh() {
				this.requestGeneration += 1
				// 旧请求已被代次作废；立即释放加载锁，允许新关键词请求接管界面。
				this.loading = false
				this.models = []
				this.pageNum = 0
				this.hasNext = false
				this.error = ''
				return this.loadPage(1, false)
			},
			loadMore() {
				if (!this.loading && this.hasNext) return this.loadPage(this.pageNum + 1, true)
			},
			async loadPage(pageNum, append) {
				if (this.loading) return
				const generation = this.requestGeneration
				this.loading = true
				this.error = ''
				try {
					const page = await aiModelApi.list({
						pageNum,
						pageSize: 50,
						keyword: this.activeKeyword
					})
					if (generation !== this.requestGeneration) return
					const byId = new Map((append ? this.models : []).map(model => [model.publicId, model]))
					for (const model of page.models) byId.set(model.publicId, model)
					this.models = [...byId.values()]
					this.pageNum = page.pageNum
					this.hasNext = page.hasNext
				} catch (error) {
					if (generation === this.requestGeneration) {
						this.error = error?.message || '可用模型暂时无法加载。'
					}
				} finally {
					if (generation === this.requestGeneration) this.loading = false
				}
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.api-key-model-picker { min-width: 0; }
	.model-picker-toolbar { display: flex; gap: 10px; }
	.model-picker-search { min-width: 0; height: 46px; flex: 1; box-sizing: border-box; padding: 0 14px; border: 1px solid rgba(151, 170, 160, .22); border-radius: 12px; background: #101310; color: #f3f5f4; font-size: 14px; }
	.model-picker-search:focus { border-color: rgba(55, 211, 154, .64); outline: 2px solid rgba(55, 211, 154, .18); }
	.model-picker-search-button, .model-picker-more, .model-picker-state button { @include user-frosted-control; margin: 0; padding: 0 16px; border-radius: 12px; color: #dce5e0; font-size: 14px; }
	.model-picker-summary { display: flex; justify-content: space-between; gap: 12px; margin: 10px 2px; color: #8f9b95; font-size: 12px; }
	.model-picker-limit { display: block; margin: -2px 2px 10px; color: #efc18a; font-size: 12px; }
	.model-picker-disabled { display: flex; flex-direction: column; gap: 4px; margin: 12px 0; padding: 12px 14px; border: 1px solid rgba(222, 157, 80, .28); border-radius: 12px; background: rgba(201, 130, 47, .08); color: #d9b17a; font-size: 12px; }
	.model-picker-disabled-title { color: #efc18a; font-weight: 700; }
	.model-picker-list { max-height: 310px; display: flex; flex-direction: column; gap: 8px; overflow-y: auto; overscroll-behavior: contain; }
	.model-picker-option { width: 100%; min-height: 58px; margin: 0; padding: 10px 12px; display: flex; align-items: center; gap: 12px; border: 1px solid rgba(151, 170, 160, .18); border-radius: 13px; background: #141816; color: #f3f5f4; text-align: left; }
	.model-picker-option.is-selected { border-color: rgba(55, 211, 154, .52); background: rgba(55, 211, 154, .08); }
	.model-picker-option:focus-visible { outline: 2px solid rgba(55, 211, 154, .72); outline-offset: 2px; }
	.model-picker-option[disabled] { opacity: .46; }
	.model-picker-check { width: 22px; height: 22px; flex: 0 0 22px; display: flex; align-items: center; justify-content: center; border: 1px solid rgba(151, 170, 160, .38); border-radius: 7px; color: #37d39a; }
	.model-picker-copy { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
	.model-picker-name { overflow: hidden; font-size: 14px; font-weight: 680; text-overflow: ellipsis; white-space: nowrap; }
	.model-picker-vendor { color: #8f9b95; font-size: 12px; text-transform: uppercase; }
	.model-picker-state { min-height: 112px; display: flex; align-items: center; justify-content: center; gap: 12px; flex-direction: column; color: #8f9b95; text-align: center; }
	.model-picker-error, .model-picker-inline-error { color: #efb0aa; }
	.model-picker-inline-error { margin-top: 10px; font-size: 12px; }
	.model-picker-more { width: 100%; margin-top: 12px; }
	@media screen and (max-width: 560px) { .model-picker-toolbar { flex-direction: column; } .model-picker-summary { flex-direction: column; gap: 3px; } .model-picker-list { max-height: 38dvh; } }
	@media (prefers-reduced-motion: reduce) { .model-picker-option { transition: none; } }
</style>
