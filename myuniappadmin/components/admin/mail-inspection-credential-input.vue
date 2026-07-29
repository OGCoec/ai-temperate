<template>
	<view class="credential-panel" aria-label="Microsoft 邮箱凭证输入">
		<view class="panel-heading">
			<view>
				<text class="panel-title">批量邮箱凭证</text>
				<text class="panel-copy">每行使用 邮箱----密码----clientId----refreshToken，空白行自动忽略。</text>
			</view>
			<admin-action-button
				v-if="collapsed"
				tone="neutral"
				size="compact"
				@click="$emit('toggle-collapsed')"
			>展开编辑</admin-action-button>
		</view>

		<view v-if="collapsed" class="collapsed-summary">
			<text class="summary-number">{{ analysis.lineCount }}</text>
			<text>行凭证已保留在当前管理员会话中，结果不会回显密码或 Token。</text>
		</view>

		<template v-else>
			<view class="format-example" aria-label="凭证格式示例">
				<text>user@example.com----&lt;password&gt;----00000000-0000-0000-0000-000000000000----&lt;refresh-token&gt;</text>
			</view>
			<textarea
				class="credential-textarea"
				:value="draftText"
				:focus="focusInvalid"
				:disabled="busy"
				maxlength="-1"
				placeholder="在此粘贴任意行凭证（总量不超过 1 MiB）"
				aria-label="邮箱凭证多行输入"
				:aria-invalid="analysis.errors.length ? 'true' : 'false'"
				@input="$emit('update:draft-text', $event.detail.value)"
			/>

			<view class="input-meta" aria-live="polite">
				<text>{{ analysis.lineCount }} 行</text>
				<text>{{ byteLabel }}/1 MiB</text>
				<text :class="{ invalid: analysis.errors.length }">{{ analysis.errors.length }} 个问题</text>
			</view>

			<view v-if="analysis.errors.length" class="input-errors" role="alert">
				<text v-for="error in visibleErrors" :key="`${error.lineNumber}-${error.code}`">
					{{ error.message }}
				</text>
				<text v-if="analysis.errors.length > visibleErrors.length">
					另有 {{ analysis.errors.length - visibleErrors.length }} 个问题，请修正后再提交。
				</text>
			</view>

			<button
				class="advanced-settings-toggle"
				type="button"
				:aria-expanded="advancedOpen ? 'true' : 'false'"
				aria-controls="mail-inspection-concurrency"
				@click="advancedOpen = !advancedOpen"
			>
				<view>
					<text class="advanced-settings-title">高级设置</text>
					<text class="advanced-settings-summary">业务并发 {{ businessConcurrency }} · 范围 1–64</text>
				</view>
				<text class="advanced-settings-symbol" aria-hidden="true">{{ advancedOpen ? '−' : '+' }}</text>
			</button>

			<view v-if="advancedOpen" id="mail-inspection-concurrency" class="concurrency-field">
				<view class="concurrency-copy">
					<text class="concurrency-title">业务并发数</text>
					<text>控制 RabbitMQ 同时处理的凭证数量；输入行数不设上限，总量不超过 1 MiB。</text>
				</view>
				<input
					class="concurrency-input"
					type="number"
					inputmode="numeric"
					:value="businessConcurrency"
					:disabled="busy || concurrencyLocked"
					aria-label="业务并发数，范围一到六十四"
					@input="handleBusinessConcurrencyInput"
				/>
				<view class="concurrency-presets" role="group" aria-label="业务并发快捷值">
					<button
						v-for="value in concurrencyPresets"
						:key="value"
						class="concurrency-preset-button"
						type="button"
						:class="{ active: Number(businessConcurrency) === value }"
						:disabled="busy || concurrencyLocked"
						:aria-pressed="Number(businessConcurrency) === value"
						:aria-disabled="busy || concurrencyLocked"
						@tap="selectBusinessConcurrency(value)"
						@keydown.enter.prevent="selectBusinessConcurrency(value)"
						@keydown.space.prevent="selectBusinessConcurrency(value)"
					>{{ value }}</button>
				</view>
			</view>

			<view class="input-actions">
				<admin-action-button
					v-if="filePickerAvailable"
					tone="neutral"
					:disabled="busy"
					@click="$emit('choose-file')"
				>导入 TXT</admin-action-button>
				<text v-else class="picker-fallback">当前运行环境请使用多行粘贴。</text>
				<admin-action-button tone="danger" :disabled="busy || !draftText" @click="$emit('clear')">
					清除本次凭证
				</admin-action-button>
				<admin-action-button
					class="submit-button"
					tone="amber"
					:loading="busy"
					:disabled="!analysis.valid"
					@click="$emit('submit')"
				>{{ busy ? '正在创建任务' : '创建检查任务' }}</admin-action-button>
			</view>
		</template>
	</view>
</template>

<script>
import AdminActionButton from './admin-action-button.vue'
import { formatCredentialByteCount } from '@/common/admin/mail-inspection-credential-parser.js'

export default {
	name: 'MailInspectionCredentialInput',
	components: { AdminActionButton },
	emits: [
		'update:draft-text',
		'update:business-concurrency',
		'choose-file',
		'clear',
		'submit',
		'toggle-collapsed'
	],
	props: {
		draftText: { type: String, default: '' },
		analysis: {
			type: Object,
			required: true
		},
		collapsed: { type: Boolean, default: false },
		busy: { type: Boolean, default: false },
		filePickerAvailable: { type: Boolean, default: false },
		focusInvalid: { type: Boolean, default: false },
		businessConcurrency: { type: Number, default: 4 },
		concurrencyLocked: { type: Boolean, default: false }
	},
	data() {
		return {
			concurrencyPresets: [1, 4, 8, 16, 32, 64],
			advancedOpen: false
		}
	},
	computed: {
		byteLabel() {
			return formatCredentialByteCount(this.analysis.byteCount)
		},
		visibleErrors() {
			return this.analysis.errors.slice(0, 6)
		}
	},
	methods: {
		handleBusinessConcurrencyInput(event) {
			this.selectBusinessConcurrency(event?.detail?.value)
		},
		selectBusinessConcurrency(value) {
			if (this.busy || this.concurrencyLocked) return
			const concurrency = Number(value)
			if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > 64) return
			this.$emit('update:business-concurrency', concurrency)
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.credential-panel {
	padding: 24rpx;
	@include admin-solid-panel;
}

.panel-heading {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 20rpx;
}

.panel-title,
.panel-copy {
	display: block;
}

.panel-title {
	font-size: 28rpx;
	font-weight: 780;
}

.panel-copy {
	margin-top: 7rpx;
	color: $app-muted;
	font-size: 24rpx;
	line-height: 1.5;
}

.format-example {
	margin-top: 20rpx;
	padding: 14rpx 16rpx;
	border: 1px solid rgba($app-teal, .16);
	border-radius: 10rpx;
	overflow-wrap: anywhere;
	background: #0b1115;
	color: #aec7cc;
	font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
	font-size: 24rpx;
	line-height: 1.45;
}

.credential-textarea {
	width: 100%;
	height: 390rpx;
	margin-top: 14rpx;
	padding: 18rpx;
	border: 1px solid $app-border;
	border-radius: $app-radius-control;
	box-sizing: border-box;
	background: #080d10;
	color: $app-text;
	font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
	font-size: 24rpx;
	line-height: 1.55;
	transition: border-color 180ms ease, background-color 180ms ease;
}

.credential-textarea:focus {
	border-color: rgba($app-action-teal, .7);
	background: #0a1115;
}

.input-meta {
	min-height: 58rpx;
	display: flex;
	align-items: center;
	gap: 22rpx;
	color: $app-muted;
	font-size: 24rpx;
	font-variant-numeric: tabular-nums;
}

.input-meta .invalid {
	color: $app-danger-text;
}

.input-errors {
	padding: 14rpx 16rpx;
	border: 1px solid rgba($app-danger, .42);
	border-radius: $app-radius-control;
	display: grid;
	gap: 6rpx;
	background: rgba($app-danger, .1);
	color: $app-danger-text;
	font-size: 24rpx;
	line-height: 1.45;
}

.input-actions {
	margin-top: 16rpx;
	display: flex;
	align-items: center;
	gap: 12rpx;
}

.advanced-settings-toggle {
	width: 100%;
	min-height: 82rpx;
	margin: 18rpx 0 0;
	padding: 14rpx 16rpx;
	border: 0;
	border-radius: $app-radius-control;
	display: flex;
	align-items: center;
	justify-content: space-between;
	background: rgba($app-muted, .07);
	color: $app-text;
	text-align: left;
}

.advanced-settings-toggle::after {
	border: 0;
}

.advanced-settings-title,
.advanced-settings-summary {
	display: block;
}

.advanced-settings-title {
	font-size: 25rpx;
	font-weight: 720;
}

.advanced-settings-summary {
	margin-top: 4rpx;
	color: $app-muted;
	font-size: 24rpx;
}

.advanced-settings-symbol {
	width: 48rpx;
	height: 48rpx;
	border-radius: 50%;
	display: flex;
	align-items: center;
	justify-content: center;
	background: rgba($app-green, .1);
	color: $app-green;
	font-size: 32rpx;
}

.advanced-settings-toggle:focus-visible {
	@include admin-focus-ring;
}

.concurrency-field {
	margin-top: 18rpx;
	padding: 16rpx;
	border: 1px solid $app-border;
	border-radius: $app-radius-control;
	display: grid;
	grid-template-columns: minmax(0, 1fr) 116rpx;
	gap: 14rpx 18rpx;
	align-items: center;
	background: #0b1115;
}

.concurrency-copy text {
	display: block;
	color: $app-muted;
	font-size: 24rpx;
	line-height: 1.45;
}

.concurrency-copy .concurrency-title {
	margin-bottom: 4rpx;
	color: $app-text;
	font-size: 25rpx;
	font-weight: 760;
}

.concurrency-input {
	min-height: 76rpx;
	padding: 0 14rpx;
	border: 1px solid $app-border;
	border-radius: 10rpx;
	box-sizing: border-box;
	background: #080d10;
	color: $app-text;
	font-size: 24rpx;
	font-variant-numeric: tabular-nums;
	text-align: center;
}

.concurrency-presets {
	grid-column: 1 / -1;
	display: grid;
	grid-template-columns: repeat(6, minmax(0, 1fr));
	gap: 8rpx;
}

.concurrency-preset-button {
	min-height: 88rpx;
	margin: 0;
	padding: 0;
	border: 1px solid $app-border;
	border-radius: 9rpx;
	box-sizing: border-box;
	display: flex;
	align-items: center;
	justify-content: center;
	background: transparent;
	color: $app-muted;
	font-size: 24rpx;
	font-variant-numeric: tabular-nums;
	line-height: 1;
	cursor: pointer;
	transition: border-color 160ms ease, background-color 160ms ease, color 160ms ease;
}

.concurrency-preset-button.active {
	border-color: rgba($app-action-teal, .55);
	background: rgba($app-action-teal, .12);
	color: $app-text;
}

.concurrency-preset-button:active:not(:disabled) {
	border-color: rgba($app-action-teal, .78);
	background: rgba($app-action-teal, .18);
}

.concurrency-preset-button:focus-visible {
	outline: 2px solid $app-focus;
	outline-offset: 2px;
}

.concurrency-preset-button:disabled {
	opacity: .46;
	cursor: not-allowed;
}

.concurrency-preset-button::after {
	border: 0;
}

@media (hover: hover) {
	.concurrency-preset-button:hover:not(:disabled) {
		border-color: rgba($app-action-teal, .48);
		color: $app-text;
	}
}

.picker-fallback {
	margin-right: auto;
	color: $app-muted;
	font-size: 24rpx;
}

.submit-button {
	margin-left: auto;
}

.collapsed-summary {
	min-height: 110rpx;
	margin-top: 18rpx;
	padding: 18rpx;
	border: 1px solid rgba($app-action-teal, .18);
	border-radius: $app-radius-control;
	display: flex;
	align-items: center;
	gap: 18rpx;
	background: #0b1115;
	color: $app-muted;
	font-size: 24rpx;
	line-height: 1.5;
}

.summary-number {
	color: $app-action-amber;
	font-size: 40rpx;
	font-weight: 800;
	font-variant-numeric: tabular-nums;
}

textarea:focus-visible {
	outline: 2px solid $app-focus;
	outline-offset: 2px;
}

@media (max-width: 767px) {
	.credential-panel {
		padding: 20rpx;
	}

	.panel-heading {
		align-items: center;
	}

	.panel-copy {
		display: none;
	}

	.credential-textarea {
		height: 440rpx;
		font-size: 24rpx;
	}

	.input-meta {
		justify-content: space-between;
		gap: 8rpx;
	}

	.input-actions {
		padding-bottom: calc(6rpx + env(safe-area-inset-bottom));
		flex-wrap: wrap;
	}

	.concurrency-field {
		grid-template-columns: minmax(0, 1fr) 104rpx;
	}

	.concurrency-presets {
		grid-template-columns: repeat(3, minmax(0, 1fr));
	}

	.input-actions .admin-action-button {
		flex: 1;
	}

	.submit-button {
		flex-basis: 100% !important;
		margin-left: 0;
	}

	.picker-fallback {
		flex-basis: 100%;
	}
}

@media (prefers-reduced-motion: reduce) {
	.credential-textarea,
	.concurrency-preset-button,
	.advanced-settings-toggle {
		transition: none;
	}
}
</style>
