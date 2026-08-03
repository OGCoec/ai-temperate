import { SaxesParser } from 'saxes'

const MAX_DEPTH = 64
const MAX_NODES = 5000
const MAX_STRUCTURED_CHARACTERS = 1024 * 1024

class AiXmlLimitError extends Error {}
class AiXmlUnsafeError extends Error {}

function textNode(value) {
	return { type: 'text', value: String(value ?? '') }
}

function inlineCodeNode(value) {
	return { type: 'inlineCode', value: String(value ?? '') }
}

function paragraph(children) {
	return { type: 'paragraph', children }
}

function touch(context) {
	context.nodes += 1
	if (context.nodes > MAX_NODES) throw new AiXmlLimitError('XML node limit exceeded')
}

function appendXmlChild(parent, child) {
	if (!parent || !child) return
	const previous = parent.children[parent.children.length - 1]
	if (child.kind === 'text' && previous?.kind === 'text') {
		previous.value += child.value
		return
	}
	parent.children.push(child)
}

function xmlDeclarationText(declaration) {
	const attributes = []
	if (declaration?.version) attributes.push(`version="${declaration.version}"`)
	if (declaration?.encoding) attributes.push(`encoding="${declaration.encoding}"`)
	if (declaration?.standalone) attributes.push(`standalone="${declaration.standalone}"`)
	return `<?xml${attributes.length ? ' ' + attributes.join(' ') : ''}?>`
}

function processingInstructionText(instruction) {
	const target = String(instruction?.target || '')
	const body = String(instruction?.body || '')
	return `<?${target}${body ? ' ' + body : ''}?>`
}

function parseXmlTree(text) {
	const context = { nodes: 0 }
	const documentChildren = []
	const stack = []
	let rootCount = 0
	const parser = new SaxesParser({ xmlns: true, fragment: false })

	parser.on('error', error => { throw error })
	parser.on('doctype', () => {
		throw new AiXmlUnsafeError('XML doctype is not allowed')
	})
	parser.on('xmldecl', declaration => {
		touch(context)
		documentChildren.push({ kind: 'code', value: xmlDeclarationText(declaration) })
	})
	parser.on('processinginstruction', instruction => {
		touch(context)
		const node = { kind: 'code', value: processingInstructionText(instruction) }
		if (stack.length) appendXmlChild(stack[stack.length - 1], node)
		else documentChildren.push(node)
	})
	parser.on('comment', comment => {
		touch(context)
		const node = { kind: 'code', value: `<!--${comment}-->` }
		if (stack.length) appendXmlChild(stack[stack.length - 1], node)
		else documentChildren.push(node)
	})
	parser.on('text', value => {
		if (!String(value).trim()) return
		if (!stack.length) throw new Error('XML text must be inside the root element')
		touch(context)
		appendXmlChild(stack[stack.length - 1], { kind: 'text', value: String(value) })
	})
	parser.on('cdata', value => {
		if (!stack.length) throw new Error('XML CDATA must be inside the root element')
		touch(context)
		appendXmlChild(stack[stack.length - 1], { kind: 'text', value: String(value) })
	})
	parser.on('opentag', tag => {
		const depth = stack.length + 1
		if (depth > MAX_DEPTH) throw new AiXmlLimitError('XML depth limit exceeded')
		touch(context)
		const element = {
			kind: 'element',
			name: String(tag.name || ''),
			attributes: Object.values(tag.attributes || {}).map(attribute => ({
				name: String(attribute?.name || ''),
				value: String(attribute?.value || '')
			})),
			children: []
		}
		for (const attribute of element.attributes) touch(context)
		if (stack.length) appendXmlChild(stack[stack.length - 1], element)
		else {
			rootCount += 1
			documentChildren.push(element)
		}
		stack.push(element)
	})
	parser.on('closetag', () => {
		stack.pop()
	})

	parser.write(String(text ?? '')).close()
	if (rootCount !== 1 || stack.length) throw new Error('XML must contain one root element')
	return documentChildren
}

function attributeListItem(attribute) {
	return {
		type: 'listItem',
		children: [paragraph([
			{ type: 'strong', children: [textNode('@' + attribute.name)] },
			textNode('：'),
			textNode(attribute.value)
		])]
	}
}

function xmlElementListItem(element) {
	const children = [paragraph([inlineCodeNode(element.name)])]
	if (element.attributes.length) {
		children.push({
			type: 'unorderedList',
			children: element.attributes.map(attributeListItem)
		})
	}
	for (const child of element.children) {
		if (child.kind === 'element') {
			children.push({
				type: 'unorderedList',
				children: [xmlElementListItem(child)]
			})
		} else if (child.kind === 'code') {
			children.push(paragraph([inlineCodeNode(child.value)]))
		} else {
			children.push(paragraph([textNode(child.value)]))
		}
	}
	return { type: 'listItem', children }
}

function xmlTreeToAst(nodes) {
	const children = []
	for (const node of nodes) {
		if (node.kind === 'element') {
			children.push({
				type: 'unorderedList',
				children: [xmlElementListItem(node)]
			})
		} else {
			children.push(paragraph([inlineCodeNode(node.value)]))
		}
	}
	return { type: 'document', children }
}

export function parseAiXmlDocument(text) {
	try {
		const source = String(text ?? '')
		if (source.length > MAX_STRUCTURED_CHARACTERS) {
			return { ok: false, reason: 'LIMIT' }
		}
		return { ok: true, ast: xmlTreeToAst(parseXmlTree(source)) }
	} catch (error) {
		if (error instanceof AiXmlUnsafeError) return { ok: false, reason: 'UNSAFE' }
		if (error instanceof AiXmlLimitError) return { ok: false, reason: 'LIMIT' }
		return { ok: false, reason: 'INVALID' }
	}
}
