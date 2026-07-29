<template>
	<mail-inspection-workspace
		v-if="adminRouteReady"
		:key="inspectionType"
		ref="workspace"
		:inspection-type="inspectionType"
		active-business="IP2LOCATION"
		:eyebrow="mode === 'IP2LOCATION_REGISTRATION' ? 'MAIL EVIDENCE · IP2LOCATION' : 'VERIFY LINK · IP2LOCATION'"
		:title="mode === 'IP2LOCATION_REGISTRATION' ? 'IP2Location 注册邮件检查' : 'IP2Location 验证链接提取'"
		:description="modeDescription"
		:show-ip2-modes="true"
		:ip2-mode="mode"
		@update:ip2-mode="changeMode"
	/>
</template>

<script>
import MailInspectionWorkspace from '@/components/admin/mail-inspection-workspace.vue'
import { createAdminPageGuardMixin } from '@/common/admin/admin-page-guard.js'

export default {
	mixins: [createAdminPageGuardMixin('/pages/mail-inspection/ip2location/index')],
	components: { MailInspectionWorkspace },
	data() {
		return {
			mode: 'IP2LOCATION_REGISTRATION'
		}
	},
	computed: {
		inspectionType() {
			return this.mode
		},
		modeDescription() {
			return this.mode === 'IP2LOCATION_REGISTRATION'
				? '扫描 IP2Location 注册候选邮件，明确区分已找到与扫描完成后未找到。'
				: '从 IP2Location 邮件中提取规范验证 URL 与单独 verifyToken；敏感结果只保存在任务内存有效期内。'
		}
	},
	onShow() {
		this.runAfterAdminRouteGuard(() =>
			this.$nextTick(() => this.$refs.workspace?.resume()))
	},
	onHide() {
		this.$refs.workspace?.pause()
	},
	onUnload() {
		this.$refs.workspace?.pause()
	},
	methods: {
		changeMode(value) {
			if (!['IP2LOCATION_REGISTRATION', 'IP2LOCATION_VERIFY_LINK'].includes(value)) return
			this.$refs.workspace?.pause()
			this.mode = value
		}
	}
}
</script>
