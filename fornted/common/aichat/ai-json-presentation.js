import * as losslessJsonModule from 'lossless-json'

const losslessJson = losslessJsonModule.default || losslessJsonModule
const { isLosslessNumber, parse: parseLosslessJson } = losslessJson

const MAX_DEPTH = 64
const MAX_NODES = 5000
const MAX_STRUCTURED_CHARACTERS = 1024 * 1024
const MIN_TABLE_ROWS = 2
const MAX_TABLE_ROWS = 50
const MAX_TABLE_COLUMNS = 12

class AiJsonLimitError extends Error {}

function jsonStringEnd(source, start) {
	let escaped = false
	for (let index = start + 1; index < source.length; index += 1) {
		const character = source[index]
		if (escaped) {
			escaped = false
			continue
		}
		if (character === '\\') escaped = true
		else if (character === '"') return index
	}
	return -1
}

function protectPrototypeKeys(source) {
	const keys = new Set()
	const protectedSpans = []
	for (let index = 0; index < source.length; index += 1) {
		if (source[index] !== '"') continue
		const end = jsonStringEnd(source, index)
		if (end < 0) break
		let cursor = end + 1
		while (/\s/.test(source[cursor] || '')) cursor += 1
		if (source[cursor] === ':') {
			try {
				const key = JSON.parse(source.slice(index, end + 1))
				keys.add(key)
				if (key === '__proto__') protectedSpans.push([index, end + 1])
			} catch (_) {}
		}
		index = end
	}
	if (!protectedSpans.length) return { source, protectedKey: '' }
	let protectedKey = '__ai_temperate_prototype_key__'
	while (keys.has(protectedKey)) protectedKey += '_'
	let transformed = ''
	let cursor = 0
	for (const [start, end] of protectedSpans) {
		transformed += source.slice(cursor, start) + JSON.stringify(protectedKey)
		cursor = end
	}
	transformed += source.slice(cursor)
	return { source: transformed, protectedKey }
}

function textNode(value) {
	return { type: 'text', value: String(value ?? '') }
}

function inlineCodeNode(value) {
	return { type: 'inlineCode', value: String(value ?? '') }
}

function paragraph(children) {
	return { type: 'paragraph', children }
}

function isContainer(value) {
	return Array.isArray(value)
		|| (value !== null && typeof value === 'object' && !isLosslessNumber(value))
}

function isEmptyContainer(value) {
	if (Array.isArray(value)) return value.length === 0
	return isContainer(value) && Object.keys(value).length === 0
}

function isSimpleValue(value) {
	return !isContainer(value)
}

function simpleInlineNode(value) {
	if (typeof value === 'string') return textNode(value)
	if (value === null) return inlineCodeNode('null')
	if (isLosslessNumber(value)) return inlineCodeNode(value.toString())
	return inlineCodeNode(String(value))
}

function emptyContainerInlineNode(value) {
	return inlineCodeNode(Array.isArray(value) ? '[]' : '{}')
}

function touch(context, depth) {
	if (depth > MAX_DEPTH) throw new AiJsonLimitError('JSON depth limit exceeded')
	context.nodes += 1
	if (context.nodes > MAX_NODES) throw new AiJsonLimitError('JSON node limit exceeded')
}

function tableColumns(value) {
	if (!Array.isArray(value)
		|| value.length < MIN_TABLE_ROWS
		|| value.length > MAX_TABLE_ROWS) return null
	const first = value[0]
	if (!first || Array.isArray(first) || typeof first !== 'object'
		|| isLosslessNumber(first)) return null
	const columns = Object.keys(first)
	if (!columns.length || columns.length > MAX_TABLE_COLUMNS) return null
	for (const row of value) {
		if (!row || Array.isArray(row) || typeof row !== 'object'
			|| isLosslessNumber(row)) return null
		const rowColumns = Object.keys(row)
		if (rowColumns.length !== columns.length
			|| rowColumns.some((column, index) => column !== columns[index])) return null
		if (columns.some(column => !isSimpleValue(row[column]))) return null
	}
	return columns
}

function tableCell(value, header = false) {
	return {
		type: 'tableCell',
		header,
		alignment: null,
		children: [simpleInlineNode(value)]
	}
}

function arrayTableNode(value, columns, depth, context) {
	const rows = value.map(row => {
		touch(context, depth + 1)
		return columns.map(column => {
			touch(context, depth + 2)
			return tableCell(row[column])
		})
	})
	return {
		type: 'table',
		headers: columns.map(column => tableCell(
			column === context.protectedKey ? '__proto__' : column,
			true)),
		rows,
		alignments: columns.map(() => null)
	}
}

function propertyNode(key, value, depth, context) {
	const label = { type: 'strong', children: [textNode(key)] }
	if (isSimpleValue(value)) {
		touch(context, depth)
		return {
			type: 'listItem',
			children: [paragraph([label, textNode('：'), simpleInlineNode(value)])]
		}
	}
	if (isEmptyContainer(value)) {
		touch(context, depth)
		return {
			type: 'listItem',
			children: [paragraph([label, textNode('：'), emptyContainerInlineNode(value)])]
		}
	}
	return {
		type: 'listItem',
		children: [paragraph([label]), valueBlockNode(value, depth, context)]
	}
}

function valueBlockNode(value, depth, context) {
	touch(context, depth)
	if (!isContainer(value)) return paragraph([simpleInlineNode(value)])
	if (isEmptyContainer(value)) return paragraph([emptyContainerInlineNode(value)])
	if (Array.isArray(value)) {
		const columns = tableColumns(value)
		if (columns) return arrayTableNode(value, columns, depth, context)
		return {
			type: 'orderedList',
			start: 1,
			children: value.map(item => ({
				type: 'listItem',
				children: [valueBlockNode(item, depth + 1, context)]
			}))
		}
	}
	return {
		type: 'unorderedList',
		children: Object.entries(value).map(([key, item]) =>
			propertyNode(
				key === context.protectedKey ? '__proto__' : key,
				item,
				depth + 1,
				context))
	}
}

export function parseAiJsonDocument(text) {
	try {
		const source = String(text ?? '')
		if (source.length > MAX_STRUCTURED_CHARACTERS) {
			return { ok: false, reason: 'LIMIT' }
		}
		const protectedDocument = protectPrototypeKeys(source)
		const value = parseLosslessJson(protectedDocument.source)
		const ast = {
			type: 'document',
			children: [valueBlockNode(value, 0, {
				nodes: 0,
				protectedKey: protectedDocument.protectedKey
			})]
		}
		return { ok: true, ast }
	} catch (error) {
		return {
			ok: false,
			reason: error instanceof AiJsonLimitError ? 'LIMIT' : 'INVALID'
		}
	}
}
