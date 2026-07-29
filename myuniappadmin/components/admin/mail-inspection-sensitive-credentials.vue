<template>
	<view class="sensitive-credential-panel" role="region" aria-label="未注册原始凭证">
		<view class="sensitive-credential-heading">
			<view>
				<text class="sensitive-credential-kicker">SENSITIVE SESSION DATA</text>
				<text class="sensitive-credential-title">未注册原始凭证</text>
				<text class="sensitive-credential-copy">
					内容包含密码和 Refresh Token，仅能从当前管理员会话恢复。
				</text>
			</view>
			<text class="sensitive-credential-count">{{ recoverableCount }} 项可恢复</text>
		</view>

		<view v-if="maskedResults.length" class="masked-credential-preview" aria-label="未注册邮箱预览">
			<text
				v-for="result in maskedPreview"
				:key="result.lineNumber"
				class="masked-credential-line"
			>#{{ result.lineNumber }} · {{ maskEmail(result.email) }}</text>
			<text v-if="maskedResults.length > maskedPreview.length" class="masked-credential-more">
				另有 {{ maskedResults.length - maskedPreview.length }} 项未在预览中显示。
			</text>
		</view>

		<admin-feedback-banner
			v-if="!available"
			tone="warning"
			message="当前会话已不存在原始凭证，无法恢复四段格式。"
		/>

		<view v-if="!revealed" class="sensitive-credential-actions">
			<admin-action-button
				tone="orange"
				size="compact"
				:disabled="!available"
				@click="$emit('request-reveal')"
			>显示原始四段凭证</admin-action-button>
		</view>

		<view v-if="revealed" class="revealed-credential-region" aria-live="polite">
			<textarea
				class="revealed-credential-text"
				:value="credentialText"
				readonly
				:maxlength="-1"
				aria-label="未注册原始四段凭证"
			/>
			<view class="sensitive-credential-actions">
				<admin-action-button tone="neutral" size="compact" @click="$emit('request-hide')">
					隐藏
				</admin-action-button>
				<admin-action-button tone="orange" size="compact" @click="$emit('request-copy')">
					复制全部
				</admin-action-button>
				<admin-action-button
					tone="amber"
					size="compact"
					:loading="exporting"
					@click="$emit('request-export')"
				>导出 TXT</admin-action-button>
			</view>
		</view>

		<view v-if="exportedFile && exportedFile.path" class="exported-file-status">
			<text class="exported-file-label">导出路径</text>
			<text class="exported-file-path">{{ exportedFile.path }}</text>
			<view class="sensitive-credential-actions">
				<admin-action-button tone="teal" size="compact" @click="$emit('request-open-export')">
					打开文件
				</admin-action-button>
				<admin-action-button tone="danger" size="compact" @click="$emit('request-delete-export')">
					删除文件
				</admin-action-button>
			</view>
		</view>

		<text v-if="statusText" class="sensitive-operation-status" aria-live="polite">
			{{ statusText }}
		</text>
	</view>
</template>

<script>
import AdminActionButton from './admin-action-button.vue'
import AdminFeedbackBanner from './admin-feedback-banner.vue'
import { maskMailInspectionEmail } from '@/common/admin/mail-inspection-presenter.js'

export default {
	name: 'MailInspectionSensitiveCredentials',
	components: { AdminActionButton, AdminFeedbackBanner },
	emits: [
		'request-reveal',
		'request-hide',
		'request-copy',
		'request-export',
		'request-open-export',
		'request-delete-export'
	],
	props: {
		maskedResults: { type: Array, default: () => [] },
		recoverableCount: { type: Number, default: 0 },
		available: { type: Boolean, default: false },
		revealed: { type: Boolean, default: false },
		credentialLines: { type: Array, default: () => [] },
		exportedFile: { type: Object, default: null },
		exporting: { type: Boolean, default: false },
		statusText: { type: String, default: '' }
	},
	computed: {
		maskedPreview() {
			return this.maskedResults.slice(0, 20)
		},
		credentialText() {
			return this.revealed ? this.credentialLines.join('\n') : ''
		}
	},
	methods: {
		maskEmail(value) {
			return maskMailInspectionEmail(value)
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.sensitive-credential-panel {
	margin: 14rpx 0 4rpx;
	padding: 22rpx;
	border: 1px solid rgba($app-action-amber, .34);
	border-radius: $app-radius-panel;
	background: rgba($app-action-amber, .055);
}

.sensitive-credential-heading {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 20rpx;
}

.sensitive-credential-kicker,
.sensitive-credential-title,
.sensitive-credential-copy,
.sensitive-credential-count,
.masked-credential-line,
.masked-credential-more,
.exported-file-label,
.exported-file-path,
.sensitive-operation-status {
	display: block;
}

.sensitive-credential-kicker {
	color: $app-action-amber;
	font-size: 20rpx;
	font-weight: 800;
	letter-spacing: .08em;
}

.sensitive-credential-title {
	margin-top: 6rpx;
	color: $app-text;
	font-size: 28rpx;
	font-weight: 800;
}

.sensitive-credential-copy {
	margin-top: 7rpx;
	color: $app-muted;
	font-size: 23rpx;
	line-height: 1.5;
}

.sensitive-credential-count {
	flex: 0 0 auto;
	color: #ffe4aa;
	font-size: 24rpx;
	font-variant-numeric: tabular-nums;
}

.masked-credential-preview {
	max-height: 260rpx;
	margin-top: 18rpx;
	padding: 14rpx 16rpx;
	border: 1px solid rgba($app-border, .9);
	border-radius: $app-radius-control;
	overflow-y: auto;
	background: rgba($app-canvas, .68);
}

.masked-credential-line,
.masked-credential-more {
	color: $app-muted;
	font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
	font-size: 22rpx;
	line-height: 1.65;
}

.masked-credential-more {
	margin-top: 6rpx;
	color: $app-action-amber;
}

.sensitive-credential-actions {
	display: flex;
	flex-wrap: wrap;
	gap: 10rpx;
	margin-top: 16rpx;
}

.revealed-credential-region {
	margin-top: 18rpx;
}

.revealed-credential-text {
	width: 100%;
	height: 320rpx;
	padding: 16rpx;
	border: 1px solid rgba($app-danger, .34);
	border-radius: $app-radius-control;
	box-sizing: border-box;
	background: $app-canvas;
	color: $app-text;
	font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
	font-size: 22rpx;
	line-height: 1.55;
}

.exported-file-status {
	margin-top: 18rpx;
	padding: 16rpx;
	border: 1px solid rgba($app-action-teal, .24);
	border-radius: $app-radius-control;
	background: rgba($app-action-teal, .055);
}

.exported-file-label {
	color: $app-action-teal;
	font-size: 21rpx;
	font-weight: 750;
}

.exported-file-path {
	margin-top: 6rpx;
	color: $app-text;
	font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
	font-size: 22rpx;
	overflow-wrap: anywhere;
}

.sensitive-operation-status {
	margin-top: 12rpx;
	color: $app-muted;
	font-size: 22rpx;
}

@media (max-width: 767px) {
	.sensitive-credential-heading {
		flex-direction: column;
	}

	.sensitive-credential-actions {
		display: grid;
		grid-template-columns: 1fr;
	}

	.revealed-credential-text {
		height: 420rpx;
	}
}

@media (prefers-reduced-motion: reduce) {
	.sensitive-credential-panel {
		scroll-behavior: auto;
	}
}
</style>
