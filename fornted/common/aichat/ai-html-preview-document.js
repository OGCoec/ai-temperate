export const AI_HTML_PREVIEW_CSP = [
	"default-src 'none'",
	"script-src 'unsafe-inline'",
	"style-src 'unsafe-inline'",
	'img-src data: blob:',
	'font-src data:',
	'media-src data: blob:',
	"connect-src 'none'",
	"object-src 'none'",
	"frame-src 'none'",
	"child-src 'none'",
	"worker-src 'none'",
	"form-action 'none'",
	"base-uri 'none'"
].join('; ')

const HTML_PREVIEW_LANGUAGE_IDS = new Set(['html', 'htm'])
const SECURITY_HEAD = [
	'<meta charset="utf-8">',
	`<meta http-equiv="Content-Security-Policy" content="${AI_HTML_PREVIEW_CSP}">`,
	'<meta name="referrer" content="no-referrer">',
	'<meta name="viewport" content="width=device-width,initial-scale=1">'
].join('')

function requestedLanguageId(language) {
	const value = typeof language === 'object' && language
		? language.canonicalId || language.id || language.requestedId
		: language
	return String(value || '').trim().toLowerCase()
}

export function isAiHtmlPreviewLanguage(language) {
	return HTML_PREVIEW_LANGUAGE_IDS.has(requestedLanguageId(language))
}

export function createAiHtmlPreviewDocument(code) {
	const source = String(code || '').replace(/^\uFEFF/, '')
	if (/<head(?:\s[^>]*)?>/i.test(source)) {
		return source.replace(/<head(?:\s[^>]*)?>/i, match => match + SECURITY_HEAD)
	}
	if (/<html(?:\s[^>]*)?>/i.test(source)) {
		return source.replace(/<html(?:\s[^>]*)?>/i, match => match + '<head>' + SECURITY_HEAD + '</head>')
	}
	return '<!doctype html><html><head>' + SECURITY_HEAD + '</head><body>' + source + '</body></html>'
}
