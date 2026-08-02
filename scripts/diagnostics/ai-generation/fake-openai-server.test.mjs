import assert from 'node:assert/strict'
import { once } from 'node:events'
import http from 'node:http'
import test from 'node:test'
import { createFakeOpenAiServer } from './fake-openai-server.mjs'

async function withServer(action) {
	const server = createFakeOpenAiServer()
	server.listen(0, '127.0.0.1')
	await once(server, 'listening')
	try {
		await action(server.address().port)
	} finally {
		server.close()
		await once(server, 'close')
	}
}

function request(port, model) {
	return fetch(`http://127.0.0.1:${port}/v1/chat/completions`, {
		method: 'POST',
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({ model, stream: true, messages: [{ role: 'user', content: 'not-logged' }] })
	})
}

test('正常场景返回两个片段、最终 Usage 和 DONE', async () => {
	await withServer(async port => {
		const response = await request(port, 'ait-test-normal')
		const text = await response.text()
		assert.equal(response.status, 200)
		assert.match(text, /第一段/)
		assert.match(text, /第二段/)
		assert.match(text, /"prompt_tokens":12/)
		assert.match(text, /data: \[DONE\]/)
	})
})

test('无 Usage 场景完成但不伪造 Usage', async () => {
	await withServer(async port => {
		const response = await request(port, 'ait-test-no-usage')
		const text = await response.text()
		assert.equal(response.status, 200)
		assert.doesNotMatch(text, /prompt_tokens/)
		assert.match(text, /"finish_reason":"STOP"/)
	})
})

test('首片前失败返回受控 503', async () => {
	await withServer(async port => {
		const response = await request(port, 'ait-test-fail-before-first')
		assert.equal(response.status, 503)
		assert.equal((await response.json()).error.code, 'AIT_FAKE_UPSTREAM_UNAVAILABLE')
	})
})

test('部分输出后失败会提前关闭流且不发送 DONE', async () => {
	await withServer(async port => {
		const outcome = await new Promise((resolve, reject) => {
			const payload = JSON.stringify({ model: 'ait-test-fail-after-partial', stream: true, messages: [] })
			const request = http.request({
				host: '127.0.0.1',
				port,
				path: '/v1/chat/completions',
				method: 'POST',
				headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(payload) }
			}, response => {
				let received = ''
				response.on('data', value => { received += value.toString('utf8') })
				response.on('aborted', () => resolve(received))
				response.on('end', () => resolve(received))
			})
			request.on('error', reject)
			request.end(payload)
		})
		assert.match(outcome, /第一段/)
		assert.doesNotMatch(outcome, /\[DONE\]/)
	})
})
