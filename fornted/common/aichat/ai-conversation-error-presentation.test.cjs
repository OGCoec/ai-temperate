const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

async function loadPresentation() {
	const source = fs.readFileSync(
		path.join(__dirname, 'ai-conversation-error-presentation.js'),
		'utf8'
	)
	const url = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(`${url}#${Date.now()}-${Math.random()}`)
}

test('maps insufficient quota to the stable recharge message', async () => {
	const presentation = await loadPresentation()

	assert.equal(
		presentation.aiConversationErrorMessage({
			code: 'AI_QUOTA_INSUFFICIENT',
			message: '内部诊断不应覆盖稳定文案'
		}),
		'额度不足，请充值。'
	)
})

test('preserves readable unknown errors and falls back when empty', async () => {
	const presentation = await loadPresentation()

	assert.equal(
		presentation.aiConversationErrorMessage({ message: '上游暂时不可用。' }),
		'上游暂时不可用。'
	)
	assert.equal(
		presentation.aiConversationErrorMessage({}, '请求失败。'),
		'请求失败。'
	)
})

test('maps provider compatibility errors to explicit safe messages', async () => {
	const presentation = await loadPresentation()

	assert.equal(presentation.aiConversationErrorMessage({
		code: 'AI_MODEL_REASONING_LEVEL_UNSUPPORTED'
	}), '当前模型不支持所选推理档位。')
	assert.equal(presentation.aiConversationErrorMessage({
		code: 'AI_IMAGE_RESOLUTION_UNSUPPORTED'
	}), '当前模型不支持所选图片分辨率。')
	assert.equal(presentation.aiConversationErrorMessage({
		code: 'AI_PROVIDER_TOOL_UNSUPPORTED'
	}), '当前模型不支持所选联网搜索配置。')
})

test('maps safe stream reason codes without exposing arbitrary server messages', async () => {
	const presentation = await loadPresentation()
	const reasons = {
		UPSTREAM_TOTAL_TIMEOUT: '模型响应未能完成：模型响应超过最大允许时间',
		UPSTREAM_RATE_LIMITED: '模型响应未能完成：上游模型当前受到限流',
		UPSTREAM_AUTH_UNAVAILABLE: '模型响应未能完成：上游模型认证暂不可用',
		UPSTREAM_CONNECTION_CLOSED: '模型响应未能完成：上游连接提前中断',
		UPSTREAM_NETWORK_ERROR: '模型响应未能完成：上游网络通信失败',
		UPSTREAM_PROTOCOL_ERROR: '模型响应未能完成：上游响应格式无法解析',
		UPSTREAM_REASONING_LEVEL_UNSUPPORTED: '模型响应未能完成：当前模型不支持所选推理档位',
		UPSTREAM_IMAGE_RESOLUTION_UNSUPPORTED: '模型响应未能完成：当前模型不支持所选图片分辨率',
		UPSTREAM_TOOL_CONFIGURATION_UNSUPPORTED: '模型响应未能完成：当前模型不支持所选工具配置',
		UPSTREAM_SERVER_ERROR: '模型响应未能完成：上游模型服务异常',
		USAGE_DATA_UNAVAILABLE: '模型响应未能完成：上游未返回完整用量信息',
		STREAM_BACKPRESSURE_OVERFLOW: '模型响应未能完成：服务端流式转发发生背压异常'
	}

	for (const [reasonCode, expected] of Object.entries(reasons)) {
		assert.equal(
			presentation.aiConversationErrorMessage({
				code: 'AI_UPSTREAM_STREAM_FAILED',
				reasonCode,
				message: 'https://proxy.invalid?api_key=secret'
			}),
			expected
		)
	}

	assert.equal(
		presentation.aiConversationErrorMessage({
			code: 'AI_UPSTREAM_STREAM_FAILED',
			reasonCode: 'UNTRUSTED_REASON',
			message: 'provider secret body'
		}),
		'模型响应未能完成'
	)
})
