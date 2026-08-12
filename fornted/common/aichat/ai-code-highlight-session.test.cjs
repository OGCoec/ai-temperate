const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

function fakeTokenizerFactory(calls) {
	return async language => {
		calls.push(language.canonicalId || language.id)
		let unstable = []
		let unstableCode = ''
		return {
			language: {
				requestedId: language.id,
				canonicalId: language.id,
				label: language.label,
				supported: true
			},
			engine: 'fake',
			tokenizer: {
				async enqueue(chunk) {
					const previousRecall = unstable.length
					const parts = (unstableCode + chunk).split('\n')
					const stable = []
					for (const part of parts.slice(0, -1)) {
						stable.push({ content: part, color: '#569CD6' }, { content: '\n' })
					}
					unstableCode = parts[parts.length - 1]
					unstable = unstableCode
						? [{ content: unstableCode, color: '#9CDCFE' }]
						: []
					return { recall: previousRecall, stable, unstable }
				},
				close() {
					const stable = unstable
					unstable = []
					unstableCode = ''
					return { stable }
				}
			}
		}
	}
}

test('coalesces append-only updates and emits the latest tokens in one scheduled frame', async () => {
	const { createAiCodeHighlightSessionFactory } = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlight-session.js')
	)
	const tokenizerCalls = []
	const scheduled = []
	const snapshots = []
	const createSession = createAiCodeHighlightSessionFactory({
		createTokenizer: fakeTokenizerFactory(tokenizerCalls),
		languageTimeoutMs: 1000
	})
	const session = createSession({
		blockKey: 'message:0',
		language: { id: 'java', label: 'Java' },
		onSnapshot: snapshot => snapshots.push(snapshot),
		scheduleFrame: callback => scheduled.push(callback)
	})

	await Promise.all([
		session.update({ code: 'pub', previousCode: '', streaming: true }),
		session.update({ code: 'public\nvalue', previousCode: 'pub', streaming: true })
	])
	assert.equal(scheduled.length, 1)
	scheduled.shift()()

	assert.equal(tokenizerCalls.length, 1)
	assert.equal(snapshots.at(-1).status, 'ready')
	assert.equal(snapshots.at(-1).stableLines.length, 1)
	assert.equal(snapshots.at(-1).unstableLines.length, 1)
	assert.equal(
		snapshots.at(-1).lines.flatMap(line => line.tokens).map(token => token.content).join('\n'),
		'public\nvalue'
	)
	const stableLine = snapshots.at(-1).stableLines[0]

	await session.update({ code: 'public\nvalueMore', previousCode: 'public\nvalue', streaming: true })
	scheduled.shift()()
	assert.strictEqual(snapshots.at(-1).stableLines[0], stableLine)
})

test('increments revision for corrected snapshots and ignores the old tokenization result', async () => {
	const { createAiCodeHighlightSessionFactory } = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlight-session.js')
	)
	const createSession = createAiCodeHighlightSessionFactory({
		createTokenizer: fakeTokenizerFactory([]),
		languageTimeoutMs: 1000
	})
	const snapshots = []
	const session = createSession({
		blockKey: 'message:1',
		language: { id: 'java', label: 'Java' },
		onSnapshot: snapshot => snapshots.push(snapshot),
		scheduleFrame: callback => callback()
	})

	await session.update({ code: 'class Wrong', previousCode: '', streaming: true })
	const firstRevision = snapshots.at(-1).revision
	await session.update({ code: 'class Correct', previousCode: 'class Wrong', streaming: true })

	assert.ok(snapshots.at(-1).revision > firstRevision)
	assert.equal(
		snapshots.at(-1).lines.flatMap(line => line.tokens).map(token => token.content).join('\n'),
		'class Correct'
	)
})

test('discards in-flight tokens as soon as a non-prefix snapshot correction arrives', async () => {
	const { createAiCodeHighlightSessionFactory } = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlight-session.js')
	)
	let releaseFirstEnqueue
	let tokenizerNumber = 0
	const createSession = createAiCodeHighlightSessionFactory({
		createTokenizer: async language => {
			tokenizerNumber += 1
			const currentTokenizer = tokenizerNumber
			let unstable = []
			return {
				language: { requestedId: language.id, canonicalId: language.id, label: language.label, supported: true },
				tokenizer: {
					async enqueue(chunk) {
						if (currentTokenizer === 1) {
							await new Promise(resolve => { releaseFirstEnqueue = resolve })
						}
						unstable = [{ content: chunk, color: '#9CDCFE' }]
						return { recall: 0, stable: [], unstable }
					},
					close() { return { stable: unstable } }
				}
			}
		}
	})
	const snapshots = []
	const session = createSession({
		language: { id: 'java', label: 'Java' },
		onSnapshot: snapshot => snapshots.push(snapshot),
		scheduleFrame: callback => callback()
	})

	const first = session.update({ code: 'class Wrong', streaming: true })
	while (!releaseFirstEnqueue) await Promise.resolve()
	const corrected = session.update({ code: 'class Correct', streaming: true })
	releaseFirstEnqueue()
	await Promise.all([first, corrected])
	const correctedRevision = snapshots.at(-1).revision

	const visibleText = snapshots
		.filter(snapshot => snapshot.revision >= correctedRevision)
		.flatMap(snapshot => snapshot.lines)
		.flatMap(line => line.tokens)
		.map(token => token.content)
	assert.equal(visibleText.includes('class Wrong'), false)
	assert.equal(visibleText.at(-1), 'class Correct')
})

test('publishes an empty view when a streaming snapshot retracts all code', async () => {
	const { createAiCodeHighlightSessionFactory } = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlight-session.js')
	)
	const snapshots = []
	const session = createAiCodeHighlightSessionFactory({
		createTokenizer: fakeTokenizerFactory([])
	})({
		language: { id: 'java', label: 'Java' },
		onSnapshot: snapshot => snapshots.push(snapshot),
		scheduleFrame: callback => callback()
	})

	await session.update({ code: 'temporary', streaming: true })
	assert.equal(snapshots.at(-1).lines[0].tokens[0].content, 'temporary')
	await session.update({ code: '', previousCode: 'temporary', streaming: true })
	assert.deepEqual(snapshots.at(-1).lines, [])
})

test('keeps cold grammar text readable and recolors it when initialization finishes', async () => {
	const { createAiCodeHighlightSessionFactory } = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlight-session.js')
	)
	let resolveTokenizer
	const tokenizerPromise = new Promise(resolve => { resolveTokenizer = resolve })
	const snapshots = []
	const session = createAiCodeHighlightSessionFactory({
		createTokenizer: () => tokenizerPromise
	})({
		language: { id: 'cold-language', label: 'Cold language' },
		onSnapshot: snapshot => snapshots.push(snapshot),
		scheduleFrame: callback => callback()
	})

	const update = session.update({ code: 'first', streaming: true })
	await Promise.resolve()
	assert.equal(snapshots.at(-1).status, 'loading')
	assert.equal(snapshots.at(-1).lines[0].tokens[0].content, 'first')
	resolveTokenizer(await fakeTokenizerFactory([])({ id: 'cold-language', label: 'Cold language' }))
	await update
	assert.equal(snapshots.at(-1).status, 'ready')
	assert.equal(snapshots.at(-1).lines[0].tokens[0].content, 'first')
})

test('retries a transient highlighter failure on the next append', async () => {
	const { createAiCodeHighlightSessionFactory } = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlight-session.js')
	)
	let attempts = 0
	const snapshots = []
	const errors = []
	const session = createAiCodeHighlightSessionFactory({
		createTokenizer: async language => {
			attempts += 1
			if (attempts === 1) throw new Error('AI_CODE_ENGINE_INIT_FAILED')
			return fakeTokenizerFactory([])(language)
		}
	})({
		language: { id: 'java', label: 'Java' },
		onSnapshot: snapshot => snapshots.push(snapshot),
		onError: error => errors.push(error),
		scheduleFrame: callback => callback()
	})

	await session.update({ code: 'public', streaming: true })
	assert.equal(snapshots.at(-1).status, 'plain')
	assert.equal(errors[0].code, 'AI_CODE_ENGINE_INIT_FAILED')
	await session.update({ code: 'public class Main', streaming: true })
	assert.equal(attempts, 2)
	assert.equal(snapshots.at(-1).status, 'ready')
	assert.equal(snapshots.at(-1).lines[0].tokens[0].content, 'public class Main')
})

test('leaves indefinite loading as readable plain text and retries on the next append', async () => {
	const { createAiCodeHighlightSessionFactory } = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlight-session.js')
	)
	let attempts = 0
	const snapshots = []
	const errors = []
	const session = createAiCodeHighlightSessionFactory({
		languageTimeoutMs: 10,
		createTokenizer: language => {
			attempts += 1
			if (attempts === 1) return new Promise(() => {})
			return fakeTokenizerFactory([])(language)
		}
	})({
		language: { id: 'java', label: 'Java' },
		onSnapshot: snapshot => snapshots.push(snapshot),
		onError: error => errors.push(error),
		scheduleFrame: callback => callback()
	})

	const firstResult = await Promise.race([
		session.update({ code: 'public', streaming: true }).then(() => 'settled'),
		new Promise(resolve => setTimeout(() => resolve('still-loading'), 200))
	])
	assert.equal(firstResult, 'settled')
	assert.equal(snapshots.at(-1).status, 'plain')
	assert.equal(snapshots.at(-1).lines[0].tokens[0].content, 'public')
	assert.deepEqual(errors, [{
		code: 'AI_CODE_ENGINE_INIT_TIMEOUT',
		languageId: 'java',
		stage: 'TOKENIZER',
		elapsedMs: 10
	}])

	await session.update({ code: 'public class Main', streaming: true })
	assert.equal(attempts, 2)
	assert.equal(snapshots.at(-1).status, 'ready')
	assert.equal(snapshots.at(-1).lines[0].tokens[0].content, 'public class Main')
})

test('flushes an unfinished last line on complete and retains original text on failure', async () => {
	const { createAiCodeHighlightSessionFactory } = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlight-session.js')
	)
	const snapshots = []
	const createSession = createAiCodeHighlightSessionFactory({
		createTokenizer: async () => { throw new Error('engine unavailable') },
		languageTimeoutMs: 1000
	})
	const session = createSession({
		blockKey: 'message:2',
		language: { id: 'java', label: 'Java' },
		onSnapshot: snapshot => snapshots.push(snapshot),
		scheduleFrame: callback => callback()
	})

	await session.complete({ finalCode: 'public class Main {}' })
	assert.equal(snapshots.at(-1).status, 'plain')
	assert.equal(snapshots.at(-1).lines[0].tokens[0].content, 'public class Main {}')
})
