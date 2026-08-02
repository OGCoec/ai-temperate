import { performance } from 'node:perf_hooks'
import { pathToFileURL } from 'node:url'

function requiredEnvironment(name) {
	const value = process.env[name]?.trim()
	if (!value) throw new Error(`Required environment is missing: ${name}`)
	return value
}

export function loopbackResponsesUrl(value) {
	const baseUrl = new URL(value)
	const hostname = baseUrl.hostname.toLowerCase()
	if (!['127.0.0.1', '::1', 'localhost'].includes(hostname)
		|| !['http:', 'https:'].includes(baseUrl.protocol)
		|| baseUrl.username || baseUrl.password) {
		throw new Error('Responses probe only accepts a loopback base URL')
	}
	return new URL('/v1/responses', baseUrl).toString()
}

function safeEventType(value) {
	const normalized = String(value || 'message')
	return /^[A-Za-z0-9_.-]{1,96}$/.test(normalized)
		? normalized : 'unknown'
}

function frames() {
	let buffer = ''
	return {
		push(value) {
			buffer += value.replaceAll('\r\n', '\n')
			const result = []
			while (true) {
				const boundary = buffer.indexOf('\n\n')
				if (boundary < 0) break
				const block = buffer.slice(0, boundary)
				buffer = buffer.slice(boundary + 2)
				let eventType = 'message'
				const data = []
				for (const line of block.split('\n')) {
					if (line.startsWith('event:')) eventType = line.slice(6).trim()
					if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
				}
				if (data.length) result.push({ eventType, data: data.join('\n') })
			}
			return result
		},
		finish() {
			if (buffer.trim()) throw new Error('Responses SSE ended with an incomplete frame')
		}
	}
}

function inspectNode(node, summary) {
	const serialized = JSON.stringify(node)
	summary.queryObserved ||= /"query"\s*:/.test(serialized)
	summary.sourcesObserved ||= /"sources"\s*:/.test(serialized)
	summary.reasoningSummaryObserved ||=
		/"summary"\s*:|reasoning_summary/.test(serialized)
	if (node?.response?.usage || node?.usage) summary.usageObserved = true
	const candidates = []
	function visit(value, depth = 0) {
		if (!value || typeof value !== 'object' || depth > 10) return
		if (typeof value.url === 'string') candidates.push(value.url)
		if (Array.isArray(value)) value.forEach(item => visit(item, depth + 1))
		else Object.values(value).forEach(item => visit(item, depth + 1))
	}
	visit(node)
	for (const candidate of candidates.slice(0, 100)) {
		try {
			const url = new URL(candidate)
			if (['http:', 'https:'].includes(url.protocol)) {
				summary.sourceDomains.add(url.hostname.toLowerCase())
			}
		} catch (_) {}
	}
}

async function main() {
	const url = loopbackResponsesUrl(requiredEnvironment(
		'AI_INFERENCE_CLI_PROXY_BASE_URL'))
	const apiKey = requiredEnvironment('CLI_PROXY_API_KEY')
	const model = requiredEnvironment('AI_DIAGNOSTIC_MODEL')
	const mode = String(process.env.AI_DIAGNOSTIC_WEB_SEARCH_MODE || 'AUTO')
		.toUpperCase()
	if (!['AUTO', 'REQUIRED'].includes(mode)) {
		throw new Error('AI_DIAGNOSTIC_WEB_SEARCH_MODE must be AUTO or REQUIRED')
	}
	const request = {
		model,
		stream: true,
		store: false,
		max_output_tokens: 128,
		input: 'Find one current public source about the OpenAI API and answer briefly.',
		tools: [{ type: 'web_search', search_context_size: 'low' }],
		tool_choice: mode === 'REQUIRED' ? { type: 'web_search' } : 'auto',
		include: ['web_search_call.action.sources'],
		reasoning: { effort: 'low', summary: 'auto' }
	}
	const startedAt = performance.now()
	const summary = {
		eventTypes: new Map(),
		queryObserved: false,
		sourcesObserved: false,
		reasoningSummaryObserved: false,
		usageObserved: false,
		sourceDomains: new Set()
	}
	const response = await fetch(url, {
		method: 'POST',
		headers: {
			Accept: 'text/event-stream',
			Authorization: `Bearer ${apiKey}`,
			'Content-Type': 'application/json'
		},
		body: JSON.stringify(request),
		cache: 'no-store',
		signal: AbortSignal.timeout(120000)
	})
	const eventStream = String(response.headers.get('content-type') || '')
		.toLowerCase().includes('text/event-stream')
	process.stdout.write(`${JSON.stringify({
		event: 'ai_responses_probe_headers',
		statusCode: response.status,
		eventStream,
		elapsedMs: Math.round(performance.now() - startedAt)
	})}\n`)
	if (!response.ok || !eventStream) {
		throw new Error('CLIProxyAPI did not accept the Responses SSE probe')
	}
	const reader = response.body?.getReader?.()
	if (!reader) throw new Error('Responses probe stream is unavailable')
	const decoder = new TextDecoder('utf-8', { fatal: true })
	const parser = frames()
	while (true) {
		const next = await reader.read()
		if (next.done) break
		for (const frame of parser.push(decoder.decode(next.value, { stream: true }))) {
			const eventType = safeEventType(frame.eventType)
			summary.eventTypes.set(eventType,
				(summary.eventTypes.get(eventType) || 0) + 1)
			if (frame.data === '[DONE]') continue
			inspectNode(JSON.parse(frame.data), summary)
		}
	}
	for (const frame of parser.push(decoder.decode())) {
		const eventType = safeEventType(frame.eventType)
		summary.eventTypes.set(eventType,
			(summary.eventTypes.get(eventType) || 0) + 1)
		if (frame.data !== '[DONE]') inspectNode(JSON.parse(frame.data), summary)
	}
	parser.finish()
	process.stdout.write(`${JSON.stringify({
		event: 'ai_responses_probe_summary',
		elapsedMs: Math.round(performance.now() - startedAt),
		eventTypes: Object.fromEntries(summary.eventTypes),
		queryObserved: summary.queryObserved,
		sourcesObserved: summary.sourcesObserved,
		reasoningSummaryObserved: summary.reasoningSummaryObserved,
		usageObserved: summary.usageObserved,
		sourceDomainCount: summary.sourceDomains.size
	})}\n`)
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
	main().catch(error => {
		process.stderr.write(`${JSON.stringify({
			event: 'ai_responses_probe_failed',
			exceptionType: String(error?.constructor?.name || 'Error').slice(0, 128)
		})}\n`)
		process.exitCode = 1
	})
}
