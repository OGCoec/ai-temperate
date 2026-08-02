const DEFAULT_MAXIMUM_FRAME_BYTES = 1024 * 1024

function utf8Bytes(value) {
	return Buffer.byteLength(value, 'utf8')
}

/**
 * 解析 OpenAI 兼容 SSE 帧并只返回事件类型、字节数和终态标志，不向调用方暴露 data 正文。
 */
export function createDiagnosticSseFrameParser(onFrame, options = {}) {
	if (typeof onFrame !== 'function') {
		throw new TypeError('onFrame must be a function')
	}
	const maximumFrameBytes = Math.max(
		256,
		Number(options.maximumFrameBytes || DEFAULT_MAXIMUM_FRAME_BYTES)
	)
	let buffer = ''
	let eventName = ''
	let dataLines = []
	let dataBytes = 0

	function resetFrame() {
		eventName = ''
		dataLines = []
		dataBytes = 0
	}

	function dispatch() {
		if (!dataLines.length) {
			resetFrame()
			return
		}
		const joined = dataLines.join('\n')
		onFrame(Object.freeze({
			eventType: eventName || 'message',
			dataBytes,
			terminal: joined.trim() === '[DONE]'
		}))
		resetFrame()
	}

	function line(value) {
		if (value === '') {
			dispatch()
			return
		}
		if (value.startsWith(':')) return
		const separator = value.indexOf(':')
		const field = separator < 0 ? value : value.slice(0, separator)
		let content = separator < 0 ? '' : value.slice(separator + 1)
		if (content.startsWith(' ')) content = content.slice(1)
		if (field === 'event') {
			eventName = content.slice(0, 64)
			return
		}
		if (field !== 'data') return
		dataBytes += utf8Bytes(content) + (dataLines.length ? 1 : 0)
		if (dataBytes > maximumFrameBytes) {
			throw new Error('SSE frame exceeds diagnostic parser limit')
		}
		dataLines.push(content)
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
			if (utf8Bytes(buffer) > maximumFrameBytes) {
				throw new Error('SSE frame exceeds diagnostic parser limit')
			}
		},
		finish() {
			if (buffer) line(buffer.endsWith('\r')
				? buffer.slice(0, -1)
				: buffer)
			buffer = ''
			dispatch()
		}
	})
}
