<template>
	<admin-page-shell
		:current-path="currentRoutePath"
		kicker="邮件证据"
		:title="title"
		:description="description"
		:busy="creating || Boolean(resumeBusyJobId)"
		@navigate="navigateProtected"
	>
		<template #meta>
			<view class="stage-status">
				<view class="stage-status-dot" aria-hidden="true" />
				<text>{{ workflowStageLabel }}</text>
			</view>
		</template>
		<template #actions>
			<admin-action-button tone="neutral" size="compact" @click="goBack">
				返回控制台
			</admin-action-button>
			<view class="more-actions">
				<admin-action-button
					tone="neutral"
					size="compact"
					:aria-expanded="moreMenuOpen ? 'true' : 'false'"
					@click="moreMenuOpen = !moreMenuOpen"
				>更多</admin-action-button>
				<view v-if="moreMenuOpen" class="more-menu" role="menu">
					<button type="button" role="menuitem" @click="openClearAll">
						清除全部检查数据
					</button>
				</view>
			</view>
		</template>
		<view class="inspection-shell">
			<mail-inspection-business-tabs :active="activeBusiness" />

			<view v-if="showIp2Modes" class="mode-tabs" role="group" aria-label="IP2Location 检查模式">
				<button
					v-for="option in ip2Modes"
					:key="option.value"
					type="button"
					:class="{ active: ip2Mode === option.value }"
					:aria-pressed="ip2Mode === option.value"
					@click="$emit('update:ip2-mode', option.value)"
				>{{ option.label }}</button>
			</view>

			<view class="workflow-stages" aria-label="检查流程">
				<view
					v-for="(stage, index) in workflowStages"
					:key="stage.value"
					class="workflow-stage"
					:class="{ active: workflowStageIndex >= index, current: workflowStage === stage.value }"
				>
					<view class="workflow-stage-mark" aria-hidden="true">{{ workflowStageIndex > index ? '✓' : index + 1 }}</view>
					<text>{{ stage.label }}</text>
				</view>
			</view>

			<admin-feedback-banner
				v-if="bannerMessage"
				:tone="bannerType === 'error' ? 'danger' : (bannerType === 'success' ? 'success' : 'info')"
				:message="bannerMessage"
				:dismissible="true"
				@dismiss="clearBanner"
			/>

			<admin-feedback-banner
				v-if="persistenceMode === 'MEMORY'"
				tone="warning"
				message="安全会话存储当前不可用；凭证只保留在内存中，刷新或重启后无法恢复。"
			/>

			<mail-inspection-recovered-jobs
				:jobs="visibleRecoveredJobs"
				:busy-job-id="resumeBusyJobId"
				@approve="approveRecoveredJob"
			/>

			<view v-if="submissionNeedsConfirmation" class="submission-recovery" role="alert">
				<view>
					<text class="submission-recovery-title">{{ submissionRecoveryTitle }}</text>
					<text class="submission-recovery-copy">{{ viewState.message }}</text>
				</view>
				<admin-action-button
					tone="primary"
					size="compact"
					:loading="creating"
					@click="continueSubmission"
				>
					继续确认原提交
				</admin-action-button>
			</view>

			<view class="workspace-grid" :class="`stage-${workflowStage.toLowerCase()}`">
				<mail-inspection-credential-input
					:draft-text="viewState.draftText"
					:analysis="viewState.analysis"
					:collapsed="inputCollapsed"
					:busy="creating"
					:file-picker-available="filePickerAvailable"
					:focus-invalid="focusInvalid"
					:business-concurrency="viewState.businessConcurrency"
					:concurrency-locked="submissionLocked"
					@update:draft-text="updateDraft"
					@update:business-concurrency="updateBusinessConcurrency"
					@choose-file="chooseFile"
					@clear="confirmClear"
					@submit="submit"
					@toggle-collapsed="inputCollapsed = false"
				/>
				<mail-inspection-job-progress
					v-if="workflowStage !== 'PREPARE'"
					:state="viewState.state"
					:job="viewState.job"
					:job-id="viewState.jobId"
					:message="viewState.message"
				/>
			</view>

			<view v-if="presentedResults.length" class="result-summary" aria-label="检查结果摘要">
				<view class="summary-intro">
					<text class="summary-title">结果摘要</text>
					<text class="summary-copy">按当前平台的邮件证据语义统计，不代表第三方账号实时状态。</text>
				</view>
				<view class="summary-metrics">
					<view v-for="item in resultSummaryItems" :key="item.value">
						<text class="summary-value" :class="item.tone">{{ item.count }}</text>
						<text>{{ item.label }}</text>
					</view>
				</view>
			</view>

			<mail-inspection-result-list
				:results="presentedResults"
				:retry-count="retryLines.length"
				:inspection-type="inspectionType"
				:active-business-category="activeBusinessCategory"
				@retry="confirmRetry"
				@copy-retry="confirmCopyRetry"
				@copy-value="copyValue"
				@select-business-category="selectBusinessCategory"
				@request-unregistered-reveal="requestUnregisteredPanel"
			>
				<template #sensitive-business-panel>
					<mail-inspection-sensitive-credentials
						v-if="sensitiveCredentialPanelVisible"
						:masked-results="unregisteredResults"
						:recoverable-count="unregisteredCredentialLines.length"
						:available="unregisteredCredentialLines.length > 0"
						:revealed="unregisteredCredentialsRevealed"
						:credential-lines="visibleUnregisteredCredentialLines"
						:exported-file="exportedCredentialFile"
						:exporting="credentialExportBusy"
						:status-text="sensitiveCredentialStatus"
						@request-reveal="confirmRevealUnregistered"
						@request-hide="hideUnregisteredCredentials"
						@request-copy="confirmCopyUnregistered"
						@request-export="confirmExportUnregistered"
						@request-open-export="openExportedCredentialFile"
						@request-delete-export="confirmDeleteExportedCredentialFile"
					/>
				</template>
			</mail-inspection-result-list>
			<text class="clipboard-status" aria-live="polite">{{ clipboardStatus }}</text>
		</view>
	</admin-page-shell>
</template>

<script>
import AdminActionButton from './admin-action-button.vue'
import MailInspectionBusinessTabs from './mail-inspection-business-tabs.vue'
import MailInspectionCredentialInput from './mail-inspection-credential-input.vue'
import MailInspectionJobProgress from './mail-inspection-job-progress.vue'
import MailInspectionResultList from './mail-inspection-result-list.vue'
import MailInspectionRecoveredJobs from './mail-inspection-recovered-jobs.vue'
import MailInspectionSensitiveCredentials from './mail-inspection-sensitive-credentials.vue'
import AdminFeedbackBanner from './admin-feedback-banner.vue'
import AdminPageShell from './admin-page-shell.vue'
import { adminMailInspectionApi } from '@/common/admin/admin-mail-inspection-api.js'
import { guardedAdminNavigate } from '@/common/admin/admin-route-guard-runtime.js'
import {
	createUnavailableAdminMailInspectionApi,
	requireAdminMailInspectionApi
} from '@/common/admin/mail-inspection-api-contract.js'
import {
	adminMailInspectionSessionStore
} from '@/common/admin/admin-mail-inspection-session-store.js'
import {
	analyzeMailboxCredentialText
} from '@/common/admin/mail-inspection-credential-parser.js'
import {
	chooseMailInspectionTextFile,
	isMailInspectionFilePickerAvailable
} from '@/common/admin/mail-inspection-file-picker.js'
import {
	countMailInspectionGroups,
	mailInspectionBusinessCategoryOptions,
	presentMailInspectionResults,
	recoverUnregisteredCredentialLines
} from '@/common/admin/mail-inspection-presenter.js'
import {
	createMailInspectionCredentialExport,
	deleteMailInspectionCredentialExport,
	exportMailInspectionCredentialFile,
	openMailInspectionCredentialExport
} from '@/common/admin/mail-inspection-credential-export.js'
import {
	createMailInspectionJobController
} from '@/common/admin/mail-inspection-job-controller.js'

function emptyViewState(inspectionType) {
	return {
		state: 'IDLE',
		inspectionType,
		draftText: '',
		analysis: analyzeMailboxCredentialText(''),
		credentialLines: [],
		clientRequestId: '',
		submissionStartedAt: '',
		jobId: '',
		job: null,
		results: [],
		message: '',
		pollAfterMillis: 2000,
		businessConcurrency: 4
	}
}

export default {
	name: 'MailInspectionWorkspace',
	components: {
		AdminActionButton,
		MailInspectionBusinessTabs,
		MailInspectionCredentialInput,
		MailInspectionJobProgress,
		MailInspectionResultList,
		MailInspectionRecoveredJobs,
		MailInspectionSensitiveCredentials,
		AdminFeedbackBanner,
		AdminPageShell
	},
	emits: ['update:ip2-mode'],
	props: {
		inspectionType: { type: String, required: true },
		activeBusiness: { type: String, required: true },
		eyebrow: { type: String, required: true },
		title: { type: String, required: true },
		description: { type: String, required: true },
		showIp2Modes: { type: Boolean, default: false },
		ip2Mode: { type: String, default: '' }
	},
	data() {
		return {
			controller: null,
			mailInspectionApi: null,
			apiContractError: null,
			restored: false,
			viewState: emptyViewState(this.inspectionType),
			inputCollapsed: false,
			focusInvalid: false,
			filePickerAvailable: isMailInspectionFilePickerAvailable(),
			persistenceMode: adminMailInspectionSessionStore.persistenceMode(),
			bannerMessage: '',
			bannerType: '',
			clipboardStatus: '',
			moreMenuOpen: false,
			recoveredJobs: [],
			resumeBusyJobId: '',
			activeBusinessCategory: '',
			unregisteredCredentialsRevealed: false,
			credentialExportBusy: false,
			exportedCredentialFile: null,
			sensitiveCredentialStatus: '',
			ip2Modes: [
				{ value: 'IP2LOCATION_REGISTRATION', label: '注册邮件检查' },
				{ value: 'IP2LOCATION_VERIFY_LINK', label: '验证链接提取' }
			],
			workflowStages: [
				{ value: 'PREPARE', label: '准备凭证' },
				{ value: 'RUNNING', label: '执行检查' },
				{ value: 'RESULTS', label: '查看结果' }
			]
		}
	},
	computed: {
		creating() {
			return ['CREATING', 'DISPATCHING'].includes(this.viewState.state)
		},
		submissionNeedsConfirmation() {
			return ['SUBMISSION_UNKNOWN', 'SERVICE_UNAVAILABLE', 'AWAITING_CLIENT_RESUBMISSION']
				.includes(this.viewState.state)
		},
		submissionLocked() {
			return Boolean(this.viewState.jobId)
				|| ['CREATING', 'DISPATCHING', 'SUBMISSION_UNKNOWN', 'SERVICE_UNAVAILABLE', 'AWAITING_CLIENT_RESUBMISSION']
					.includes(this.viewState.state)
		},
		submissionRecoveryTitle() {
			if (this.viewState.state === 'SERVICE_UNAVAILABLE') {
				return '该类型邮箱检查暂不可用'
			}
			return this.viewState.state === 'AWAITING_CLIENT_RESUBMISSION'
				? '部分凭证尚未持久化'
				: '提交结果暂不确定'
		},
		presentedResults() {
			return presentMailInspectionResults(this.viewState.results)
		},
		resultGroupCounts() {
			return countMailInspectionGroups(this.presentedResults)
		},
		resultSummaryItems() {
			const businessItems = mailInspectionBusinessCategoryOptions(this.inspectionType)
				.map(option => ({
					...option,
					count: this.resultGroupCounts[option.value] || 0
				}))
			const businessCount = businessItems.reduce((total, item) => total + item.count, 0)
			const otherCount = Math.max(0, this.presentedResults.length - businessCount)
			if (otherCount) {
				businessItems.push({
					value: 'OTHER',
					label: '凭证、网络或内部错误',
					tone: 'danger',
					count: otherCount
				})
			}
			return businessItems
		},
		unregisteredResults() {
			return this.presentedResults.filter(
				result => result.businessCategory === 'UNREGISTERED')
		},
		unregisteredCredentialLines() {
			return recoverUnregisteredCredentialLines(
				this.inspectionType,
				this.viewState.results,
				this.viewState.credentialLines)
		},
		visibleUnregisteredCredentialLines() {
			return this.unregisteredCredentialsRevealed
				? this.unregisteredCredentialLines
				: []
		},
		sensitiveCredentialPanelVisible() {
			return this.activeBusinessCategory === 'UNREGISTERED'
				&& this.unregisteredResults.length > 0
		},
		retryLines() {
			return this.controller ? this.controller.retryLines() : []
		},
		visibleRecoveredJobs() {
			return this.recoveredJobs.filter(job =>
				job.inspectionType === this.inspectionType)
		},
		currentRoutePath() {
			return {
				OPENAI: '/pages/mail-inspection/openai/index',
				KIRO: '/pages/mail-inspection/kiro/index',
				IP2LOCATION: '/pages/mail-inspection/ip2location/index'
			}[this.activeBusiness] || '/pages/mail-inspection/openai/index'
		},
		workflowStage() {
			if (this.presentedResults.length || ['COMPLETED', 'FAILED'].includes(this.viewState.state)) {
				return 'RESULTS'
			}
			if (this.submissionLocked || [
				'QUEUED',
				'RUNNING',
				'AWAITING_ADMIN_RESUME',
				'POLLING_INTERRUPTED'
			].includes(this.viewState.state)) return 'RUNNING'
			return 'PREPARE'
		},
		workflowStageIndex() {
			return this.workflowStages.findIndex(stage => stage.value === this.workflowStage)
		},
		workflowStageLabel() {
			return this.workflowStages[this.workflowStageIndex]?.label || '准备凭证'
		},
	},
	created() {
		try {
			this.mailInspectionApi = requireAdminMailInspectionApi(adminMailInspectionApi)
		} catch (error) {
			this.apiContractError = error
			this.mailInspectionApi = createUnavailableAdminMailInspectionApi(error)
		}
		this.controller = createMailInspectionJobController({
			inspectionType: this.inspectionType,
			api: this.mailInspectionApi,
			store: adminMailInspectionSessionStore,
			onChange: next => {
				const previousJobId = this.viewState.jobId
				this.viewState = next
				this.persistenceMode = adminMailInspectionSessionStore.persistenceMode()
				if (previousJobId !== next.jobId) this.resetSensitiveCredentialView()
				if (['CREATING', 'DISPATCHING', 'SUBMISSION_UNKNOWN', 'SERVICE_UNAVAILABLE', 'AWAITING_CLIENT_RESUBMISSION', 'QUEUED', 'RUNNING', 'AWAITING_ADMIN_RESUME', 'COMPLETED', 'FAILED', 'POLLING_INTERRUPTED'].includes(next.state)
					&& next.credentialLines.length) {
					this.inputCollapsed = true
				}
			}
		})
	},
	async mounted() {
		try {
			await this.controller.restore({ allowNetwork: !this.apiContractError })
			if (this.apiContractError) {
				this.setError(this.apiContractError, '前端资源版本不一致。')
				return
			}
			await this.refreshRecoveredJobs()
			this.inputCollapsed = Boolean(this.viewState.jobId)
		} catch (error) {
			this.setError(error, '任务恢复失败。')
		} finally {
			this.restored = true
		}
	},
	beforeDestroy() {
		this.pause()
	},
	beforeUnmount() {
		this.pause()
	},
	methods: {
		navigateProtected(route) {
			if (route === this.currentRoutePath) return
			return guardedAdminNavigate(route)
		},
		goBack() {
			uni.navigateBack({
				fail: () => uni.reLaunch({ url: '/pages/index/index' })
			})
		},
		clearBanner() {
			this.bannerMessage = ''
			this.bannerType = ''
		},
		openClearAll() {
			this.moreMenuOpen = false
			this.confirmClearAll()
		},
		setError(error, fallback) {
			this.bannerMessage = error?.message || fallback
			this.bannerType = 'error'
		},
		updateDraft(value) {
			this.focusInvalid = false
			this.controller.setDraftText(value)
		},
		updateBusinessConcurrency(value) {
			const next = this.controller.setBusinessConcurrency(Number(value))
			this.viewState = next
		},
		selectBusinessCategory(value) {
			const next = String(value || '')
			if (next !== this.activeBusinessCategory) this.hideUnregisteredCredentials()
			this.activeBusinessCategory = next
		},
		requestUnregisteredPanel() {
			this.activeBusinessCategory = 'UNREGISTERED'
		},
		resetSensitiveCredentialView() {
			this.activeBusinessCategory = ''
			this.hideUnregisteredCredentials()
		},
		hideUnregisteredCredentials() {
			this.unregisteredCredentialsRevealed = false
			this.sensitiveCredentialStatus = ''
		},
		confirmRevealUnregistered() {
			if (!this.unregisteredCredentialLines.length) {
				this.sensitiveCredentialStatus = '当前会话已不存在原始凭证，无法恢复四段格式。'
				return
			}
			uni.showModal({
				title: '显示敏感凭证',
				content: '内容包含密码和 Refresh Token。请确认周围环境安全，并仅在当前管理操作中使用。',
				confirmText: '确认显示',
				confirmColor: '#e89a4a',
				success: result => {
					if (!result.confirm) return
					this.unregisteredCredentialsRevealed = true
					this.sensitiveCredentialStatus = `已显示 ${this.unregisteredCredentialLines.length} 行未注册凭证。`
				}
			})
		},
		confirmCopyUnregistered() {
			if (!this.unregisteredCredentialsRevealed
				|| !this.unregisteredCredentialLines.length) return
			uni.showModal({
				title: '复制未注册原始凭证',
				content: '即将把密码和 Refresh Token 写入系统剪贴板。请只粘贴到你信任的位置，并及时清理剪贴板。',
				confirmText: '继续复制',
				confirmColor: '#e89a4a',
				success: result => {
					if (!result.confirm) return
					this.copyValue(
						this.unregisteredCredentialLines.join('\n'),
						'未注册原始凭证')
				}
			})
		},
		confirmExportUnregistered() {
			if (!this.unregisteredCredentialsRevealed
				|| !this.unregisteredCredentialLines.length
				|| this.credentialExportBusy) return
			const android = typeof plus !== 'undefined' && plus?.os?.name === 'Android'
			uni.showModal({
				title: '导出未注册原始凭证',
				content: android
					? '文件包含密码和 Refresh Token，将写入应用私有目录；退出或会话失效时自动清理。'
					: '文件包含密码和 Refresh Token，将下载到浏览器下载目录；应用无法自动删除，请由管理员妥善保管并及时删除。',
				confirmText: '确认导出',
				confirmColor: '#e89a4a',
				success: async result => {
					if (!result.confirm) return
					this.credentialExportBusy = true
					this.sensitiveCredentialStatus = ''
					try {
						const exportFile = createMailInspectionCredentialExport({
							inspectionType: this.inspectionType,
							jobId: this.viewState.jobId,
							credentialLines: this.unregisteredCredentialLines
						})
						const exported = await exportMailInspectionCredentialFile(exportFile)
						this.exportedCredentialFile = exported.path ? exported : null
						this.sensitiveCredentialStatus = exported.platform === 'ANDROID'
							? '未注册凭证已写入应用私有目录。'
							: '未注册凭证 TXT 下载已开始；请及时安全保存或删除。'
					} catch (error) {
						this.setError(error, '未注册凭证导出失败。')
					} finally {
						this.credentialExportBusy = false
					}
				}
			})
		},
		async openExportedCredentialFile() {
			if (!this.exportedCredentialFile?.path) return
			try {
				await openMailInspectionCredentialExport(this.exportedCredentialFile.path)
				this.sensitiveCredentialStatus = '已请求系统打开导出文件。'
			} catch (error) {
				this.setError(error, '导出文件打开失败。')
			}
		},
		confirmDeleteExportedCredentialFile() {
			if (!this.exportedCredentialFile?.path) return
			uni.showModal({
				title: '删除导出文件',
				content: '将从应用私有目录删除这份未注册原始凭证 TXT。',
				confirmText: '删除',
				confirmColor: '#d9686b',
				success: async result => {
					if (!result.confirm) return
					try {
						await deleteMailInspectionCredentialExport(
							this.exportedCredentialFile.path)
						this.exportedCredentialFile = null
						this.sensitiveCredentialStatus = '应用私有导出文件已删除。'
					} catch (error) {
						this.setError(error, '导出文件删除失败。')
					}
				}
			})
		},
		async refreshRecoveredJobs() {
			try {
				this.recoveredJobs = await this.mailInspectionApi.getRecoveredJobs()
			} catch (error) {
				this.setError(error, '恢复任务列表读取失败。')
			}
		},
		approveRecoveredJob(job) {
			uni.showModal({
				title: '批准继续处理剩余任务',
				content: `将按原业务并发 ${job.businessConcurrency} 继续处理 RabbitMQ 中的 ${job.remainingCount} 项，不会重新发布消息。`,
				confirmText: '批准继续',
				confirmColor: '#f3be58',
				success: async result => {
					if (!result.confirm) return
					this.resumeBusyJobId = job.jobId
					try {
						const resumed = await this.mailInspectionApi.resumeJob(job.jobId)
						await this.controller.trackJob(resumed)
						await this.refreshRecoveredJobs()
					} catch (error) {
						this.setError(error, '恢复任务启动失败。')
					} finally {
						this.resumeBusyJobId = ''
					}
				}
			})
		},
		async chooseFile() {
			this.clearBanner()
			try {
				const selected = await chooseMailInspectionTextFile()
				this.controller.setDraftText(selected.text)
				this.inputCollapsed = false
				this.bannerMessage = `已读取 ${selected.name}，请检查格式后提交。`
				this.bannerType = 'success'
			} catch (error) {
				if (error?.code !== 'FILE_PICKER_CANCELLED') this.setError(error, 'TXT 文件读取失败。')
			}
		},
		async submit() {
			this.clearBanner()
			this.resetSensitiveCredentialView()
			try {
				const next = await this.controller.submit(this.viewState.draftText)
				if (next.state === 'VALIDATING') {
					this.inputCollapsed = false
					this.focusInvalid = true
					this.$nextTick(() => { this.focusInvalid = false })
					return
				}
				this.inputCollapsed = true
			} catch (error) {
				this.setError(error, '任务创建失败。')
			}
		},
		async continueSubmission() {
			this.clearBanner()
			this.hideUnregisteredCredentials()
			try {
				await this.controller.continueSubmission()
				this.inputCollapsed = true
			} catch (error) {
				this.setError(error, '原提交确认失败。')
			}
		},
		confirmClear() {
			uni.showModal({
				title: '清除本次凭证',
				content: '将删除当前业务的草稿、原始凭证和任务编号；后端已创建的任务不会被取消。',
				confirmText: '清除',
				confirmColor: '#d9686b',
				success: result => {
					if (!result.confirm) return
					this.resetSensitiveCredentialView()
					this.controller.clear()
					this.inputCollapsed = false
				}
			})
		},
		confirmClearAll() {
			uni.showModal({
				title: '清除全部邮箱检查数据',
				content: '将删除 OpenAI、Kiro 和两个 IP2Location 模式保留的全部本地凭证与任务编号。',
				confirmText: '全部清除',
				confirmColor: '#d9686b',
				success: result => {
					if (!result.confirm) return
					this.resetSensitiveCredentialView()
					this.controller.clear()
					void adminMailInspectionSessionStore.clearAll()
					this.exportedCredentialFile = null
					this.inputCollapsed = false
					this.bannerMessage = '本机邮箱检查会话数据已清除。'
					this.bannerType = 'success'
				}
			})
		},
		confirmRetry() {
			if (!this.retryLines.length) return
			uni.showModal({
				title: '重新提交网络失败项',
				content: `将 ${this.retryLines.length} 行网络重试耗尽凭证创建为一个新任务；后端仍只会有限尝试三次。`,
				confirmText: '重新提交',
				confirmColor: '#f3be58',
				success: async result => {
					if (!result.confirm) return
					this.resetSensitiveCredentialView()
					try {
						await this.controller.retryExhausted()
						this.inputCollapsed = true
					} catch (error) {
						this.setError(error, '网络失败项重新提交失败。')
					}
				}
			})
		},
		confirmCopyRetry() {
			if (!this.retryLines.length) return
			uni.showModal({
				title: '复制敏感凭证',
				content: '即将复制密码和 refresh token。只应粘贴到你信任的位置。',
				confirmText: '继续复制',
				confirmColor: '#e89a4a',
				success: result => {
					if (result.confirm) this.copyValue(this.retryLines.join('\n'), '原始重试凭证')
				}
			})
		},
		copyValue(value, label) {
			if (!value) return
			uni.setClipboardData({
				data: String(value),
				success: () => {
					this.clipboardStatus = `${label}已复制。`
					if (label === '未注册原始凭证') {
						this.sensitiveCredentialStatus = '未注册原始凭证已复制；请及时清理系统剪贴板。'
					}
				},
				fail: () => {
					this.clipboardStatus = `${label}复制失败。`
					if (label === '未注册原始凭证') {
						this.sensitiveCredentialStatus = '未注册原始凭证复制失败。'
					}
				}
			})
		},
		pause() {
			this.hideUnregisteredCredentials()
			this.controller?.pause()
		},
		async resume() {
			if (!this.controller || !this.restored) return
			try {
				await this.controller.resume()
				await this.refreshRecoveredJobs()
			} catch (error) {
				this.setError(error, '任务状态恢复失败。')
			}
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.inspection-page {
	min-height: 100vh;
	box-sizing: border-box;
	padding: 34rpx 34rpx calc(48rpx + env(safe-area-inset-bottom));
	background:
		radial-gradient(circle at 92% 0%, rgba($app-action-teal, .07), transparent 30%),
		$app-bg;
	color: $app-text;
}

.inspection-shell {
	width: min(1640px, 100%);
	margin: $app-space-4 auto 0;
}

.submission-recovery {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 24rpx;
	margin: 20rpx 0;
	padding: 24rpx;
	border: 1px solid rgba($app-warning, .46);
	background: rgba($app-warning, .08);
}

.submission-recovery-title,
.submission-recovery-copy {
	display: block;
}

.submission-recovery-title {
	color: $app-text;
	font-weight: 700;
}

.submission-recovery-copy {
	margin-top: 8rpx;
	color: $app-text-muted;
}

.stage-status {
	min-height: 48rpx;
	padding: 0 16rpx;
	border-radius: 999px;
	display: inline-flex;
	align-items: center;
	gap: 10rpx;
	background: rgba($app-green, .1);
	color: #d9fbfb;
	font-size: $app-font-size-caption;
}

.stage-status-dot {
	width: 10rpx;
	height: 10rpx;
	border-radius: 50%;
	background: $app-green;
}

.more-actions {
	position: relative;
}

.more-menu {
	position: absolute;
	top: calc(100% + 10rpx);
	right: 0;
	z-index: 60;
	width: 300rpx;
	padding: 8rpx;
	border-radius: $app-radius-control;
	@include admin-glass-chrome(true);
	box-shadow: $app-shadow-floating;
}

.more-menu button {
	width: 100%;
	min-height: 76rpx;
	margin: 0;
	padding: 0 18rpx;
	border: 0;
	border-radius: 10rpx;
	background: transparent;
	color: $app-danger-text;
	font-size: 24rpx;
	text-align: left;
}

.more-menu button::after {
	border: 0;
}

.more-menu button:focus-visible {
	@include admin-focus-ring;
}

.mode-tabs {
	max-width: 760rpx;
	min-height: 88rpx;
	margin-top: 16rpx;
	padding: 5rpx;
	border: 1px solid $app-border;
	border-radius: $app-radius-control;
	display: grid;
	grid-template-columns: 1fr 1fr;
	gap: 5rpx;
	background: #0b1115;
}

.mode-tabs button {
	min-height: 76rpx;
	margin: 0;
	border: 0;
	border-radius: 9rpx;
	background: transparent;
	color: $app-muted;
	font-size: 24rpx;
	transition: background-color 180ms ease, color 180ms ease, transform 120ms ease-out;
}

.mode-tabs button.active {
	background: rgba($app-action-amber, .14);
	color: #ffe6b9;
}

.mode-tabs button:active {
	transform: scale(.985);
}

.mode-tabs button::after {
	border: 0;
}

.workflow-stages {
	min-height: 92rpx;
	margin-top: $app-space-3;
	padding: 12rpx;
	@include admin-solid-panel;
	display: grid;
	grid-template-columns: repeat(3, minmax(0, 1fr));
	gap: 10rpx;
}

.workflow-stage {
	min-width: 0;
	padding: 12rpx 14rpx;
	border-radius: $app-radius-control;
	display: flex;
	align-items: center;
	gap: 12rpx;
	color: $app-muted;
	font-size: 24rpx;
}

.workflow-stage.active {
	color: $app-text;
}

.workflow-stage.current {
	background: rgba($app-green, .09);
}

.workflow-stage-mark {
	width: 40rpx;
	height: 40rpx;
	flex: 0 0 auto;
	border-radius: 50%;
	display: flex;
	align-items: center;
	justify-content: center;
	background: rgba($app-muted, .11);
	color: $app-muted;
	font-size: 24rpx;
	font-weight: 760;
}

.workflow-stage.active .workflow-stage-mark {
	background: rgba($app-green, .16);
	color: $app-green;
}

.inspection-shell > .admin-feedback-banner {
	margin-top: $app-space-3;
}

.workspace-grid {
	margin-top: 24rpx;
	display: grid;
	grid-template-columns: minmax(0, 1.35fr) minmax(340rpx, .65fr);
	gap: 20rpx;
	align-items: start;
}

.workspace-grid.stage-prepare {
	grid-template-columns: minmax(0, 1fr);
}

.result-summary {
	margin-top: $app-space-3;
	padding: $app-space-3;
	@include admin-solid-panel;
	display: grid;
	grid-template-columns: minmax(220rpx, .5fr) minmax(0, 1.5fr);
	gap: $app-space-4;
	align-items: center;
}

.summary-title,
.summary-copy,
.summary-value {
	display: block;
}

.summary-title {
	font-size: 30rpx;
	font-weight: 750;
}

.summary-copy {
	margin-top: 6rpx;
	color: $app-muted;
	font-size: 24rpx;
	line-height: 1.45;
}

.summary-metrics {
	display: grid;
	grid-template-columns: repeat(auto-fit, minmax(180rpx, 1fr));
	gap: 10rpx;
}

.summary-metrics > view {
	padding: 16rpx;
	border-radius: $app-radius-control;
	background: $app-surface-soft;
	color: $app-muted;
	font-size: 24rpx;
}

.summary-value {
	margin-bottom: 5rpx;
	color: $app-text;
	font-size: 34rpx;
	font-weight: 780;
	font-variant-numeric: tabular-nums;
}

.summary-value.warning {
	color: $app-warning;
}

.summary-value.success {
	color: $app-action-lime;
}

.summary-value.neutral {
	color: $app-text;
}

.summary-value.danger {
	color: $app-danger-text;
}

.clipboard-status {
	position: absolute;
	width: 1px;
	height: 1px;
	margin: -1px;
	padding: 0;
	overflow: hidden;
	clip: rect(0 0 0 0);
	white-space: nowrap;
	border: 0;
}

button:focus-visible {
	outline: 2px solid $app-focus;
	outline-offset: 2px;
}

@media (max-width: 767px) {
	.inspection-shell { margin-top: $app-space-3; }

	.mode-tabs {
		max-width: none;
	}

	.workspace-grid {
		grid-template-columns: 1fr;
	}

	.workflow-stages {
		min-height: 82rpx;
		padding: 8rpx;
	}

	.workflow-stage {
		justify-content: center;
		padding: 10rpx 8rpx;
	}

	.workflow-stage text {
		display: none;
	}

	.result-summary {
		grid-template-columns: 1fr;
		gap: $app-space-3;
	}

	.summary-metrics {
		grid-template-columns: 1fr 1fr;
	}
}

@media (prefers-reduced-motion: reduce) {
	.mode-tabs button {
		transition: none;
	}

	.mode-tabs button:active {
		transform: none;
	}
}

@media (prefers-reduced-transparency: reduce) {
	.more-menu {
		background: $app-surface-elevated;
		-webkit-backdrop-filter: none;
		backdrop-filter: none;
	}
}

@media (prefers-contrast: more) {
	.workflow-stages,
	.result-summary,
	.more-menu {
		border: 2px solid $app-text;
		background: $app-canvas;
	}
}
</style>
