// #ifdef MP-WEIXIN
import * as mpWeixinHighlighter from './ai-code-highlighter-mp-weixin.js'
// #endif
// #ifndef MP-WEIXIN
import * as shikiHighlighter from './ai-code-highlighter-shiki.js'
// #endif

let platformHighlighter
// #ifdef MP-WEIXIN
platformHighlighter = mpWeixinHighlighter
// #endif
// #ifndef MP-WEIXIN
platformHighlighter = shikiHighlighter
// #endif

// 调用方只依赖这一层稳定接口，平台条件编译必须在模块边界截断不兼容依赖。
export function resolveAiCodeLanguage(language) {
	return platformHighlighter.resolveAiCodeLanguage(language)
}

export async function prepareAiCodeHighlighterWithFallback(language, services = {}) {
	return platformHighlighter.prepareAiCodeHighlighterWithFallback(language, services)
}

export async function createAiCodeTokenizer(language) {
	return platformHighlighter.createAiCodeTokenizer(language)
}

export function prewarmAiCodeHighlighter(options = {}) {
	return platformHighlighter.prewarmAiCodeHighlighter(options)
}
