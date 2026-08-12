import MarkdownIt from 'markdown-it'
import { parseAbsoluteHttpUrl } from '../platform/http-url.js'

const LANGUAGE_LABELS = new Map([
	['c++', 'C++'],
	['csharp', 'C#'],
	['cs', 'C#'],
	['css', 'CSS'],
	['go', 'Go'],
	['html', 'HTML'],
	['java', 'Java'],
	['javascript', 'JavaScript'],
	['js', 'JavaScript'],
	['json', 'JSON'],
	['kotlin', 'Kotlin'],
	['node', 'Node.js'],
	['php', 'PHP'],
	['python', 'Python'],
	['py', 'Python'],
	['rust', 'Rust'],
	['shell', 'Shell'],
	['sh', 'Shell'],
	['sql', 'SQL'],
	['swift', 'Swift'],
	['typescript', 'TypeScript'],
	['ts', 'TypeScript'],
	['vue', 'Vue'],
	['xml', 'XML'],
	['yaml', 'YAML'],
	['yml', 'YAML']
])

const MARKDOWN_IT_OPTIONS = Object.freeze({
	html: false,
	breaks: false,
	linkify: false,
	typographer: false
})

function textNode(value) {
	return { type: 'text', value: String(value || '') }
}

function appendChild(parent, child) {
	if (!child) return
	if (!parent.children) parent.children = []
	if (child.type === 'text' && !child.value) return
	const previous = parent.children[parent.children.length - 1]
	if (previous?.type === 'text' && child.type === 'text') {
		previous.value += child.value
		return
	}
	parent.children.push(child)
}

function appendChildren(parent, children) {
	for (const child of children || []) appendChild(parent, child)
}

function attributeValue(token, name) {
	for (const pair of token.attrs || []) {
		if (pair[0] === name) return pair[1]
	}
	return ''
}

function normalizeLanguage(info) {
	const raw = String(info || '').trim().split(/\s+/)[0].toLowerCase()
	if (!raw) return { id: 'plain', label: 'Plain text' }
	const id = raw.replace(/[^a-z0-9+#.-]/g, '').slice(0, 64)
	if (!id) return { id: 'plain', label: 'Plain text' }
	return {
		id,
		label: LANGUAGE_LABELS.get(id) || id
	}
}

function sanitizeUrl(value) {
	const href = String(value || '')
		.replace(/[\u0000-\u001f\u007f]/g, '')
		.trim()
	if (!href) return { href: '', safe: false }
	if (href.startsWith('/') || href.startsWith('./') || href.startsWith('../') || href.startsWith('#')) {
		return { href, safe: true }
	}
	const parsed = parseAbsoluteHttpUrl(href)
	if (!parsed && /^mailto:[^\s<>]+$/i.test(href)) {
		return { href, safe: true }
	}
	return {
		href: parsed?.href || href,
		safe: Boolean(parsed)
	}
}

function createBlockNode(token) {
	switch (token.type) {
		case 'paragraph_open':
			return { type: 'paragraph', children: [] }
		case 'heading_open':
			return {
				type: 'heading',
				level: Number(String(token.tag || 'h1').slice(1)) || 1,
				children: []
			}
		case 'bullet_list_open':
			return { type: 'unorderedList', children: [] }
		case 'ordered_list_open':
			return {
				type: 'orderedList',
				start: Number(attributeValue(token, 'start')) || 1,
				children: []
			}
		case 'list_item_open':
			return { type: 'listItem', children: [] }
		case 'blockquote_open':
			return { type: 'blockquote', children: [] }
		case 'table_open':
			return { type: 'table', children: [] }
		case 'thead_open':
			return { type: 'tableHead', children: [] }
		case 'tbody_open':
			return { type: 'tableBody', children: [] }
		case 'tr_open':
			return { type: 'tableRow', children: [] }
		case 'th_open':
			return {
				type: 'tableCell',
				header: true,
				alignment: alignmentFromToken(token),
				children: []
			}
		case 'td_open':
			return {
				type: 'tableCell',
				header: false,
				alignment: alignmentFromToken(token),
				children: []
			}
		default:
			return null
	}
}

function alignmentFromToken(token) {
	const style = attributeValue(token, 'style').toLowerCase()
	if (style.includes('text-align:center')) return 'center'
	if (style.includes('text-align:right')) return 'right'
	if (style.includes('text-align:left')) return 'left'
	return null
}

function parseInlineTokens(tokens) {
	const root = { children: [] }
	const stack = [root]
	for (const token of tokens || []) {
		const current = stack[stack.length - 1]
		if (token.nesting === 1) {
			let node = null
			if (token.type === 'strong_open') node = { type: 'strong', children: [] }
			if (token.type === 'em_open') node = { type: 'emphasis', children: [] }
			if (token.type === 's_open') node = { type: 'deletion', children: [] }
			if (token.type === 'link_open') {
				const link = sanitizeUrl(attributeValue(token, 'href'))
				node = { type: 'link', href: link.href, safe: link.safe, children: [] }
			}
			if (node) {
				appendChild(current, node)
				stack.push(node)
			}
			continue
		}
		if (token.nesting === -1) {
			if (stack.length > 1) stack.pop()
			continue
		}
		if (token.type === 'text') appendChild(current, textNode(token.content))
		else if (token.type === 'code_inline') {
			appendChild(current, { type: 'inlineCode', value: token.content || '' })
		} else if (token.type === 'softbreak' || token.type === 'hardbreak') {
			appendChild(current, textNode('\n'))
		} else if (token.type === 'html_inline') {
			appendChild(current, textNode(token.content || ''))
		} else if (token.type === 'image') {
			appendChild(current, textNode(attributeValue(token, 'alt') || token.content || ''))
		} else if (token.content) {
			appendChild(current, textNode(token.content))
		}
	}
	return normalizeInlineLinks(root.children)
}

function normalizeInlineLinks(children) {
	const normalized = []
	for (const child of children || []) {
		if (child.children) child.children = normalizeInlineLinks(child.children)
		if (child.type === 'link' && !child.safe) {
			appendChildren({ children: normalized }, child.children)
			continue
		}
		appendChild({ children: normalized }, child)
	}
	return normalized
}

function tableRows(section) {
	return (section?.children || [])
		.filter(row => row.type === 'tableRow')
		.map(row => row.children.filter(cell => cell.type === 'tableCell'))
}

function emptyTableCell(header) {
	return {
		type: 'tableCell',
		header,
		alignment: null,
		children: []
	}
}

function normalizeTableRow(row, width, header) {
	const cells = row.map(normalizeNode)
	while (cells.length < width) {
		cells.push(emptyTableCell(header))
	}
	return cells
}

function normalizeTable(node) {
	const head = node.children.find(child => child.type === 'tableHead')
	const body = node.children.find(child => child.type === 'tableBody')
	const headers = tableRows(head)[0] || []
	const rows = tableRows(body)
	const alignments = headers.map(cell => cell.alignment || null)
	const width = Math.max(headers.length, ...rows.map(row => row.length), alignments.length, 0)
	while (alignments.length < width) alignments.push(null)
	return {
		type: 'table',
		headers: normalizeTableRow(headers, width, true),
		rows: rows.map(row => normalizeTableRow(row, width, false)),
		alignments
	}
}

function normalizeTaskItem(node) {
	const first = node.children?.[0]
	const firstText = first?.type === 'paragraph' ? first.children?.[0] : null
	if (firstText?.type !== 'text') return node
	const match = firstText.value.match(/^\[([ xX])\]\s+/)
	if (!match) return node
	firstText.value = firstText.value.slice(match[0].length)
	return {
		type: 'taskItem',
		checked: match[1].toLowerCase() === 'x',
		children: node.children
	}
}

function normalizeNode(node) {
	if (!node) return null
	if (node.type === 'table') return normalizeTable(node)
	if (node.type === 'listItem') return normalizeTaskItem({
		...node,
		children: (node.children || []).map(normalizeNode).filter(Boolean)
	})
	if (node.children) {
		return {
			...node,
			children: node.children.map(normalizeNode).filter(Boolean)
		}
	}
	return node
}

function buildAst(tokens, runtimeOptions = {}) {
	const root = { type: 'document', children: [] }
	const stack = [root]
	for (const token of tokens || []) {
		const current = stack[stack.length - 1]
		if (token.type === 'inline') {
			appendChildren(current, parseInlineTokens(token.children || []))
			continue
		}
		if (token.nesting === 1) {
			const node = createBlockNode(token)
			if (node) {
				appendChild(current, node)
				stack.push(node)
			}
			continue
		}
		if (token.nesting === -1) {
			if (stack.length > 1) stack.pop()
			continue
		}
		if (token.type === 'fence' || token.type === 'code_block') {
			appendChild(current, {
				type: 'codeBlock',
				language: normalizeLanguage(token.info),
				code: token.content || '',
				streaming: runtimeOptions.streaming === true
			})
			continue
		}
		if (token.type === 'hr') {
			appendChild(current, { type: 'thematicBreak' })
			continue
		}
		if (token.type === 'html_block') {
			appendChild(current, textNode(token.content || ''))
			continue
		}
		if (token.content) appendChild(current, textNode(token.content))
	}
	return {
		type: 'document',
		children: root.children.map(normalizeNode).filter(Boolean)
	}
}

export function createAiMarkdownParser(options = {}) {
	const requestedOptions = options.markdownOptions || {}
	const markdown = options.markdown || new MarkdownIt({
		...MARKDOWN_IT_OPTIONS,
		breaks: requestedOptions.breaks === true
	})
	return {
		parse(text, runtimeOptions = {}) {
			const value = text == null ? '' : String(text)
			if (!value) return { type: 'document', children: [] }
			try {
				return buildAst(markdown.parse(value, {}), runtimeOptions)
			} catch {
				return {
					type: 'document',
					children: [textNode(value)]
				}
			}
		}
	}
}

const defaultParser = createAiMarkdownParser()

export function parseAiMarkdown(text, options = {}) {
	if (options && (options.markdown || options.markdownOptions)) {
		return createAiMarkdownParser(options).parse(text, options)
	}
	return defaultParser.parse(text, options)
}

export { normalizeLanguage, sanitizeUrl }
