/**
 * Android app-service 只需要增量分词能力，因此在本地保留不依赖浏览器流 API 的最小实现。
 */
export class AppShikiStreamTokenizer {
	constructor(options) {
		this.options = options
		this.tokensStable = []
		this.tokensUnstable = []
		this.lastUnstableCodeChunk = ''
		this.lastStableGrammarState = undefined
	}

	async enqueue(chunk) {
		const chunkLines = (this.lastUnstableCodeChunk + chunk).split('\n')
		const stable = []
		let unstable = []
		const recall = this.tokensUnstable.length

		for (let index = 0; index < chunkLines.length; index += 1) {
			const line = chunkLines[index]
			const isLastLine = index === chunkLines.length - 1
			const result = this.options.highlighter.codeToTokens(line, {
				...this.options,
				grammarState: this.lastStableGrammarState
			})
			const tokens = result.tokens[0]
			if (!isLastLine) tokens.push({ content: '\n', offset: 0 })
			if (isLastLine) {
				unstable = tokens
				this.lastUnstableCodeChunk = line
			} else {
				this.lastStableGrammarState = result.grammarState
				stable.push(...tokens)
			}
		}

		this.tokensStable.push(...stable)
		this.tokensUnstable = unstable
		return { recall, stable, unstable }
	}

	close() {
		const stable = this.tokensUnstable
		this.tokensUnstable = []
		this.lastUnstableCodeChunk = ''
		this.lastStableGrammarState = undefined
		return { stable }
	}

	clear() {
		this.tokensStable = []
		this.tokensUnstable = []
		this.lastUnstableCodeChunk = ''
		this.lastStableGrammarState = undefined
	}

	clone() {
		const clone = new AppShikiStreamTokenizer(this.options)
		clone.lastUnstableCodeChunk = this.lastUnstableCodeChunk
		clone.tokensUnstable = this.tokensUnstable.slice()
		clone.tokensStable = this.tokensStable.slice()
		clone.lastStableGrammarState = this.lastStableGrammarState
		return clone
	}
}
