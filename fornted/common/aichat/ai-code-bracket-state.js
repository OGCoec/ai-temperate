const OPEN_TO_CLOSE = Object.freeze({ '(': ')', '[': ']', '{': '}', '<': '>' })
const CLOSE_TO_OPEN = Object.freeze({ ')': '(', ']': '[', '}': '{', '>': '<' })
const BRACKET_ROLES = ['bracket-level-1', 'bracket-level-2', 'bracket-level-3']
const EXCLUDED_SCOPE_PARTS = ['comment', 'string', 'regexp', 'regex']
const ANGLE_SCOPE_PARTS = [
	'punctuation.definition.typeparameters',
	'entity.name.type.instance.jsdoc',
	'punctuation.definition.template',
	'meta.type.parameters',
	'meta.generic'
]

function tokenScopes(token) {
	const scopes = []
	for (const explanation of token?.explanation || []) {
		for (const scope of explanation?.scopes || []) {
			if (scope?.scopeName) scopes.push(String(scope.scopeName).toLowerCase())
		}
	}
	return scopes
}

function bracketAllowed(character, scopes) {
	if (scopes.some(scope => EXCLUDED_SCOPE_PARTS.some(part => scope.includes(part)))) return false
	if (character === '<' || character === '>') {
		return scopes.some(scope => ANGLE_SCOPE_PARTS.some(part => scope.includes(part)))
	}
	return true
}

function splitToken(token, stack) {
	const content = String(token?.content || '')
	const scopes = tokenScopes(token)
	if (![...content].some(character => (OPEN_TO_CLOSE[character] || CLOSE_TO_OPEN[character]) && bracketAllowed(character, scopes))) {
		return [{ ...token }]
	}

	const result = []
	let buffer = ''
	const flush = () => {
		if (!buffer) return
		result.push({ ...token, content: buffer })
		buffer = ''
	}
	for (const character of content) {
		if (!(OPEN_TO_CLOSE[character] || CLOSE_TO_OPEN[character]) || !bracketAllowed(character, scopes)) {
			buffer += character
			continue
		}
		flush()
		if (OPEN_TO_CLOSE[character]) {
			const colorRole = BRACKET_ROLES[stack.length % BRACKET_ROLES.length]
			stack.push({ opener: character, colorRole })
			result.push({ ...token, content: character, colorRole })
			continue
		}
		const top = stack[stack.length - 1]
		if (top?.opener === CLOSE_TO_OPEN[character]) {
			stack.pop()
			result.push({ ...token, content: character, colorRole: top.colorRole })
		} else {
			result.push({ ...token, content: character, colorRole: 'bracket-error' })
		}
	}
	flush()
	return result
}

function explanationSegments(token) {
	const explanations = Array.isArray(token?.explanation) ? token.explanation : []
	if (!explanations.length) return [token]
	const content = explanations.map(item => String(item?.content || '')).join('')
	if (content !== String(token?.content || '')) return [token]
	return explanations.map(explanation => ({
		...token,
		content: String(explanation?.content || ''),
		explanation: [explanation]
	}))
}

function colorize(tokens, stack) {
	return (tokens || []).flatMap(token =>
		explanationSegments(token).flatMap(segment => splitToken(segment, stack)))
}

export function createAiCodeBracketState() {
	let stableStack = []
	return {
		appendStable(tokens) {
			return colorize(tokens, stableStack)
		},
		replaceUnstable(tokens) {
			return colorize(tokens, stableStack.map(item => ({ ...item })))
		},
		closeUnstable(tokens) {
			return colorize(tokens, stableStack)
		},
		reset() {
			stableStack = []
		}
	}
}
