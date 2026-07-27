function contentType(headers) {
	if (!headers || typeof headers !== 'object') return ''
	let value = ''
	for (const [name, headerValue] of Object.entries(headers)) {
		if (String(name).toLowerCase() === 'content-type') value = headerValue
	}
	return String(value || '')
		.split(';', 1)[0]
		.trim()
		.toLowerCase()
}

export function serializeStructuredJsonRequestBody(data, headers = {}) {
	if (data === null || data === undefined || typeof data === 'string') return data
	const mediaType = contentType(headers)
	const prefix = 'application/'
	const subtype = mediaType.startsWith(prefix)
		? mediaType.slice(prefix.length)
		: ''
	if (subtype.length <= '+json'.length || !subtype.endsWith('+json')) return data

	// uni.request 对自定义 +json 媒体类型不会稳定地序列化对象，必须在传输边界显式生成 JSON 文本。
	return JSON.stringify(data)
}
