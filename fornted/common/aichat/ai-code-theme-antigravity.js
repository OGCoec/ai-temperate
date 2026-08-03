export const AI_CODE_THEME_NAME = 'ai-antigravity-default-dark-modern'

export const AI_CODE_COLOR_ROLES = Object.freeze({
	foreground: '#D4D4D4',
	comment: '#6A9955',
	keyword: '#569CD6',
	control: '#C586C0',
	type: '#4EC9B0',
	function: '#DCDCAA',
	variable: '#9CDCFE',
	constant: '#4FC1FF',
	string: '#CE9178',
	number: '#B5CEA8',
	regexp: '#D16969',
	invalid: '#F44747',
	'accent-gold': '#D7BA7D',
	'regexp-muted': '#646695',
	'markup-list': '#6796E6',
	'tag-punctuation': '#808080',
	label: '#C8C8C8',
	header: '#000080',
	'bracket-level-1': '#FFD700',
	'bracket-level-2': '#DA70D6',
	'bracket-level-3': '#179FFF',
	'bracket-error': 'rgba(255, 18, 18, .8)'
})

const COLOR_ROLE_BY_VALUE = new Map(
	Object.entries(AI_CODE_COLOR_ROLES).map(([role, color]) => [color.toUpperCase(), role])
)
const ALLOWED_FONT_STYLES = new Set(['italic', 'bold', 'underline'])

export function createAntigravityCodeTheme(baseTheme = {}) {
	const source = baseTheme?.default || baseTheme || {}
	const clonedRules = rules => rules.map(rule => ({
		...rule,
		settings: { ...(rule.settings || {}) }
	}))
	return {
		...source,
		name: AI_CODE_THEME_NAME,
		bg: '#1F1F1F',
		fg: '#D4D4D4',
		colors: {
			...(source.colors || {}),
			'editor.background': '#1F1F1F',
			'editor.foreground': '#CCCCCC',
			'textCodeBlock.background': '#2B2B2B'
		},
		...(Array.isArray(source.tokenColors)
			? { tokenColors: clonedRules(source.tokenColors) }
			: {}),
		...(Array.isArray(source.settings)
			? { settings: clonedRules(source.settings) }
			: {})
	}
}

function normalizedFontStyles(fontStyle) {
	if (typeof fontStyle === 'number') {
		if (fontStyle <= 0) return []
		const styles = []
		if ((fontStyle & 1) !== 0) styles.push('italic')
		if ((fontStyle & 2) !== 0) styles.push('bold')
		if ((fontStyle & 4) !== 0) styles.push('underline')
		return styles
	}
	return String(fontStyle || '')
		.toLowerCase()
		.split(/\s+/)
		.filter(style => ALLOWED_FONT_STYLES.has(style))
}

export function safeAiCodeTokenStyle(token = {}) {
	const explicitRole = String(token.colorRole || '')
	const colorRole = Object.prototype.hasOwnProperty.call(AI_CODE_COLOR_ROLES, explicitRole)
		? explicitRole
		: COLOR_ROLE_BY_VALUE.get(String(token.color || '').toUpperCase()) || 'foreground'
	return {
		colorRole,
		fontStyles: normalizedFontStyles(token.fontStyle)
	}
}

export function buildSafeAiCodeLines(tokens = []) {
	const lines = [{ index: 0, tokens: [] }]
	for (const sourceToken of tokens || []) {
		const contentParts = String(sourceToken?.content || '').split('\n')
		for (let partIndex = 0; partIndex < contentParts.length; partIndex += 1) {
			const content = contentParts[partIndex]
			if (content) {
				const line = lines[lines.length - 1]
				line.tokens.push({
					index: line.tokens.length,
					content,
					...safeAiCodeTokenStyle(sourceToken)
				})
			}
			if (partIndex < contentParts.length - 1) {
				lines.push({ index: lines.length, tokens: [] })
			}
		}
	}
	return lines.length === 1 && lines[0].tokens.length === 0 ? [] : lines
}

export function buildPlainAiCodeLines(code) {
	return String(code || '').split('\n').map((content, index) => ({
		index,
		tokens: content
			? [{ index: 0, content, colorRole: 'foreground', fontStyles: [] }]
			: []
	}))
}
