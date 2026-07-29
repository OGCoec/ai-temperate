const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const component = fs.readFileSync(
	path.resolve(__dirname, '..', '..', 'components', 'admin',
		'mail-inspection-sensitive-credentials.vue'),
	'utf8')

test('sensitive credential panel does not render raw lines before confirmation', () => {
	assert.match(component, /v-if="revealed"/)
	assert.match(component, /显示原始四段凭证/)
	assert.match(component, /内容包含密码和 Refresh Token/)
	assert.match(component, /request-reveal/)
	assert.doesNotMatch(component, /v-html/)
})

test('sensitive credential panel exposes explicit hide copy export and Android file actions', () => {
	for (const event of [
		'request-hide',
		'request-copy',
		'request-export',
		'request-open-export',
		'request-delete-export'
	]) {
		assert.match(component, new RegExp(event))
	}
	assert.match(component, /当前会话已不存在原始凭证，无法恢复四段格式。/)
	assert.match(component, /导出路径/)
})
