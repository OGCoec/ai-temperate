function protocolError(message) {
	const error = new Error(message)
	error.code = 'AI_CONVERSATION_SSE_PROTOCOL_INVALID'
	return error
}

const DEFAULT_MAXIMUM_EVENT_CHARACTERS = 48 * 1024 * 1024

export function createAiConversationSseParser(
	onEvent = () => {},
	{ maximumEventCharacters = DEFAULT_MAXIMUM_EVENT_CHARACTERS } = {}) {
	if (!Number.isSafeInteger(maximumEventCharacters)
		|| maximumEventCharacters < 1) {
		throw protocolError('会话事件大小边界无效。')
	}
	let buffer = ''
	let eventName = ''
	let dataLines = []
	let eventCharacters = 0

	function dispatch() {
		if (!dataLines.length) {
			eventName = ''
			eventCharacters = 0
			return
		}
		let data
		try {
			data = JSON.parse(dataLines.join('\n'))
		} catch (_) {
			throw protocolError('会话事件数据无效。')
		}
		onEvent(Object.freeze({ type: eventName || 'message', data }))
		eventName = ''
		dataLines = []
		eventCharacters = 0
	}

	function line(value) {
		if (value === '') return dispatch()
		if (value.startsWith(':')) return
		const separator = value.indexOf(':')
		const field = separator < 0 ? value : value.slice(0, separator)
		let content = separator < 0 ? '' : value.slice(separator + 1)
		if (content.startsWith(' ')) content = content.slice(1)
		if (field === 'event') eventName = content
		else if (field === 'data') {
			eventCharacters += content.length
			if (eventCharacters > maximumEventCharacters) {
				throw protocolError('会话事件超过浏览器内存边界。')
			}
			dataLines.push(content)
		}
	}

	return Object.freeze({
		push(chunk) {
			buffer += String(chunk || '')
			let newline = buffer.indexOf('\n')
			while (newline >= 0) {
				let current = buffer.slice(0, newline)
				if (current.endsWith('\r')) current = current.slice(0, -1)
				buffer = buffer.slice(newline + 1)
				line(current)
				newline = buffer.indexOf('\n')
			}
			if (buffer.length > maximumEventCharacters) {
				throw protocolError('会话事件超过浏览器内存边界。')
			}
		},
		finish() {
			if (buffer) line(buffer.endsWith('\r') ? buffer.slice(0, -1) : buffer)
			buffer = ''
			dispatch()
		}
	})
}
