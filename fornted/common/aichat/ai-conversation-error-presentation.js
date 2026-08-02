const AI_CONVERSATION_ERROR_MESSAGES = Object.freeze({
	AI_QUOTA_INSUFFICIENT: '额度不足，请充值。'
})

const AI_CONVERSATION_STREAM_REASON_MESSAGES = Object.freeze({
	UPSTREAM_TOTAL_TIMEOUT: '模型响应超过最大允许时间',
	UPSTREAM_RATE_LIMITED: '上游模型当前受到限流',
	UPSTREAM_AUTH_UNAVAILABLE: '上游模型认证暂不可用',
	UPSTREAM_CONNECTION_CLOSED: '上游连接提前中断',
	UPSTREAM_NETWORK_ERROR: '上游网络通信失败',
	UPSTREAM_PROTOCOL_ERROR: '上游响应格式无法解析',
	UPSTREAM_SERVER_ERROR: '上游模型服务异常',
	USAGE_DATA_UNAVAILABLE: '上游未返回完整用量信息',
	STREAM_BACKPRESSURE_OVERFLOW: '服务端流式转发发生背压异常'
})

const AI_CONVERSATION_STREAM_ERROR_CODES = new Set([
	'AI_UPSTREAM_STREAM_FAILED',
	'AI_UPSTREAM_TIMEOUT',
	'AI_UPSTREAM_UNAVAILABLE',
	'AI_USAGE_UNAVAILABLE'
])

/**
 * 把后端稳定错误码转换为面向普通用户的文案，未知错误继续保留服务端可读消息。
 */
export function aiConversationErrorMessage(error, fallback = '模型响应中断。') {
	const code = String(error?.code || '').trim()
	if (Object.prototype.hasOwnProperty.call(AI_CONVERSATION_ERROR_MESSAGES, code)) {
		return AI_CONVERSATION_ERROR_MESSAGES[code]
	}
	if (AI_CONVERSATION_STREAM_ERROR_CODES.has(code)) {
		const reasonCode = String(error?.reasonCode || '').trim()
		if (Object.prototype.hasOwnProperty.call(
			AI_CONVERSATION_STREAM_REASON_MESSAGES,
			reasonCode
		)) {
			return `模型响应未能完成：${AI_CONVERSATION_STREAM_REASON_MESSAGES[reasonCode]}`
		}
		return '模型响应未能完成'
	}
	return String(error?.message || '').trim() || fallback
}
