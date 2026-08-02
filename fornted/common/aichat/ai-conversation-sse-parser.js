function protocolError(message) {
	const error = new Error(message)
	error.code = 'AI_CONVERSATION_SSE_PROTOCOL_INVALID'
	return error
}

export function createAiConversationSseParser(onEvent = () => {}) {
	let buffer = ''
	let eventName = ''
	let dataLines = []

	function dispatch() {
		if (!dataLines.length) {
			eventName = ''
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
	}

	function line(value) {
		if (value === '') return dispatch()
		if (value.startsWith(':')) return
		const separator = value.indexOf(':')
		const field = separator < 0 ? value : value.slice(0, separator)
		let content = separator < 0 ? '' : value.slice(separator + 1)
		if (content.startsWith(' ')) content = content.slice(1)
		if (field === 'event') eventName = content
		else if (field === 'data') dataLines.push(content)
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
		},
		finish() {
			if (buffer) line(buffer.endsWith('\r') ? buffer.slice(0, -1) : buffer)
			buffer = ''
			dispatch()
		}
	})
}
