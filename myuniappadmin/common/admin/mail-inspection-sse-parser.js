function parserError(message) {
	const error = new Error(message)
	error.code = 'MAIL_INSPECTION_SSE_PROTOCOL_INVALID'
	return error
}

export function createMailInspectionSseParser(options = {}) {
	const onEvent = typeof options.onEvent === 'function' ? options.onEvent : () => {}
	let buffer = ''
	let eventName = ''
	let eventId = ''
	let dataLines = []

	function dispatch() {
		if (!dataLines.length) {
			eventName = ''
			eventId = ''
			return
		}
		const raw = dataLines.join('\n')
		let data
		try {
			data = JSON.parse(raw)
		} catch (_) {
			throw parserError('实时事件 JSON 无效。')
		}
		onEvent(Object.freeze({
			type: eventName || 'message',
			id: eventId,
			data
		}))
		eventName = ''
		eventId = ''
		dataLines = []
	}

	function consumeLine(line) {
		if (line === '') {
			dispatch()
			return
		}
		if (line.startsWith(':')) return
		const separator = line.indexOf(':')
		const field = separator < 0 ? line : line.slice(0, separator)
		let value = separator < 0 ? '' : line.slice(separator + 1)
		if (value.startsWith(' ')) value = value.slice(1)
		if (field === 'event') eventName = value
		else if (field === 'id' && !value.includes('\u0000')) eventId = value
		else if (field === 'data') dataLines.push(value)
	}

	return Object.freeze({
		push(chunk) {
			buffer += String(chunk || '')
			let newline = buffer.indexOf('\n')
			while (newline >= 0) {
				let line = buffer.slice(0, newline)
				if (line.endsWith('\r')) line = line.slice(0, -1)
				buffer = buffer.slice(newline + 1)
				consumeLine(line)
				newline = buffer.indexOf('\n')
			}
		},
		finish() {
			if (buffer) consumeLine(buffer.endsWith('\r') ? buffer.slice(0, -1) : buffer)
			buffer = ''
			dispatch()
		}
	})
}
