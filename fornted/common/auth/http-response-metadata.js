/**
 * 只把业务需要的 ETag 暴露给调用方，避免响应中的续签凭据和边缘头越过认证客户端边界。
 */
export function captureEtagPayload(data, headers = {}) {
	const entry = Object.entries(headers || {})
		.find(([name]) => String(name).toLowerCase() === 'etag')
	return Object.freeze({
		data,
		etag: entry && typeof entry[1] === 'string' ? entry[1] : ''
	})
}
