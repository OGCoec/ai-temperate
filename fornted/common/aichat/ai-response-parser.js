import { parseAiJsonDocument } from './ai-json-presentation.js'
import { parseAiMarkdown } from './ai-markdown-parser.js'
import { parseAiXmlDocument } from './ai-xml-presentation.js'

const MAX_STRUCTURED_CHARACTERS = 1024 * 1024
const MARKDOWN_FENCE = /^(?:`{3,}|~{3,})/
const XML_PREFIX = /^(?:<\?xml\b|<!--|<!DOCTYPE\b|<\?[A-Za-z_:][A-Za-z0-9_.:-]*(?:\s|\?>)|<[A-Za-z_:][A-Za-z0-9_.:-]*(?:\s|\/?>))/i

function significantText(value) {
	return String(value ?? '').replace(/^\uFEFF/, '').trimStart()
}

function rawCodeDocument(format, text, streaming) {
	return {
		type: 'document',
		children: [{
			type: 'codeBlock',
			language: {
				id: format,
				label: format === 'json' ? 'JSON' : 'XML'
			},
			code: String(text ?? ''),
			streaming: streaming === true
		}]
	}
}

export function detectAiResponseFormatCandidate(text) {
	const value = significantText(text)
	if (!value || MARKDOWN_FENCE.test(value)) return 'markdown'
	if (value.startsWith('{') || value.startsWith('[')) return 'json'
	if (XML_PREFIX.test(value)) return 'xml'
	return 'markdown'
}

export function parseAiResponse(text, options = {}) {
	const value = text == null ? '' : String(text)
	const format = detectAiResponseFormatCandidate(value)
	if (format === 'markdown') return parseAiMarkdown(value, options)
	if (options.streaming === true || value.length > MAX_STRUCTURED_CHARACTERS) {
		return rawCodeDocument(format, value, options.streaming)
	}
	const result = format === 'json'
		? parseAiJsonDocument(value)
		: parseAiXmlDocument(value)
	if (result.ok) return result.ast
	if (result.reason === 'LIMIT' || result.reason === 'UNSAFE') {
		return rawCodeDocument(format, value, false)
	}
	return parseAiMarkdown(value, options)
}
