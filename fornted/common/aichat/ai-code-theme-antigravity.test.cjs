const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

test('loads only the fixed Dark Plus theme instead of the complete theme registry', () => {
	const source = fs.readFileSync(path.join(__dirname, 'ai-code-highlighter-shiki.js'), 'utf8')

	assert.match(source, /from ['"]@shikijs\/themes\/dark-plus['"]/)
	assert.match(source, /from ['"]shiki\/core['"]/)
	assert.match(source, /from ['"]shiki\/langs['"]/)
	assert.doesNotMatch(source, /from ['"]shiki['"]/)
	assert.doesNotMatch(source, /\bbundledThemes\b/)
})

test('creates the Antigravity Default Dark Modern canvas over Dark Plus token rules', async () => {
	const { AI_CODE_THEME_NAME, createAntigravityCodeTheme } = await loadEsmModule(
		path.join(__dirname, 'ai-code-theme-antigravity.js')
	)
	const theme = createAntigravityCodeTheme({
		name: 'dark-plus',
		bg: '#1E1E1E',
		fg: '#D4D4D4',
		tokenColors: [{ scope: ['comment'], settings: { foreground: '#6A9955' } }]
	})

	assert.equal(theme.name, AI_CODE_THEME_NAME)
	assert.equal(theme.bg, '#1F1F1F')
	assert.equal(theme.fg, '#D4D4D4')
	assert.equal(theme.tokenColors[0].settings.foreground, '#6A9955')
	assert.equal(Object.prototype.hasOwnProperty.call(theme, 'settings'), false)
})

test('maps only the fixed Antigravity palette and allowed font styles into view roles', async () => {
	const { AI_CODE_COLOR_ROLES, safeAiCodeTokenStyle } = await loadEsmModule(
		path.join(__dirname, 'ai-code-theme-antigravity.js')
	)

	assert.deepEqual(safeAiCodeTokenStyle({ color: '#6a9955', fontStyle: 3 }), {
		colorRole: 'comment',
		fontStyles: ['italic', 'bold']
	})
	assert.deepEqual(safeAiCodeTokenStyle({ color: 'url(javascript:alert(1))', fontStyle: 15 }), {
		colorRole: 'foreground',
		fontStyles: ['italic', 'bold', 'underline']
	})
	assert.deepEqual(safeAiCodeTokenStyle({ color: '#D4D4D4', fontStyle: -1 }), {
		colorRole: 'foreground',
		fontStyles: []
	})
	for (const [role, color] of Object.entries(AI_CODE_COLOR_ROLES)) {
		assert.equal(safeAiCodeTokenStyle({ color }).colorRole, role)
	}
})
