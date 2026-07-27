<template>
	<view class="model-form" :aria-busy="busy">
		<view class="form-section">
			<view class="section-heading">
				<text class="section-index">01</text>
				<view>
					<text class="section-title">模型身份</text>
					<text class="section-copy">名称与厂商会由后端统一去空白并转换为小写。</text>
				</view>
			</view>
			<view class="field-grid">
				<view class="field-block">
					<text class="field-label">模型名称</text>
					<input
						class="field-control"
						type="text"
						:value="modelValue.modelName"
						:disabled="readonly || busy"
						:aria-invalid="Boolean(errors.modelName)"
						:aria-describedby="errors.modelName ? 'model-name-error' : undefined"
						aria-label="模型名称"
						maxlength="128"
						placeholder="例如 gpt-5.6"
						@input="updateField('modelName', $event.detail.value)"
					/>
					<text v-if="errors.modelName" id="model-name-error" class="field-error" role="alert">{{ errors.modelName }}</text>
				</view>
				<view class="field-block">
					<text class="field-label">厂商</text>
					<input
						class="field-control"
						type="text"
						:value="modelValue.vendor"
						:disabled="readonly || busy"
						:aria-invalid="Boolean(errors.vendor)"
						:aria-describedby="errors.vendor ? 'model-vendor-error' : undefined"
						aria-label="模型厂商"
						maxlength="128"
						placeholder="例如 openai"
						@input="updateField('vendor', $event.detail.value)"
					/>
					<text v-if="errors.vendor" id="model-vendor-error" class="field-error" role="alert">{{ errors.vendor }}</text>
				</view>
			</view>
			<view class="field-block">
				<text class="field-label">模型说明</text>
				<textarea
					class="field-control field-textarea"
					:value="modelValue.description"
					:disabled="readonly || busy"
					:aria-invalid="Boolean(errors.description)"
					:aria-describedby="errors.description ? 'model-description-error' : undefined"
					aria-label="模型说明"
					maxlength="4000"
					placeholder="记录模型定位、上下文限制或内部使用说明"
					@input="updateField('description', $event.detail.value)"
				/>
				<view class="field-meta">
					<text v-if="errors.description" id="model-description-error" class="field-error" role="alert">{{ errors.description }}</text>
					<text v-else>可选 · 最多 4000 字符</text>
					<text class="numeric">{{ String(modelValue.description || '').length }} / 4000</text>
				</view>
			</view>
		</view>

		<view class="form-section">
			<view class="section-heading">
				<text class="section-index">02</text>
				<view>
					<text class="section-title">计费倍率</text>
					<text class="section-copy">输入和输出倍率独立保存，最多保留八位小数。</text>
				</view>
			</view>
			<view class="ratio-grid">
				<view class="ratio-field">
					<text class="field-label">输入倍率</text>
					<view class="ratio-control">
						<text aria-hidden="true">IN</text>
						<input
							type="text"
							inputmode="decimal"
							:value="modelValue.inputRatio"
							:disabled="readonly || busy"
							:aria-invalid="Boolean(errors.inputRatio)"
							:aria-describedby="errors.inputRatio ? 'model-input-ratio-error' : undefined"
							aria-label="输入倍率"
							placeholder="1.00000000"
							@input="updateField('inputRatio', $event.detail.value)"
						/>
					</view>
					<text v-if="errors.inputRatio" id="model-input-ratio-error" class="field-error" role="alert">{{ errors.inputRatio }}</text>
				</view>
				<view class="ratio-field">
					<text class="field-label">输出倍率</text>
					<view class="ratio-control">
						<text aria-hidden="true">OUT</text>
						<input
							type="text"
							inputmode="decimal"
							:value="modelValue.outputRatio"
							:disabled="readonly || busy"
							:aria-invalid="Boolean(errors.outputRatio)"
							:aria-describedby="errors.outputRatio ? 'model-output-ratio-error' : undefined"
							aria-label="输出倍率"
							placeholder="1.00000000"
							@input="updateField('outputRatio', $event.detail.value)"
						/>
					</view>
					<text v-if="errors.outputRatio" id="model-output-ratio-error" class="field-error" role="alert">{{ errors.outputRatio }}</text>
				</view>
			</view>
		</view>

		<view class="form-section">
			<view class="section-heading">
				<text class="section-index">03</text>
				<view>
					<text class="section-title">能力矩阵</text>
					<text class="section-copy">至少选择一项；编辑时提交会整组替换能力集合。</text>
				</view>
			</view>
			<view
				class="capability-grid"
				role="group"
				aria-label="模型能力"
				:aria-describedby="errors.capabilities ? 'model-capabilities-error' : undefined"
			>
				<button
					v-for="option in capabilityOptions"
					:key="option.code"
					class="capability-option"
					:class="{ selected: selected(option.code) }"
					type="button"
					:disabled="readonly || busy"
					:aria-pressed="selected(option.code)"
					@click="toggleCapability(option.code)"
				>
					<view class="capability-indicator" aria-hidden="true">
						<view class="capability-dot" />
					</view>
					<view>
						<text class="capability-label">{{ option.label }}</text>
						<text class="capability-hint">{{ option.hint }}</text>
					</view>
				</button>
			</view>
			<text v-if="errors.capabilities" id="model-capabilities-error" class="field-error capability-error" role="alert">
				{{ errors.capabilities }}
			</text>
		</view>

		<view class="form-section">
			<view class="section-heading">
				<text class="section-index">04</text>
				<view>
					<text class="section-title">展示元数据</text>
					<text class="section-copy">标签用于管理端辨识；图标从独立资源库选择，可被多个模型复用。</text>
				</view>
			</view>
			<view class="field-block">
				<text class="field-label">标签</text>
				<textarea
					class="field-control compact-textarea"
					:value="modelValue.tagsText"
					:disabled="readonly || busy"
					:aria-invalid="Boolean(errors.tagsText)"
					:aria-describedby="errors.tagsText ? 'model-tags-error' : undefined"
					aria-label="模型标签"
					placeholder="使用逗号或换行分隔，例如 chat, reasoning"
					@input="updateField('tagsText', $event.detail.value)"
				/>
				<text v-if="errors.tagsText" id="model-tags-error" class="field-error" role="alert">{{ errors.tagsText }}</text>
				<text v-else class="field-help">最多 20 个标签，每个标签不超过 64 个字符。</text>
			</view>
			<view class="field-block">
				<view class="icon-field-heading">
					<view>
						<text class="field-label">模型图标</text>
						<text class="field-help">这里只保存图标公共 ID，展示 URL 由后端关联查询。</text>
					</view>
					<button class="manage-icons" type="button" :disabled="busy" @click="$emit('manage-icons')">管理图标库</button>
				</view>
				<view v-if="selectedIcon" class="selected-icon">
					<image class="icon-preview" :src="selectedIcon.iconUrl" mode="aspectFit" :alt="selectedIcon.iconName" />
					<view>
						<text class="icon-name">{{ selectedIcon.iconName }}</text>
						<text class="icon-description">{{ selectedIcon.description || '暂无描述' }}</text>
					</view>
				</view>
				<template v-if="!readonly">
					<input
						class="field-control icon-search"
						type="search"
						:value="iconSearch"
						:disabled="busy || iconLoading"
						aria-label="筛选模型图标"
						maxlength="128"
						placeholder="按名称或描述筛选图标"
						@input="iconSearch = $event.detail.value"
					/>
					<view class="icon-options" role="radiogroup" aria-label="可选模型图标">
						<button
							class="icon-option no-icon-option"
							:class="{ selected: !modelValue.iconPublicId }"
							type="button"
							:disabled="busy || iconLoading"
							:aria-pressed="!modelValue.iconPublicId"
							@click="selectIcon(null)"
						>
							<view class="empty-icon" aria-hidden="true">—</view>
							<view>
								<text class="icon-name">不使用图标</text>
								<text class="icon-description">清除当前模型的图标关联</text>
							</view>
						</button>
						<button
							v-for="icon in filteredIconOptions"
							:key="icon.publicId"
							class="icon-option"
							:class="{ selected: modelValue.iconPublicId === icon.publicId }"
							type="button"
							:disabled="busy || iconLoading"
							:aria-pressed="modelValue.iconPublicId === icon.publicId"
							@click="selectIcon(icon)"
						>
							<image class="icon-preview" :src="icon.iconUrl" mode="aspectFit" :alt="icon.iconName" />
							<view>
								<text class="icon-name">{{ icon.iconName }}</text>
								<text class="icon-description">{{ icon.description || '暂无描述' }}</text>
							</view>
						</button>
					</view>
					<text v-if="iconLoading" class="field-help">正在加载图标资源…</text>
					<text v-else-if="!filteredIconOptions.length" class="field-help">没有匹配的图标，可先进入图标库新增。</text>
				</template>
				<text v-if="errors.iconPublicId" id="model-icon-error" class="field-error" role="alert">{{ errors.iconPublicId }}</text>
			</view>
		</view>
	</view>
</template>

<script>
import { AI_MODEL_CAPABILITY_OPTIONS, cloneAiModelForm } from '@/common/admin/admin-ai-model-form.js'

export default {
	name: 'AiModelForm',
	props: {
		modelValue: { type: Object, required: true },
		errors: { type: Object, default: () => ({}) },
		readonly: { type: Boolean, default: false },
		busy: { type: Boolean, default: false },
		iconOptions: { type: Array, default: () => [] },
		iconLoading: { type: Boolean, default: false }
	},
	emits: ['update:modelValue', 'manage-icons'],
	data() {
		return {
			capabilityOptions: AI_MODEL_CAPABILITY_OPTIONS,
			iconSearch: ''
		}
	},
	computed: {
		filteredIconOptions() {
			const query = String(this.iconSearch || '').trim().toLowerCase()
			if (!query) return this.iconOptions
			return this.iconOptions.filter(icon =>
				`${icon?.iconName || ''} ${icon?.description || ''}`.toLowerCase().includes(query))
		},
		selectedIcon() {
			const selected = this.iconOptions.find(
				icon => icon.publicId === this.modelValue.iconPublicId)
			if (selected) return selected
			if (!this.modelValue.iconPublicId || !this.modelValue.iconPreviewUrl) return null
			return {
				publicId: this.modelValue.iconPublicId,
				iconName: '当前模型图标',
				iconUrl: this.modelValue.iconPreviewUrl,
				description: '图标资源列表暂未包含该记录'
			}
		}
	},
	methods: {
		selected(code) {
			return Array.isArray(this.modelValue.capabilities)
				&& this.modelValue.capabilities.includes(code)
		},
		updateField(field, value) {
			if (this.readonly || this.busy) return
			this.$emit('update:modelValue', {
				...cloneAiModelForm(this.modelValue),
				[field]: value
			})
		},
		selectIcon(icon) {
			if (this.readonly || this.busy || this.iconLoading) return
			this.$emit('update:modelValue', {
				...cloneAiModelForm(this.modelValue),
				iconPublicId: icon?.publicId || '',
				iconPreviewUrl: icon?.iconUrl || ''
			})
		},
		toggleCapability(code) {
			if (this.readonly || this.busy) return
			const selected = new Set(this.modelValue.capabilities || [])
			if (selected.has(code)) selected.delete(code)
			else selected.add(code)
			this.updateField(
				'capabilities',
				AI_MODEL_CAPABILITY_OPTIONS
					.map(option => option.code)
					.filter(optionCode => selected.has(optionCode)))
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.model-form { display: grid; gap: 24rpx; }
.form-section {
	padding: 28rpx;
	border: 1px solid rgba(105, 212, 226, .15);
	border-radius: $app-radius-panel;
	background: $app-surface;
}
.section-heading { display: grid; grid-template-columns: 52rpx minmax(0, 1fr); gap: 18rpx; margin-bottom: 26rpx; }
.section-index { color: $app-green; font-size: 18rpx; font-weight: 800; letter-spacing: .12em; }
.section-title { display: block; color: $app-text; font-size: 28rpx; font-weight: 780; letter-spacing: -.015em; }
.section-copy { display: block; margin-top: 6rpx; color: $app-muted; font-size: 21rpx; line-height: 1.5; }
.field-grid, .ratio-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20rpx; }
.field-block, .ratio-field { min-width: 0; margin-top: 20rpx; }
.field-grid .field-block, .ratio-grid .ratio-field { margin-top: 0; }
.field-label { display: block; margin-bottom: 10rpx; color: #c9d5d8; font-size: 21rpx; font-weight: 690; }
.field-control, .ratio-control {
	width: 100%;
	min-height: 88rpx;
	box-sizing: border-box;
	border: 1px solid $app-border;
	border-radius: $app-radius-control;
	background: #0b1115;
	color: $app-text;
	font-size: 24rpx;
}
.field-control { padding: 0 20rpx; }
.field-control:focus, .ratio-control:focus-within { border-color: $app-focus; box-shadow: 0 0 0 4rpx rgba(139, 231, 228, .1); }
.field-control[disabled] { color: #b7c4c8; background: rgba(20, 29, 34, .5); opacity: 1; }
.ratio-control input[disabled] { color: #b7c4c8; opacity: 1; }
.field-textarea { min-height: 210rpx; padding-top: 18rpx; line-height: 1.55; }
.compact-textarea { min-height: 128rpx; padding-top: 18rpx; line-height: 1.5; }
.field-meta { display: flex; justify-content: space-between; gap: 20rpx; margin-top: 8rpx; color: $app-muted; font-size: 18rpx; }
.field-help { display: block; margin-top: 8rpx; color: $app-muted; font-size: 18rpx; }
.field-error { display: block; margin-top: 8rpx; color: $app-danger-text; font-size: 19rpx; line-height: 1.45; }
.icon-field-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 18rpx; }
.manage-icons {
	margin: 0;
	padding: 0 18rpx;
	min-height: 58rpx;
	border: 1px solid rgba(57, 214, 210, .32);
	border-radius: 10rpx;
	background: rgba(57, 214, 210, .07);
	color: $app-green;
	font-size: 19rpx;
}
.selected-icon {
	margin-top: 14rpx;
	padding: 16rpx;
	border: 1px solid rgba(57, 214, 210, .32);
	border-radius: $app-radius-control;
	display: grid;
	grid-template-columns: 60rpx minmax(0, 1fr);
	gap: 14rpx;
	align-items: center;
	background: rgba(57, 214, 210, .06);
}
.icon-search { margin-top: 14rpx; }
.icon-options {
	max-height: 380rpx;
	margin-top: 12rpx;
	padding-right: 4rpx;
	display: grid;
	grid-template-columns: repeat(2, minmax(0, 1fr));
	gap: 10rpx;
	overflow-y: auto;
}
.icon-option {
	min-height: 86rpx;
	margin: 0;
	padding: 12rpx;
	border: 1px solid $app-border;
	border-radius: $app-radius-control;
	display: grid;
	grid-template-columns: 56rpx minmax(0, 1fr);
	gap: 12rpx;
	align-items: center;
	background: #0b1115;
	color: $app-text;
	text-align: left;
}
.icon-option.selected { border-color: rgba(57, 214, 210, .62); background: rgba(57, 214, 210, .08); }
.icon-preview, .empty-icon {
	width: 56rpx;
	height: 56rpx;
	border-radius: 10rpx;
	background: rgba(255, 255, 255, .05);
}
.empty-icon { display: grid; place-items: center; color: $app-muted; }
.icon-name, .icon-description { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.icon-name { color: $app-text; font-size: 20rpx; font-weight: 720; }
.icon-description { margin-top: 4rpx; color: $app-muted; font-size: 17rpx; }
.ratio-control { display: grid; grid-template-columns: 68rpx minmax(0, 1fr); align-items: center; padding: 0 18rpx; }
.ratio-control > text { color: $app-green; font-size: 17rpx; font-weight: 820; letter-spacing: .08em; }
.ratio-control input { width: 100%; color: inherit; font-size: 28rpx; font-variant-numeric: tabular-nums; }
.capability-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14rpx; }
.capability-option {
	min-height: 104rpx;
	margin: 0;
	padding: 16rpx 18rpx;
	border: 1px solid $app-border;
	border-radius: $app-radius-control;
	display: grid;
	grid-template-columns: 30rpx minmax(0, 1fr);
	gap: 14rpx;
	align-items: start;
	background: #0b1115;
	color: $app-text;
	text-align: left;
}
.capability-option.selected { border-color: rgba(57, 214, 210, .62); background: rgba(57, 214, 210, .08); }
.capability-indicator { width: 26rpx; height: 26rpx; margin-top: 3rpx; border: 1px solid $app-border; border-radius: 50%; display: grid; place-items: center; }
.capability-option.selected .capability-indicator { border-color: $app-green; }
.capability-dot { width: 12rpx; height: 12rpx; border-radius: 50%; background: transparent; }
.capability-option.selected .capability-dot { background: $app-green; }
.capability-label, .capability-hint { display: block; }
.capability-label { font-size: 22rpx; font-weight: 740; }
.capability-hint { margin-top: 4rpx; color: $app-muted; font-size: 18rpx; line-height: 1.35; }
.capability-error { margin-top: 12rpx; }
.numeric { font-variant-numeric: tabular-nums; }
button::after { border: 0; }
button:focus-visible, input:focus-visible, textarea:focus-visible { outline: 2px solid $app-focus; outline-offset: 2px; }

@media (min-width: 960px) {
	.model-form { grid-template-columns: 1.15fr .85fr; align-items: start; }
	.form-section:first-child, .form-section:last-child { grid-column: 1; }
	.form-section:nth-child(2), .form-section:nth-child(3) { grid-column: 2; }
	.form-section:nth-child(2) { grid-row: 1; }
	.form-section:nth-child(3) { grid-row: 2 / span 2; }
	.ratio-grid { grid-template-columns: 1fr; }
}

@media (max-width: 640px) {
	.form-section { padding: 24rpx 20rpx; border-left: 0; border-right: 0; border-radius: 0; }
	.field-grid, .ratio-grid, .capability-grid { grid-template-columns: 1fr; }
	.icon-options { grid-template-columns: 1fr; }
	.field-control, .ratio-control, .capability-option { min-height: 96rpx; }
}

@media (prefers-reduced-motion: reduce) {
	* { scroll-behavior: auto !important; transition-duration: .01ms !important; }
}
</style>
