const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const projectRoot = path.resolve(__dirname, '..', '..')
const read = relativePath => fs.readFileSync(path.join(projectRoot, relativePath), 'utf8')

const visualComponents = [
	'components/admin/admin-page-shell.vue',
	'components/admin/admin-side-navigation.vue',
	'components/admin/admin-page-header.vue',
	'components/admin/admin-feedback-banner.vue',
	'components/admin/admin-material-sheet.vue'
]

test('Quiet Apple Pro keeps the approved administrator brand colors unchanged', () => {
	const theme = read('common/app-theme.scss')
	const approvedColors = {
		'$app-green': '#39d6d2',
		'$app-green-pressed': '#24bbb8',
		'$app-teal': '#69d4e2',
		'$app-action-teal': '#39d6d2',
		'$app-action-amber': '#f3be58',
		'$app-action-lime': '#a8dc4a',
		'$app-action-orange': '#e89a4a',
		'$app-danger': '#d9686b',
		'$app-danger-text': '#ffb8ba',
		'$app-focus': '#8be7e4'
	}

	for (const [token, value] of Object.entries(approvedColors)) {
		assert.match(theme, new RegExp(`${token.replace('$', '\\$')}\\s*:\\s*${value}`, 'i'))
	}
})

test('shared visual components are display-only and never own network primitives', () => {
	const sources = visualComponents.map(read).join('\n')

	assert.doesNotMatch(sources, /uni\.(?:request|uploadFile)/)
	assert.doesNotMatch(sources, /adminRequest|adminUploadFile/)
	assert.match(read('components/admin/admin-side-navigation.vue'), /emits:\s*\['navigate'\]/)
	assert.match(read('components/admin/admin-feedback-banner.vue'), /emits:\s*\['dismiss'\]/)
	assert.match(
		read('components/admin/admin-material-sheet.vue'),
		/emits:\s*\['update:modelValue',\s*'close'\]/
	)
})

test('visual system provides motion, transparency and contrast accessibility fallbacks', () => {
	const theme = read('common/app-theme.scss')
	const sources = [theme, ...visualComponents.map(read)].join('\n')

	assert.match(sources, /prefers-reduced-motion:\s*reduce/)
	assert.match(sources, /prefers-reduced-transparency:\s*reduce/)
	assert.match(sources, /prefers-contrast:\s*more/)
	assert.match(sources, /backdrop-filter/)
	assert.match(sources, /focus-visible/)
})

test('administrator motion only targets compositor-friendly properties', () => {
	const motion = read('common/admin/admin-motion.js')
	const sheet = read('components/admin/admin-material-sheet.vue')

	assert.match(motion, /requestAnimationFrame/)
	assert.match(motion, /cancelAnimationFrame/)
	assert.match(motion, /#ifdef H5/)
	assert.match(motion, /prefers-reduced-motion/)
	assert.match(sheet, /css-motion-fallback/)
	assert.match(sheet, /transition:[\s\S]*transform[\s\S]*opacity/)
	assert.doesNotMatch(motion, /style\.(?:width|height|maxHeight|top|left)\s*=/)
	assert.doesNotMatch(motion, /transition[^;\n]*(?:width|height|max-height|top|left)/)
})

test('WebRTC safety gate pages remain outside the Quiet Apple Pro component system', () => {
	const webRtcPages = [
		read('pages/risk/webrtc-probe.vue'),
		read('pages/risk/webrtc-failed.vue')
	].join('\n')

	assert.doesNotMatch(
		webRtcPages,
		/AdminPageShell|AdminMaterialSheet|admin-motion|quiet-apple/
	)
})

test('mail inspection keeps six concurrency presets and an accessible compositor progress bar', () => {
	const input = read('components/admin/mail-inspection-credential-input.vue')
	const progress = read('components/admin/mail-inspection-job-progress.vue')

	assert.match(input, /concurrencyPresets:\s*\[1,\s*4,\s*8,\s*16,\s*32,\s*64\]/)
	assert.match(progress, /role="progressbar"/)
	assert.match(progress, /:aria-valuemin="0"/)
	assert.match(progress, /:aria-valuemax="100"/)
	assert.match(progress, /:aria-valuenow="progressPercent"/)
	assert.match(progress, /transform:\s*`scaleX\(\$\{progressScale\}\)`/)
	assert.doesNotMatch(progress, /transition[^;\n]*width/)
})

test('administrator H5 scroll areas use the dark canvas scrollbar without native arrow buttons', () => {
	const app = read('App.vue')

	assert.match(app, /\*\s*\{[\s\S]*scrollbar-width:\s*thin/)
	assert.match(app, /scrollbar-color:\s*rgba\(139,\s*156,\s*154,\s*\.46\)\s*transparent/)
	assert.match(app, /\*::-webkit-scrollbar\s*\{[\s\S]*width:\s*6px[\s\S]*height:\s*6px/)
	assert.match(app, /\*::-webkit-scrollbar-track\s*\{[\s\S]*background:\s*transparent/)
	assert.match(app, /\*::-webkit-scrollbar-thumb\s*\{[\s\S]*border-radius:\s*999px/)
	assert.match(app, /\*::-webkit-scrollbar-button[\s\S]*width:\s*0[\s\S]*height:\s*0/)
	assert.match(app, /\*::-webkit-scrollbar-corner\s*\{[\s\S]*background:\s*transparent/)
})
