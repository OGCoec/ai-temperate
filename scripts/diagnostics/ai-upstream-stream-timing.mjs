import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { performance } from 'node:perf_hooks'
import { createDiagnosticSseFrameParser } from './ai-upstream-stream-parser.mjs'

function requiredEnvironment(name) {
	const value = process.env[name]?.trim()
	if (!value) throw new Error(`Required environment is missing: ${name}`)
	return value
}

function chatCompletionsUrl(baseUrl) {
	const normalized = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`
	return new URL('chat/completions', normalized).toString()
}

function safeExceptionType(error) {
	const name = error?.constructor?.name
	return /^[A-Za-z0-9_$.-]{1,128}$/.test(String(name || ''))
		? name
		: 'Error'
}

function safeEventType(value) {
	const normalized = String(value || 'message')
	return /^[A-Za-z0-9_.-]{1,64}$/.test(normalized)
		? normalized
		: 'unknown'
}

function rounded(value) {
	return Math.max(0, Math.round(value * 1000) / 1000)
}

function optionalRounded(value) {
	return value < 0 ? -1 : rounded(value)
}

async function closeStream(stream) {
	await new Promise((resolve, reject) => {
		stream.once('error', reject)
		stream.end(resolve)
	})
}

async function main() {
	const baseUrl = requiredEnvironment('AI_INFERENCE_CLI_PROXY_BASE_URL')
	const apiKey = requiredEnvironment('CLI_PROXY_API_KEY')
	const model = requiredEnvironment('AI_DIAGNOSTIC_MODEL')
	const requestFile = path.resolve(requiredEnvironment(
		'AI_DIAGNOSTIC_REQUEST_FILE'))
	const reasoningEffort = process.env.AI_DIAGNOSTIC_REASONING_EFFORT?.trim()
	const parsedRequest = JSON.parse(fs.readFileSync(requestFile, 'utf8'))
	if (!parsedRequest || typeof parsedRequest !== 'object'
		|| !Array.isArray(parsedRequest.messages)) {
		throw new Error('Diagnostic request file must contain a messages array')
	}
	// 只在内存中补充流式协议字段；请求正文不会写入控制台或诊断 JSONL。
	const request = {
		...parsedRequest,
		model,
		stream: true,
		stream_options: {
			...(parsedRequest.stream_options || {}),
			include_usage: true
		}
	}
	if (reasoningEffort) request.reasoning_effort = reasoningEffort

	const outputDirectory = path.join(
		os.tmpdir(), 'ai-temperate-stream-diagnostics')
	fs.mkdirSync(outputDirectory, { recursive: true })
	const outputFile = path.join(
		outputDirectory,
		`cli-proxy-${Date.now()}-${process.pid}.jsonl`
	)
	const output = fs.createWriteStream(outputFile, {
		encoding: 'utf8',
		flags: 'wx'
	})
	const startedAt = performance.now()
	let lastNetworkAt = startedAt
	let lastFrameAt = startedAt
	let firstByteMs = -1
	let firstFrameMs = -1
	let maximumNetworkGapMs = 0
	let maximumFrameGapMs = 0
	let totalBytes = 0
	let totalNetworkChunks = 0
	let totalFrames = 0
	let terminalFrameSeen = false
	let networkWindowStartedAt = startedAt
	let networkWindowChunks = 0
	let networkWindowBytes = 0
	let frameWindowStartedAt = startedAt
	let frameWindowCount = 0
	let frameWindowBytes = 0

	function emit(entry) {
		const line = `${JSON.stringify(entry)}\n`
		output.write(line)
		process.stdout.write(line)
	}

	function flushNetworkWindow(timestamp, terminal = false) {
		if (!networkWindowChunks) return
		emit({
			event: 'ai_upstream_network_window',
			elapsedMs: rounded(timestamp - startedAt),
			windowMs: rounded(timestamp - networkWindowStartedAt),
			chunkCount: networkWindowChunks,
			byteCount: networkWindowBytes,
			terminal
		})
		networkWindowStartedAt = timestamp
		networkWindowChunks = 0
		networkWindowBytes = 0
	}

	function flushFrameWindow(timestamp, terminal = false) {
		if (!frameWindowCount) return
		emit({
			event: 'ai_upstream_sse_window',
			elapsedMs: rounded(timestamp - startedAt),
			windowMs: rounded(timestamp - frameWindowStartedAt),
			frameCount: frameWindowCount,
			dataBytes: frameWindowBytes,
			terminal
		})
		frameWindowStartedAt = timestamp
		frameWindowCount = 0
		frameWindowBytes = 0
	}

	const parser = createDiagnosticSseFrameParser(frame => {
		const timestamp = performance.now()
		const gap = timestamp - lastFrameAt
		lastFrameAt = timestamp
		maximumFrameGapMs = Math.max(maximumFrameGapMs, gap)
		totalFrames += 1
		frameWindowCount += 1
		frameWindowBytes += frame.dataBytes
		terminalFrameSeen ||= frame.terminal
		if (firstFrameMs < 0) {
			firstFrameMs = timestamp - startedAt
			emit({
				event: 'ai_upstream_first_sse_frame',
				elapsedMs: rounded(firstFrameMs),
				eventType: safeEventType(frame.eventType),
				dataBytes: frame.dataBytes
			})
		}
		if (timestamp - frameWindowStartedAt >= 1000) {
			flushFrameWindow(timestamp)
		}
	})

	try {
		const response = await fetch(chatCompletionsUrl(baseUrl), {
			method: 'POST',
			headers: {
				Accept: 'text/event-stream',
				Authorization: `Bearer ${apiKey}`,
				'Content-Type': 'application/json'
			},
			body: JSON.stringify(request),
			cache: 'no-store'
		})
		const contentType = response.headers.get('content-type') || ''
		emit({
			event: 'ai_upstream_response_headers',
			elapsedMs: rounded(performance.now() - startedAt),
			statusCode: response.status,
			eventStream: contentType.toLowerCase().includes('text/event-stream')
		})
		if (!response.ok) {
			const error = new Error('CLIProxyAPI returned a non-success status')
			error.statusCode = response.status
			throw error
		}
		if (!contentType.toLowerCase().includes('text/event-stream')) {
			throw new Error('CLIProxyAPI did not return an event stream')
		}
		const reader = response.body?.getReader?.()
		if (!reader) throw new Error('Streaming response body is unavailable')
		const decoder = new TextDecoder('utf-8', { fatal: true })
		try {
			while (true) {
				const next = await reader.read()
				if (next.done) break
				const timestamp = performance.now()
				const bytes = next.value?.byteLength || 0
				if (firstByteMs < 0 && bytes > 0) {
					firstByteMs = timestamp - startedAt
					emit({
						event: 'ai_upstream_first_byte',
						elapsedMs: rounded(firstByteMs),
						byteCount: bytes
					})
				}
				maximumNetworkGapMs = Math.max(
					maximumNetworkGapMs, timestamp - lastNetworkAt)
				lastNetworkAt = timestamp
				totalBytes += bytes
				totalNetworkChunks += 1
				networkWindowChunks += 1
				networkWindowBytes += bytes
				parser.push(decoder.decode(next.value, { stream: true }))
				if (timestamp - networkWindowStartedAt >= 1000) {
					flushNetworkWindow(timestamp)
				}
			}
			parser.push(decoder.decode())
			parser.finish()
		} finally {
			reader.releaseLock()
		}
		const completedAt = performance.now()
		flushNetworkWindow(completedAt, true)
		flushFrameWindow(completedAt, true)
		emit({
			event: 'ai_upstream_stream_summary',
			outcome: terminalFrameSeen ? 'COMPLETE' : 'CLOSED_WITHOUT_TERMINAL',
			elapsedMs: rounded(completedAt - startedAt),
			firstByteMs: optionalRounded(firstByteMs),
			firstFrameMs: optionalRounded(firstFrameMs),
			totalNetworkChunks,
			totalBytes,
			totalFrames,
			maximumNetworkGapMs: rounded(maximumNetworkGapMs),
			maximumFrameGapMs: rounded(maximumFrameGapMs),
			terminalFrameSeen,
			outputFile
		})
	} catch (error) {
		emit({
			event: 'ai_upstream_stream_error',
			elapsedMs: rounded(performance.now() - startedAt),
			exceptionType: safeExceptionType(error),
			statusCode: Number.isInteger(error?.statusCode)
				? error.statusCode : null,
			outputFile
		})
		process.exitCode = 1
	} finally {
		await closeStream(output)
	}
}

main().catch(error => {
	process.stderr.write(`${JSON.stringify({
		event: 'ai_upstream_diagnostic_start_failed',
		exceptionType: safeExceptionType(error)
	})}\n`)
	process.exitCode = 1
})
