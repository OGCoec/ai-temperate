<template>
	<view class="progress-panel" aria-live="polite" aria-label="邮箱检查任务进度">
		<view class="progress-heading">
			<view>
				<text class="progress-eyebrow">JOB STATUS</text>
				<text class="progress-title">{{ stateLabel }}</text>
			</view>
			<text v-if="jobId" class="job-id">{{ jobId }}</text>
		</view>
		<text v-if="connectionLabel" class="connection-state">{{ connectionLabel }}</text>

		<view
			class="progress-track"
			role="progressbar"
			aria-label="邮箱检查任务进度"
			:aria-valuemin="0"
			:aria-valuemax="100"
			:aria-valuenow="progressPercent"
		>
			<view class="progress-value" :style="{ transform: `scaleX(${progressScale})` }" />
		</view>

		<view class="progress-counts">
			<view>
				<text class="count-value">{{ processedCount }}</text>
				<text class="count-label">已处理</text>
			</view>
			<view>
				<text class="count-value">{{ runningCount }}</text>
				<text class="count-label">运行中</text>
			</view>
			<view>
				<text class="count-value">{{ queuedCount }}</text>
				<text class="count-label">排队中</text>
			</view>
			<view>
				<text class="count-value">{{ requestedCount }}</text>
				<text class="count-label">总数</text>
			</view>
		</view>

		<text v-if="message" class="progress-message" :class="{ danger: state === 'FAILED' }" role="alert">
			{{ message }}
		</text>
		<text v-else class="progress-copy">{{ stateCopy }}</text>
	</view>
</template>

<script>
const STATE_LABELS = Object.freeze({
	IDLE: '等待创建任务',
	VALIDATING: '输入需要修正',
	CREATING: '正在创建任务',
	DISPATCHING: '正在持久化提交',
	SUBMISSION_UNKNOWN: '等待确认原提交',
	SERVICE_UNAVAILABLE: '该类型暂不可用',
	AWAITING_CLIENT_RESUBMISSION: '等待补齐提交',
	QUEUED: '任务已排队',
	RUNNING: '正在读取邮箱',
	AWAITING_ADMIN_RESUME: '等待管理员批准',
	RECOVERY_FAILED: '恢复校验失败',
	ABANDONED: '残缺任务已放弃',
	COMPLETED: '检查已完成',
	FAILED: '任务未完成',
	EXPIRED: '任务已失效'
})

const CONNECTION_LABELS = Object.freeze({
	CONNECTING: '正在连接实时通道',
	SYNCING: '正在同步权威快照',
	STREAMING: '实时通道已连接',
	RECONNECTING: '实时通道有限重连中',
	COMPLETED: '实时通道已完成',
	FAILED: '实时通道已中断',
	EXPIRED: '任务已过期'
})

export default {
	name: 'MailInspectionJobProgress',
	props: {
		state: { type: String, default: 'IDLE' },
		job: { type: Object, default: null },
		jobId: { type: String, default: '' },
		message: { type: String, default: '' },
		connectionState: { type: String, default: '' }
	},
	computed: {
		stateLabel() {
			return STATE_LABELS[this.state] || this.state
		},
		connectionLabel() {
			return CONNECTION_LABELS[this.connectionState] || ''
		},
		requestedCount() {
			return Number(this.job?.requestedCount) || 0
		},
		processedCount() {
			return Number(this.job?.processedCount)
				|| (this.state === 'COMPLETED' ? this.requestedCount : 0)
		},
		runningCount() {
			return Number(this.job?.runningCount) || 0
		},
		queuedCount() {
			return Number(this.job?.queuedCount) || 0
		},
		progressPercent() {
			if (!this.requestedCount) return 0
			return Math.min(100, Math.round((this.processedCount / this.requestedCount) * 100))
		},
		progressScale() {
			return this.progressPercent / 100
		},
		stateCopy() {
			if (this.state === 'IDLE') return '输入 Microsoft 邮箱凭证后创建检查任务，单次最多 10,000 行且总量不超过 1 MiB。'
			if (this.state === 'COMPLETED') return '结果已按输入行号排序；原始凭证不会出现在普通结果中。'
			if (this.state === 'RUNNING' || this.state === 'QUEUED') return '后端正在执行有限重试，进度通过 SSE 单向推送。'
			if (this.state === 'DISPATCHING') {
				const confirmed = Number(this.job?.confirmedSubmissionChunkCount) || 0
				const total = Number(this.job?.submissionChunkCount) || 0
				return `正在持久化提交 ${confirmed}/${total}。`
			}
			if (this.state === 'SUBMISSION_UNKNOWN') return '使用原提交编号继续确认不会创建第二个任务。'
			if (this.state === 'SERVICE_UNAVAILABLE') return '恢复成功后使用原提交编号重试；当前请求没有创建后台任务。'
			if (this.state === 'AWAITING_CLIENT_RESUBMISSION') return '部分分块尚未确认，必须使用原提交编号和原凭证补齐。'
			if (this.state === 'AWAITING_ADMIN_RESUME') return '剩余凭证仍在 RabbitMQ Ready 中，批准前不会执行 OAuth 或 IMAP。'
			if (this.state === 'RECOVERY_FAILED') return '恢复消息未通过安全校验，消费者保持停止，请检查 RabbitMQ 死信与安全指标。'
			if (this.state === 'ABANDONED') return '残缺提交超过六小时，Rabbit 消息已安全清理。'
			return '任务状态变化会在这里持续更新。'
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.progress-panel {
	padding: 24rpx;
	@include admin-solid-panel;
}

.progress-heading {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 18rpx;
}

.progress-eyebrow,
.progress-title {
	display: block;
}

.progress-eyebrow { color: $app-action-teal; font-size: 24rpx; font-weight: 720; }

.progress-title {
	margin-top: 6rpx;
	font-size: 28rpx;
	font-weight: 790;
}

.job-id {
	padding: 7rpx 10rpx;
	border: 1px solid rgba($app-teal, .18);
	border-radius: 8rpx;
	color: $app-muted;
	font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
	font-size: 24rpx;
}

.progress-track {
	height: 10rpx;
	margin-top: 24rpx;
	border-radius: 999rpx;
	overflow: hidden;
	background: $app-raised;
}

.progress-value {
	width: 100%;
	height: 100%;
	border-radius: inherit;
	background: $app-action-teal;
	transform-origin: left center;
	transition: transform $app-motion-state $app-ease-out;
}

.progress-counts {
	margin-top: 24rpx;
	display: grid;
	grid-template-columns: repeat(4, minmax(0, 1fr));
	gap: 10rpx;
}

.progress-counts view {
	min-width: 0;
	padding: 14rpx;
	border: 1px solid rgba($app-teal, .13);
	border-radius: 10rpx;
	background: #0b1115;
}

.count-value,
.count-label {
	display: block;
}

.count-value {
	color: $app-text;
	font-size: 28rpx;
	font-weight: 800;
	font-variant-numeric: tabular-nums;
}

.count-label {
	margin-top: 4rpx;
	color: $app-muted;
	font-size: 24rpx;
}

.progress-copy,
.progress-message,
.connection-state {
	display: block;
	margin-top: 20rpx;
	color: $app-muted;
	font-size: 24rpx;
	line-height: 1.5;
}

.connection-state {
	color: $app-action-teal;
	font-size: 22rpx;
}

.progress-message {
	padding: 12rpx 14rpx;
	border: 1px solid rgba($app-warning, .32);
	border-radius: 9rpx;
	background: rgba($app-warning, .08);
	color: #f8d89a;
}

.progress-message.danger {
	border-color: rgba($app-danger, .4);
	background: rgba($app-danger, .1);
	color: $app-danger-text;
}

@media (max-width: 767px) {
	.progress-panel {
		padding: 20rpx;
	}

	.job-id {
		display: none;
	}
}

@media (prefers-reduced-motion: reduce) {
	.progress-value {
		transition: none;
	}
}
</style>
