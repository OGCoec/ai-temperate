export const AI_MARKDOWN_TABLE_COLUMN_WIDTH_STEPS = Object.freeze([112, 160, 224, 320])

function cellChildren(cell) {
	return Array.isArray(cell?.children) ? cell.children : []
}

export function aiMarkdownTableCellText(cell) {
	if (!cell) return ''
	if (cell.type === 'text' || cell.type === 'inlineCode') return String(cell.value || '')
	return cellChildren(cell).map(aiMarkdownTableCellText).join('')
}

function isZeroWidthCodePoint(codePoint) {
	return (codePoint >= 0x0300 && codePoint <= 0x036f) ||
		(codePoint >= 0x1ab0 && codePoint <= 0x1aff) ||
		(codePoint >= 0x1dc0 && codePoint <= 0x1dff) ||
		(codePoint >= 0x20d0 && codePoint <= 0x20ff) ||
		(codePoint >= 0xfe00 && codePoint <= 0xfe0f) ||
		(codePoint >= 0xfe20 && codePoint <= 0xfe2f) ||
		(codePoint >= 0xe0020 && codePoint <= 0xe007f)
}

function isEmojiModifier(codePoint) {
	return codePoint >= 0x1f3fb && codePoint <= 0x1f3ff
}

function isRegionalIndicator(codePoint) {
	return codePoint >= 0x1f1e6 && codePoint <= 0x1f1ff
}

function isEmojiCodePoint(codePoint) {
	return codePoint === 0x00a9 ||
		codePoint === 0x00ae ||
		codePoint === 0x203c ||
		codePoint === 0x2049 ||
		codePoint === 0x2122 ||
		codePoint === 0x2139 ||
		(codePoint >= 0x2194 && codePoint <= 0x21ff) ||
		(codePoint >= 0x2300 && codePoint <= 0x23ff) ||
		(codePoint >= 0x25a0 && codePoint <= 0x27bf) ||
		(codePoint >= 0x2b00 && codePoint <= 0x2bff) ||
		codePoint === 0x3030 ||
		codePoint === 0x303d ||
		codePoint === 0x3297 ||
		codePoint === 0x3299 ||
		(codePoint >= 0x1f000 && codePoint <= 0x1faff)
}

function isWideCodePoint(codePoint) {
	return codePoint >= 0x1100 && (
		codePoint <= 0x115f ||
		codePoint === 0x2329 ||
		codePoint === 0x232a ||
		(codePoint >= 0x2e80 && codePoint <= 0xa4cf && codePoint !== 0x303f) ||
		(codePoint >= 0xac00 && codePoint <= 0xd7a3) ||
		(codePoint >= 0xf900 && codePoint <= 0xfaff) ||
		(codePoint >= 0xfe10 && codePoint <= 0xfe6f) ||
		(codePoint >= 0xff00 && codePoint <= 0xff60) ||
		(codePoint >= 0xffe0 && codePoint <= 0xffe6) ||
		(codePoint >= 0x20000 && codePoint <= 0x3fffd)
	)
}

export function aiMarkdownDisplayUnits(value) {
	const characters = Array.from(String(value || ''))
	let units = 0
	let joinNextEmoji = false
	let regionalIndicatorOpen = false
	for (let index = 0; index < characters.length; index += 1) {
		const codePoint = characters[index].codePointAt(0)
		const nextCodePoint = characters[index + 1]?.codePointAt(0)
		const followingCodePoint = characters[index + 2]?.codePointAt(0)
		const keycapBase = codePoint === 0x23 || codePoint === 0x2a ||
			(codePoint >= 0x30 && codePoint <= 0x39)
		if (keycapBase && (nextCodePoint === 0x20e3 ||
			(nextCodePoint === 0xfe0f && followingCodePoint === 0x20e3))) {
			units += 2
			index += nextCodePoint === 0xfe0f ? 2 : 1
			joinNextEmoji = false
			regionalIndicatorOpen = false
			continue
		}
		if (codePoint === 0x200d) {
			joinNextEmoji = true
			continue
		}
		if (isZeroWidthCodePoint(codePoint) || isEmojiModifier(codePoint)) continue
		if (isRegionalIndicator(codePoint)) {
			if (!regionalIndicatorOpen) units += 2
			regionalIndicatorOpen = !regionalIndicatorOpen
			joinNextEmoji = false
			continue
		}
		regionalIndicatorOpen = false
		if (isEmojiCodePoint(codePoint)) {
			if (!joinNextEmoji) units += 2
			joinNextEmoji = false
			continue
		}
		joinNextEmoji = false
		units += isWideCodePoint(codePoint) ? 2 : 1
	}
	return units
}

function containsInlineCode(cell) {
	if (!cell) return false
	if (cell.type === 'inlineCode') return true
	return cellChildren(cell).some(containsInlineCode)
}

function looksLikeUrl(value) {
	const text = String(value || '').trim()
	return /(?:https?:\/\/|mailto:|www\.|\/\/)[^\s<>()]+/i.test(text) ||
		/(?:^|[^A-Za-z0-9/])(?:\/(?!\/)|\.\.?\/|#)[^\s<>()]+/i.test(text)
}

function isApiIdentifierToken(text) {
	if (/^(?:asst|chat|file|msg|org|proj|req|resp|run|thread|usr)[_-][A-Za-z0-9_-]+$/i.test(text)) {
		return true
	}
	if (text.length >= 8 && /[A-Za-z]/.test(text) &&
		/^[A-Za-z0-9]+(?:[-_.:/][A-Za-z0-9]+)+$/.test(text)) {
		return true
	}
	return text.length >= 12 &&
		/[A-Za-z]/.test(text) &&
		/\d/.test(text) &&
		/^[A-Za-z0-9]+$/.test(text)
}

function looksLikeApiIdentifier(value) {
	const tokens = String(value || '').match(/[A-Za-z0-9][A-Za-z0-9_.:/-]*/g) || []
	return tokens.some(isApiIdentifierToken)
}

export function aiMarkdownTableCellNeedsTokenBreak(cell) {
	const text = aiMarkdownTableCellText(cell).trim()
	return containsInlineCode(cell) || looksLikeUrl(text) || looksLikeApiIdentifier(text)
}

function isShortNumericValue(value) {
	return /^[+-]?(?:[$¥€£]\s*)?\d[\d,.]*(?:%|[KMBT]|ms|s|KB|MB|GB|TB)?$/i
		.test(String(value || '').trim())
}

function widthForDisplayUnits(units) {
	const widths = AI_MARKDOWN_TABLE_COLUMN_WIDTH_STEPS
	if (units <= 8) return widths[0]
	if (units <= 16) return widths[1]
	if (units <= 28) return widths[2]
	return widths[3]
}

function columnProfile(headers, rows, alignments, index) {
	const cells = [
		headers[index],
		...rows.map(row => Array.isArray(row) ? row[index] : null)
	].filter(Boolean)
	const texts = cells.map(cell => aiMarkdownTableCellText(cell).trim())
	const bodyTexts = rows
		.map(row => Array.isArray(row) ? aiMarkdownTableCellText(row[index]).trim() : '')
		.filter(Boolean)
	const maximumUnits = texts.reduce(
		(maximum, text) => Math.max(maximum, aiMarkdownDisplayUnits(text)),
		0
	)
	const numericMaximum = bodyTexts.reduce(
		(maximum, text) => Math.max(maximum, aiMarkdownDisplayUnits(text)),
		0
	)
	const numeric = bodyTexts.length > 0 &&
		bodyTexts.every(isShortNumericValue) &&
		numericMaximum <= 12
	const breakable = cells.some(aiMarkdownTableCellNeedsTokenBreak)
	let width = numeric
		? AI_MARKDOWN_TABLE_COLUMN_WIDTH_STEPS[0]
		: widthForDisplayUnits(maximumUnits)
	if (breakable) width = Math.max(width, AI_MARKDOWN_TABLE_COLUMN_WIDTH_STEPS[2])
	return {
		width,
		numeric,
		breakable,
		alignment: alignments[index] || (numeric ? 'right' : 'left')
	}
}

export function createAiMarkdownTableLayout(headers = [], rows = [], alignments = []) {
	const safeHeaders = Array.isArray(headers) ? headers : []
	const safeRows = Array.isArray(rows) ? rows : []
	const safeAlignments = Array.isArray(alignments) ? alignments : []
	const columnCount = Math.max(
		safeHeaders.length,
		safeAlignments.length,
		...safeRows.map(row => Array.isArray(row) ? row.length : 0),
		0
	)
	const columnProfiles = Array.from(
		{ length: columnCount },
		(_, index) => columnProfile(safeHeaders, safeRows, safeAlignments, index)
	)
	return {
		columnProfiles,
		tableMinWidth: columnProfiles.reduce((total, profile) => total + profile.width, 0)
	}
}

export function aiMarkdownTableAsTsv(headers = [], rows = []) {
	const safeHeaders = Array.isArray(headers) ? headers : []
	const safeRows = Array.isArray(rows) ? rows : []
	const columnCount = Math.max(
		safeHeaders.length,
		...safeRows.map(row => Array.isArray(row) ? row.length : 0),
		0
	)
	const encodeField = value => {
		const normalized = String(value || '').replace(/\r\n?/g, '\n')
		return /[\t\n"]/.test(normalized)
			? '"' + normalized.replace(/"/g, '""') + '"'
			: normalized
	}
	const line = cells => Array.from(
		{ length: columnCount },
		(_, index) => encodeField(
			aiMarkdownTableCellText(Array.isArray(cells) ? cells[index] : null)
		)
	).join('\t')
	return [line(safeHeaders), ...safeRows.map(line)].join('\n')
}
