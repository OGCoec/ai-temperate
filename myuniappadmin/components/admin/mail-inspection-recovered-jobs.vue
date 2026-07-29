<template>
	<view v-if="jobs.length" class="recovery-panel" aria-live="polite">
		<view class="recovery-heading">
			<view>
				<text class="recovery-eyebrow">RESTART RECOVERY</text>
				<text class="recovery-title">{{ panelTitle }}</text>
			</view>
			<text class="recovery-count">{{ jobs.length }} 个任务</text>
		</view>
		<text class="recovery-warning" role="alert">
			应用曾重启。重启前已经完成的详细结果无法恢复；下列项目仍安全保存在 RabbitMQ 中，尚未重新处理。
		</text>

		<view v-for="job in jobs" :key="job.jobId" class="recovery-job">
			<view class="job-summary">
				<view>
					<text class="job-type">{{ typeLabel(job.inspectionType) }}</text>
					<text class="job-meta">{{ job.jobId }} · 剩余 {{ job.remainingCount }} · 并发 {{ job.businessConcurrency }}</text>
				</view>
				<text class="job-status">{{ recoveredStatusLabel(job.status) }}</text>
			</view>
			<view class="pending-list" aria-label="脱敏等待列表">
				<text v-for="item in job.pendingItems" :key="item.lineNumber">
					#{{ item.lineNumber }}&nbsp;&nbsp;{{ item.maskedEmail }}
				</text>
			</view>
			<view class="recovery-actions">
				<text v-if="job.lostResultCount" class="lost-copy">重启前 {{ job.lostResultCount }} 条详细结果已丢失</text>
				<admin-action-button
					tone="amber"
					:disabled="job.status !== 'AWAITING_ADMIN_RESUME' || busyJobId === job.jobId"
					:loading="busyJobId === job.jobId"
					@click="$emit('approve', job)"
				>批准继续处理全部剩余项</admin-action-button>
			</view>
		</view>
	</view>
</template>

<script>
import AdminActionButton from './admin-action-button.vue'

const TYPE_LABELS = Object.freeze({
	OPENAI_STATUS: 'OpenAI 邮件状态',
	KIRO_STATUS: 'Kiro 邮件状态',
	IP2LOCATION_REGISTRATION: 'IP2Location 注册邮件',
	IP2LOCATION_VERIFY_LINK: 'IP2Location 验证链接'
})

export default {
	name: 'MailInspectionRecoveredJobs',
	components: { AdminActionButton },
	emits: ['approve'],
	props: {
		jobs: { type: Array, default: () => [] },
		busyJobId: { type: String, default: '' }
	},
	computed: {
		panelTitle() {
			if (this.jobs.some(job => job.status === 'AWAITING_ADMIN_RESUME')) {
				return '等待管理员批准'
			}
			if (this.jobs.some(job => job.status === 'AWAITING_CLIENT_RESUBMISSION')) {
				return '等待原提交补齐'
			}
			return '恢复校验状态'
		}
	},
	methods: {
		recoveredStatusLabel(status) {
			if (status === 'RECOVERY_FAILED') return '恢复校验失败'
			if (status === 'AWAITING_CLIENT_RESUBMISSION') return '等待原提交补齐'
			return '已暂停'
		},
		typeLabel(type) {
			return TYPE_LABELS[type] || type
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.recovery-panel {
	margin-top: 22rpx;
	padding: 22rpx;
	border: 1px solid rgba($app-action-amber, .38);
	border-radius: $app-radius-panel;
	background: rgba($app-action-amber, .055);
}

.recovery-heading,
.job-summary,
.recovery-actions {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 18rpx;
}

.recovery-eyebrow,
.recovery-title,
.job-type,
.job-meta {
	display: block;
}

.recovery-eyebrow {
	color: $app-action-amber;
	font-size: 24rpx;
	font-weight: 780;
	letter-spacing: .1em;
}

.recovery-title {
	margin-top: 4rpx;
	font-size: 28rpx;
	font-weight: 800;
}

.recovery-count,
.job-status {
	color: $app-action-amber;
	font-size: 24rpx;
}

.recovery-warning {
	display: block;
	margin-top: 16rpx;
	color: #f6d79d;
	font-size: 24rpx;
	line-height: 1.55;
}

.recovery-job {
	margin-top: 16rpx;
	padding: 18rpx;
	border: 1px solid $app-border;
	border-radius: $app-radius-control;
	background: $app-surface;
}

.job-type {
	font-size: 24rpx;
	font-weight: 760;
}

.job-meta,
.lost-copy {
	margin-top: 5rpx;
	color: $app-muted;
	font-size: 24rpx;
	font-variant-numeric: tabular-nums;
}

.pending-list {
	max-height: 300rpx;
	margin-top: 14rpx;
	padding: 12rpx 14rpx;
	border: 1px solid rgba($app-teal, .14);
	border-radius: 9rpx;
	overflow-y: auto;
	background: #080d10;
	font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.pending-list text {
	display: block;
	padding: 5rpx 0;
	color: #b8c8cb;
	font-size: 24rpx;
}

.recovery-actions {
	margin-top: 16rpx;
}

@media (max-width: 767px) {
	.recovery-panel {
		padding: 18rpx;
	}

	.recovery-actions {
		align-items: stretch;
		flex-direction: column;
	}
}
</style>
