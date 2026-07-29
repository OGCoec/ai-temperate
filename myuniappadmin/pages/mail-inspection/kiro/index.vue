<template><view class="legacy-workspace-entry" aria-live="polite">正在打开 Kiro 邮件检查…</view></template>
<script>
import { redirectLegacyAdminWorkspace } from '@/common/admin/admin-workspace-legacy-entry.js'
export default { onLoad() { redirectLegacyAdminWorkspace('/pages/mail-inspection/kiro/index') } }
</script>
<style scoped>.legacy-workspace-entry { min-height: 100vh; padding: 32px; box-sizing: border-box; background: #080b0d; color: #819098; }</style>
