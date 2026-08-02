const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const FRONTEND_ROOT = path.resolve(__dirname, '..', '..')

function readFrontendFile(relativePath) {
	return fs.readFileSync(path.join(FRONTEND_ROOT, relativePath), 'utf8')
}

function cssBlock(source, selector) {
	const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
	const match = source.match(new RegExp(`${escapedSelector}\\s*\\{([\\s\\S]*?)\\n\\s*\\}`))
	assert.ok(match, `missing CSS block for ${selector}`)
	return match[1]
}

test('ordinary user material defines dark frosted controls with accessible fallbacks', () => {
	const source = readFrontendFile('common/ui/user-material.scss')

	assert.match(source, /@mixin user-frosted-control/)
	assert.match(source, /@mixin user-frosted-surface/)
	assert.match(source, /@mixin user-safe-viewport/)
	assert.match(source, /backdrop-filter:\s*blur\(/)
	assert.match(source, /-webkit-backdrop-filter:\s*blur\(/)
	assert.match(source, /prefers-reduced-transparency:\s*reduce/)
	assert.match(source, /prefers-contrast:\s*more/)
	assert.match(source, /prefers-reduced-motion:\s*reduce/)
})

test('profile avatar action is a 48px centered control instead of relying on native button alignment', () => {
	const source = readFrontendFile('components/user/workspace/user-profile-panel.vue')
	const block = cssBlock(source, '.profile-avatar-button')

	assert.match(block, /min-height:\s*48px/)
	assert.match(block, /display:\s*flex/)
	assert.match(block, /align-items:\s*center/)
	assert.match(block, /justify-content:\s*center/)
	assert.match(block, /text-align:\s*center/)
})

test('ordinary user pages import the shared material and use dynamic viewport sizing', () => {
	for (const page of [
		'common/auth/auth.scss',
		'components/user/user-workspace.vue',
		'components/user/workspace/user-profile-panel.vue',
		'components/user/workspace/user-model-catalog.vue',
		'components/user/workspace/user-model-detail.vue',
		'components/user/user-primary-navigation.vue',
		'pages/launch/session-gate.vue',
		'pages/risk/webrtc-failed.vue',
		'pages/risk/blocked.vue'
	]) {
		const source = readFrontendFile(page)
		assert.match(source, /user-material\.scss/, `${page} must import the shared user material`)
	}

	assert.match(readFrontendFile('components/user/user-workspace.vue'), /100dvh/)
	assert.match(readFrontendFile('components/user/workspace/user-profile-panel.vue'), /height:\s*100%/)
	assert.match(readFrontendFile('components/user/workspace/user-model-catalog.vue'), /height:\s*100%/)
	assert.match(readFrontendFile('components/user/workspace/user-model-detail.vue'), /height:\s*100%/)
})

test('H5 gives every native and UniApp scroll area one quiet theme-matched scrollbar', () => {
	const app = readFrontendFile('App.vue')

	assert.match(app, /\*\s*\{[\s\S]*scrollbar-width:\s*thin/)
	assert.match(app, /scrollbar-color:\s*rgba\(135,\s*148,\s*141,\s*\.46\)\s*transparent/)
	assert.match(app, /\*::-webkit-scrollbar\s*\{[\s\S]*width:\s*6px[\s\S]*height:\s*6px/)
	assert.match(app, /\*::-webkit-scrollbar-track\s*\{[\s\S]*background:\s*transparent/)
	assert.match(app, /\*::-webkit-scrollbar-thumb\s*\{[\s\S]*border-radius:\s*999px/)
	assert.match(app, /\*::-webkit-scrollbar-button[\s\S]*width:\s*0[\s\S]*height:\s*0/)
	assert.match(app, /\*::-webkit-scrollbar-corner\s*\{[\s\S]*background:\s*transparent/)
})
