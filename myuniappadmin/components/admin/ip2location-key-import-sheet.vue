<template>
	<admin-material-sheet
		:model-value="open"
		title="导入 IP2Location 凭据"
		description="密钥只在本次提交期间保留于内存，成功或关闭后立即清空。"
		:close-on-backdrop="!busy"
		@close="requestClose"
	>
		<view class="import-form">
				<view class="capacity-line" aria-live="polite">
					<text>凭据容量</text>
					<text class="numeric">{{ currentCount }} / 100</text>
				</view>

				<view class="field-group">
					<view class="field-heading">
						<label class="field-label" for="ip2location-key-input">API Key</label>
						<button
							v-if="filePickerAvailable"
							class="text-button"
							type="button"
							:disabled="busy"
							@click="selectTextFile"
						>
							读取 TXT
						</button>
					</view>
					<textarea
						id="ip2location-key-input"
						v-model="rawText"
						class="key-textarea"
						:maxlength="32768"
						:disabled="busy"
						:focus="focusField === 'keys'"
						placeholder="每行一个 API Key"
						aria-describedby="ip2location-key-help"
					/>
					<text id="ip2location-key-help" class="field-help">
						已识别 {{ parsed.apiKeys.length }} 条，已去除 {{ parsed.duplicateCount }} 条重复记录。
					</text>
					<text v-if="fileName" class="file-note">{{ fileName }} · {{ formattedFileSize }}</text>
					<text v-if="!filePickerAvailable" class="field-help">当前 Android 运行时请使用多行粘贴。</text>
				</view>

				<view class="field-grid">
					<view class="field-group">
						<text class="field-label">套餐类型</text>
						<picker :range="planLabels" :value="planIndex" :disabled="busy" @change="changePlan">
							<view class="select-control">{{ planLabels[planIndex] }}</view>
						</picker>
					</view>
					<view class="field-group">
						<label class="field-label" for="ip2location-quota">初始额度</label>
						<input
							id="ip2location-quota"
							v-model="quotaText"
							class="text-control numeric"
							type="number"
							inputmode="numeric"
							:disabled="busy"
							:focus="focusField === 'quota'"
							placeholder="50000"
						/>
					</view>
				</view>

				<view class="field-group">
					<text class="field-label">有效期</text>
					<view class="validity-control">{{ planValidityLabel }}</view>
					<text class="field-help">截止时间由后端按套餐和服务端当前时间计算，客户端不能修改。</text>
				</view>

				<button class="advanced-toggle" type="button" :aria-expanded="String(showAdvanced)" @click="showAdvanced = !showAdvanced">
					高级选项 · {{ mode === 'CREATE_ONLY' ? '仅新增' : '覆盖已有配置' }}
				</button>
				<view v-if="showAdvanced" class="advanced-panel">
					<radio-group @change="mode = $event.detail.value">
						<label class="radio-option">
							<radio value="CREATE_ONLY" :checked="mode === 'CREATE_ONLY'" color="#39d6d2" />
							<text>仅新增，不修改已存在凭据</text>
						</label>
						<label class="radio-option">
							<radio value="UPSERT" :checked="mode === 'UPSERT'" color="#39d6d2" />
							<text>覆盖已有凭据的额度和有效期</text>
						</label>
					</radio-group>
				</view>

				<view v-if="visibleError" class="inline-error" role="alert">{{ visibleError }}</view>
		</view>
		<template #footer>
			<view class="import-footer">
				<view class="submission-summary" aria-live="polite">
					<text>本次提交</text>
					<text class="numeric">{{ parsed.apiKeys.length }} 条</text>
				</view>
				<button class="submit-button" type="button" :loading="busy" :disabled="busy" @click="submit">
					加密导入
				</button>
			</view>
		</template>
	</admin-material-sheet>
</template>

<script>
import AdminMaterialSheet from './admin-material-sheet.vue'
import {
	parseIp2LocationKeyText,
	validateIp2LocationImportCapacity
} from '@/common/admin/ip2location-key-presenter.js'
import {
	chooseIp2LocationKeyTextFile,
	isIp2LocationKeyFilePickerAvailable
} from '@/common/admin/ip2location-key-file-picker.js'

const plans = [
	{ value: 'FREE', label: 'Free', validityLabel: '导入后有效 7 天' },
	{ value: 'STARTER', label: 'Starter', validityLabel: '导入后有效 1 个月' },
	{ value: 'PLUS', label: 'Plus', validityLabel: '导入后有效 1 个月' },
	{ value: 'SECURITY', label: 'Security', validityLabel: '导入后有效 1 个月' },
	{ value: 'SECURITY_TRIAL', label: 'Security Trial', validityLabel: '导入后有效 1 个月' },
	{ value: 'CUSTOM', label: 'Custom', validityLabel: '导入后有效 1 个月' }
]

export default {
	name: 'Ip2LocationKeyImportSheet',
	components: { AdminMaterialSheet },
	props: {
		open: { type: Boolean, default: false },
		currentCount: { type: Number, default: 0 },
		busy: { type: Boolean, default: false },
		serverError: { type: String, default: '' }
	},
	emits: ['close', 'submit'],
	data() {
		return {
			rawText: '',
			planIndex: 0,
			quotaText: '50000',
			mode: 'CREATE_ONLY',
			showAdvanced: false,
			fileName: '',
			fileSize: 0,
			filePickerAvailable: false,
			localError: '',
			focusField: ''
		}
	},
	computed: {
		planLabels() { return plans.map(plan => plan.label) },
		planValidityLabel() { return plans[this.planIndex].validityLabel },
		parsed() { return parseIp2LocationKeyText(this.rawText) },
		capacity() { return validateIp2LocationImportCapacity(this.currentCount, this.parsed.apiKeys.length) },
		visibleError() { return this.localError || this.serverError },
		formattedFileSize() {
			return this.fileSize < 1024
				? `${this.fileSize} B`
				: `${Math.ceil(this.fileSize / 1024)} KB`
		}
	},
	watch: {
		open(value) {
			if (value) {
				this.filePickerAvailable = isIp2LocationKeyFilePickerAvailable()
				this.focus('keys')
			}
		}
	},
	methods: {
		focus(field) {
			this.focusField = ''
			this.$nextTick(() => { this.focusField = field })
		},
		changePlan(event) {
			this.planIndex = Number(event.detail.value) || 0
			this.localError = ''
		},
		async selectTextFile() {
			this.localError = ''
			try {
				const selected = await chooseIp2LocationKeyTextFile()
				this.rawText = selected.text
				this.fileName = selected.name
				this.fileSize = selected.size
			} catch (error) {
				if (error.code !== 'FILE_PICKER_CANCELLED') this.localError = error.message
			}
		},
		resetSensitiveInput() {
			this.rawText = ''
			this.fileName = ''
			this.fileSize = 0
			this.localError = ''
			this.focusField = ''
		},
		requestClose() {
			if (this.busy) return
			if (!this.rawText.trim()) {
				this.resetSensitiveInput()
				this.$emit('close')
				return
			}
			uni.showModal({
				title: '放弃未提交凭据？',
				content: '关闭后，当前输入的 API Key 会从页面内存中清除。',
				confirmText: '清除并关闭',
				cancelText: '继续编辑',
				success: result => {
					if (!result.confirm) return
					this.resetSensitiveInput()
					this.$emit('close')
				}
			})
		},
		submit() {
			this.localError = ''
			if (!this.parsed.apiKeys.length) {
				this.localError = '请至少输入一个 API Key。'
				this.focus('keys')
				return
			}
			const invalidKey = this.parsed.apiKeys.find(key => key.length < 8 || key.length > 256)
			if (invalidKey) {
				this.localError = '每个 API Key 必须为 8 至 256 个字符。'
				this.focus('keys')
				return
			}
			if (!this.capacity.allowed) {
				this.localError = this.capacity.remainingCapacity === 0
					? '凭据池已达到 100 条上限。'
					: `本次超过剩余容量 ${this.capacity.overflowCount} 条。`
				this.focus('keys')
				return
			}
			const initialQuota = Number(this.quotaText)
			if (!Number.isSafeInteger(initialQuota) || initialQuota < 1 || initialQuota > 10_000_000) {
				this.localError = '初始额度必须是 1 至 10,000,000 的整数。'
				this.focus('quota')
				return
			}
			this.$emit('submit', {
				planType: plans[this.planIndex].value,
				initialQuota,
				mode: this.mode,
				apiKeys: [...this.parsed.apiKeys]
			})
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.import-form { padding-right: 4rpx; }
.text-button, .advanced-toggle { min-height: 76rpx; border: 0; background: transparent; color: $app-green; font-size: 24rpx; }
.capacity-line, .submission-summary { display: flex; align-items: center; justify-content: space-between; color: $app-muted; font-size: 24rpx; }
.capacity-line { padding-bottom: 24rpx; border-bottom: 1px solid rgba($app-border, .7); }
.capacity-line .numeric, .submission-summary .numeric { color: $app-text; font-weight: 720; }
.field-group { margin-top: 28rpx; }
.field-heading { display: flex; align-items: center; justify-content: space-between; gap: 20rpx; }
.field-label { display: block; margin-bottom: 12rpx; color: $app-text; font-size: 24rpx; font-weight: 680; }
.key-textarea, .text-control, .select-control, .validity-control { width: 100%; box-sizing: border-box; border: 1px solid $app-border; border-radius: $app-radius-control; background: $app-raised; color: $app-text; }
.key-textarea { min-height: 250rpx; padding: 22rpx; font: 24rpx/1.55 ui-monospace, SFMono-Regular, Consolas, monospace; }
.text-control, .select-control, .validity-control { min-height: 84rpx; padding: 0 22rpx; display: flex; align-items: center; font-size: 25rpx; }
.validity-control { color: $app-green; font-weight: 720; }
.field-help, .file-note { display: block; margin-top: 10rpx; color: $app-muted; font-size: 24rpx; line-height: 1.5; }
.file-note { color: $app-teal; }
.field-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18rpx; }
.advanced-toggle { width: 100%; margin-top: 24rpx; padding: 0; text-align: left; justify-content: flex-start; }
.advanced-panel { padding: 18rpx 20rpx; border-radius: $app-radius-control; background: rgba($app-raised, .62); }
.radio-option { min-height: 76rpx; display: flex; align-items: center; gap: 14rpx; color: $app-text; font-size: 24rpx; }
.inline-error { margin: 24rpx 0; padding: 18rpx 20rpx; border-radius: $app-radius-control; background: rgba($app-danger, .1); color: $app-danger-text; font-size: 24rpx; line-height: 1.5; }
.import-footer { width: 100%; }
.submit-button { width: 100%; min-height: 88rpx; margin-top: 16rpx; border: 0; border-radius: $app-radius-control; background: $app-lime; color: #171306; font-size: 27rpx; font-weight: 780; }
.numeric { font-variant-numeric: tabular-nums; }
button::after { border: 0; }
button:focus-visible, .key-textarea:focus, .text-control:focus { outline: 2px solid $app-focus; outline-offset: 2px; }

@media (max-width: 720px) {
	.field-grid { grid-template-columns: 1fr; gap: 0; }
	.text-button, .advanced-toggle, .submit-button { min-height: 96rpx; }
}

@media (prefers-reduced-motion: reduce) {
	.advanced-toggle { transition: opacity 80ms linear; }
}
</style>
