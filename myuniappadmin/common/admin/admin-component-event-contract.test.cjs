const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const projectRoot = path.resolve(__dirname, '..', '..')
const read = relativePath => fs.readFileSync(path.join(projectRoot, relativePath), 'utf8')

test('existing administrator component event names stay stable', () => {
	const contracts = new Map([
		['components/admin/admin-action-button.vue', ['click']],
		['components/admin/admin-identity-form.vue', ['update:email', 'update:countryId', 'update:phone']],
		['components/admin/ai-model-form.vue', ['update:modelValue', 'manage-icons']],
		['components/admin/ip2location-key-list.vue', [
			'open-import', 'page-change', 'delete-one', 'delete-selected'
		]],
		['components/admin/ip2location-key-import-sheet.vue', ['close', 'submit']],
		['components/admin/mail-inspection-credential-input.vue', [
			'update:draft-text',
			'update:business-concurrency',
			'choose-file',
			'clear',
			'submit',
			'toggle-collapsed'
		]],
		['components/admin/mail-inspection-result-list.vue', [
			'retry', 'copy-retry', 'copy-value'
		]],
		['components/admin/mail-inspection-recovered-jobs.vue', ['approve']],
		['components/admin/workspace/mail-inspection-panel.vue', ['update:ip2-mode']]
	])

	for (const [file, events] of contracts) {
		const source = read(file)
		for (const event of events) {
			assert.match(source, new RegExp(`['"]${event.replace(':', '\\:')}['"]`), `${file}: ${event}`)
		}
	}
})

test('administrator component event payload shapes stay stable', () => {
	const actionButton = read('components/admin/admin-action-button.vue')
	assert.match(actionButton, /\$emit\('click',\s*event\)/)

	const identity = read('components/admin/admin-identity-form.vue')
	assert.match(identity, /\$emit\('update:email',\s*\$event\)/)
	assert.match(identity, /\$emit\('update:countryId',\s*\$event\)/)
	assert.match(identity, /\$emit\('update:phone',\s*\$event\)/)

	const modelForm = read('components/admin/ai-model-form.vue')
	assert.match(modelForm, /\$emit\('update:modelValue',\s*\{/)
	assert.match(modelForm, /\$emit\('manage-icons'\)/)

	const ipList = read('components/admin/ip2location-key-list.vue')
	assert.match(ipList, /\$emit\('page-change',\s*page\s*-\s*1\)/)
	assert.match(ipList, /\$emit\('page-change',\s*page\s*\+\s*1\)/)
	assert.match(ipList, /\$emit\('delete-one',\s*item\)/)
	assert.match(ipList, /\$emit\('delete-selected',\s*\[\.\.\.selectedIds\]\)/)

	const ipImport = read('components/admin/ip2location-key-import-sheet.vue')
	assert.match(ipImport, /\$emit\('submit',\s*\{[\s\S]*planType:[\s\S]*initialQuota,[\s\S]*mode:[\s\S]*apiKeys:/)

	const mailInput = read('components/admin/mail-inspection-credential-input.vue')
	assert.match(mailInput, /\$emit\('update:draft-text',\s*\$event\.detail\.value\)/)
	assert.match(mailInput, /\$emit\('update:business-concurrency',\s*concurrency\)/)

	const results = read('components/admin/mail-inspection-result-list.vue')
	assert.match(results, /\$emit\('copy-value',\s*selectedResult\.verifyUrl,\s*'验证 URL'\)/)
	assert.match(results, /\$emit\('copy-value',\s*selectedResult\.verifyToken,\s*'验证 Token'\)/)

	const recovered = read('components/admin/mail-inspection-recovered-jobs.vue')
	assert.match(recovered, /\$emit\('approve',\s*job\)/)

	const workspace = read('components/admin/workspace/mail-inspection-panel.vue')
	assert.match(workspace, /\$emit\('update:ip2-mode',\s*option\.value\)/)
})

test('business pages keep requests behind their existing protected API modules', () => {
	const pageSources = [
		'components/admin/workspace/ai-model-list-panel.vue',
		'components/admin/workspace/ai-model-discovery-panel.vue',
		'components/admin/workspace/ai-model-create-panel.vue',
		'components/admin/workspace/ai-model-detail-panel.vue',
		'components/admin/workspace/ai-model-icons-panel.vue',
		'components/admin/workspace/ip2location-keys-panel.vue',
		'components/admin/workspace/mail-inspection-panel.vue'
	].map(read).join('\n')

	assert.doesNotMatch(pageSources, /uni\.(?:request|uploadFile)/)
	assert.match(pageSources, /adminAiModelApi/)
	assert.match(pageSources, /adminCliProxyModelApi/)
	assert.match(pageSources, /adminAiModelIconApi/)
	assert.match(pageSources, /adminIp2LocationKeyApi/)
	assert.match(pageSources, /adminMailInspectionApi/)
})

test('legacy mail pages redirect without mounting business components or sending requests', () => {
	for (const file of [
		'pages/mail-inspection/openai/index.vue',
		'pages/mail-inspection/kiro/index.vue',
		'pages/mail-inspection/ip2location/index.vue'
	]) {
		const source = read(file)
		assert.match(source, /redirectLegacyAdminWorkspace/)
		assert.doesNotMatch(source, /adminMailInspectionApi|MailInspectionPanel/)
	}
})
