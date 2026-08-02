import http from 'node:http'
import { pathToFileURL } from 'node:url'

const DEFAULT_PORT = 18317
const SCENARIOS = new Set([
	'ait-test-normal',
	'ait-test-slow',
	'ait-test-fail-before-first',
	'ait-test-fail-after-partial',
	'ait-test-no-usage'
])

function chunk(model, content, finishReason = null, usage = undefined) {
	return {
		id: 'chatcmpl-ait-isolated-test',
		object: 'chat.completion.chunk',
		created: Math.floor(Date.now() / 1000),
		model,
		choices: [{ index: 0, delta: content ? { content } : {}, finish_reason: finishReason }],
		...(usage ? { usage } : {})
	}
}

function writeEvent(response, value) {
	response.write(`data: ${JSON.stringify(value)}\n\n`)
}

function json(response, statusCode, body) {
	response.writeHead(statusCode, { 'content-type': 'application/json; charset=utf-8' })
	response.end(JSON.stringify(body))
}

async function body(request) {
	const chunks = []
	for await (const value of request) {
		chunks.push(value)
		if (chunks.reduce((total, item) => total + item.length, 0) > 256 * 1024) {
			throw new Error('request-too-large')
		}
	}
	return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

function streamHeaders(response) {
	response.writeHead(200, {
		'content-type': 'text/event-stream; charset=utf-8',
		'cache-control': 'no-cache',
		'connection': 'keep-alive'
	})
}

async function chatCompletions(request, response) {
	let requestBody
	try {
		requestBody = await body(request)
	} catch {
		json(response, 400, { error: { type: 'invalid_request_error', code: 'AIT_FAKE_INVALID_REQUEST' } })
		return
	}
	const model = typeof requestBody?.model === 'string' ? requestBody.model : ''
	if (!SCENARIOS.has(model)) {
		json(response, 400, { error: { type: 'invalid_request_error', code: 'AIT_FAKE_UNKNOWN_SCENARIO' } })
		return
	}
	if (model === 'ait-test-fail-before-first') {
		json(response, 503, { error: { type: 'server_error', code: 'AIT_FAKE_UPSTREAM_UNAVAILABLE' } })
		return
	}

	streamHeaders(response)
	writeEvent(response, chunk(model, '第一段'))
	if (model === 'ait-test-fail-after-partial') {
		setTimeout(() => response.destroy(new Error('controlled-partial-stream-failure')), 25)
		return
	}
	const delay = model === 'ait-test-slow'
		? Number.parseInt(process.env.AIT_FAKE_OPENAI_CHUNK_DELAY_MS || '1000', 10)
		: 10
	setTimeout(() => {
		writeEvent(response, chunk(model, '第二段'))
		writeEvent(response, chunk(
			model,
			'',
			'STOP',
			model === 'ait-test-no-usage'
				? undefined
				: { prompt_tokens: 12, completion_tokens: 6, total_tokens: 18 }
		))
		response.end('data: [DONE]\n\n')
	}, Math.max(1, Math.min(delay, 60_000)))
}

/**
 * 创建不记录提示词或回答正文的 OpenAI Chat Completions 假服务，供隔离端到端测试控制终态。
 */
export function createFakeOpenAiServer() {
	return http.createServer((request, response) => {
		if (request.method === 'GET' && request.url === '/health') {
			json(response, 200, { status: 'UP' })
			return
		}
		if (request.method === 'POST' && request.url === '/v1/chat/completions') {
			void chatCompletions(request, response)
			return
		}
		json(response, 404, { error: { code: 'AIT_FAKE_NOT_FOUND' } })
	})
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
	const port = Number.parseInt(process.env.AIT_FAKE_OPENAI_PORT || String(DEFAULT_PORT), 10)
	createFakeOpenAiServer().listen(port, '127.0.0.1', () => {
		process.stdout.write(`ait_fake_openai_ready port=${port}\n`)
	})
}
