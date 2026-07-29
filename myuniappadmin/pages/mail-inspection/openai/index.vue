<template>
	<mail-inspection-workspace
		v-if="adminRouteReady"
		ref="workspace"
		inspection-type="OPENAI_STATUS"
		active-business="OPENAI"
		eyebrow="MAIL EVIDENCE · OPENAI"
		title="OpenAI 邮件证据检查"
		description="通过 Microsoft OAuth 与 Outlook IMAP 搜索 OpenAI / ChatGPT 邮件，区分正常证据、限制证据、无注册证据和无法分类。"
	/>
</template>

<script>
import MailInspectionWorkspace from '@/components/admin/mail-inspection-workspace.vue'
import { createAdminPageGuardMixin } from '@/common/admin/admin-page-guard.js'

export default {
	mixins: [createAdminPageGuardMixin('/pages/mail-inspection/openai/index')],
	components: { MailInspectionWorkspace },
	onShow() {
		this.runAfterAdminRouteGuard(() =>
			this.$nextTick(() => this.$refs.workspace?.resume()))
	},
	onHide() {
		this.$refs.workspace?.pause()
	},
	onUnload() {
		this.$refs.workspace?.pause()
	}
}
</script>
