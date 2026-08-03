import {
	AI_HTML_PREVIEW_PROTOCOL_VERSION,
	AI_HTML_PREVIEW_RUNTIME_SOURCE
} from './message-protocol.js'

export const AI_HTML_PREVIEW_RUNTIME_CSP = [
	'default-src https: data: blob:',
	"script-src https: data: blob: 'unsafe-inline' 'unsafe-eval'",
	"style-src https: 'unsafe-inline'",
	'img-src https: data: blob:',
	'font-src https: data:',
	'media-src https: data: blob:',
	'connect-src https: wss:',
	'worker-src https: blob:',
	'frame-src https: data: blob:',
	'child-src https: data: blob:',
	"object-src 'none'",
	"base-uri 'none'",
	'form-action https:'
].join('; ')

function escapeHtmlAttribute(value) {
	return String(value || '')
		.replaceAll('&', '&amp;')
		.replaceAll('"', '&quot;')
		.replaceAll('<', '&lt;')
		.replaceAll('>', '&gt;')
}

function bridgeScript({ channelId, renderId, theme, shellOrigin }) {
	const state = JSON.stringify({
		source: AI_HTML_PREVIEW_RUNTIME_SOURCE,
		version: AI_HTML_PREVIEW_PROTOCOL_VERSION,
		channelId,
		renderId,
		theme: theme === 'light' ? 'light' : 'dark',
		targetOrigin: shellOrigin
	})
	return `(() => {
	const config = Object.freeze(${state});
	const { targetOrigin, ...state } = config;
	let lastHeight = 0;
	const clean = value => String(value || '')
		.replace(/[?#][^\\s)]*/g, '')
		.replace(/\\s+/g, ' ')
		.trim()
		.slice(0, 4096);
	const emit = (type, payload = {}) => {
		window.parent.postMessage({ ...state, type, ...payload }, targetOrigin);
	};
	const reportLayout = () => {
		const root = document.documentElement;
		const body = document.body;
		const height = Math.max(
			120,
			Math.min(2400, Math.ceil(Math.max(
				root?.scrollHeight || 0,
				body?.scrollHeight || 0,
				root?.offsetHeight || 0,
				body?.offsetHeight || 0
			)))
		);
		if (height === lastHeight && document.readyState === 'complete') return;
		lastHeight = height;
		const backgroundColor = clean(
			body ? getComputedStyle(body).backgroundColor : '#ffffff'
		).slice(0, 64) || '#ffffff';
		emit('rendered', { height, backgroundColor });
	};
	window.addEventListener('error', event => {
		emit('runtime-error', {
			message: clean(event.message || event.error?.message),
			line: Number(event.lineno) || 0,
			column: Number(event.colno) || 0
		});
	});
	window.addEventListener('unhandledrejection', event => {
		emit('runtime-error', {
			message: clean(event.reason?.message || event.reason),
			line: 0,
			column: 0
		});
	});
	window.addEventListener('hashchange', () => emit('navigation', { url: location.href }));
	window.addEventListener('popstate', () => emit('navigation', { url: location.href }));
	window.addEventListener('DOMContentLoaded', reportLayout, { once: true });
	window.addEventListener('load', reportLayout, { once: true });
	if (typeof ResizeObserver === 'function') {
		new ResizeObserver(reportLayout).observe(document.documentElement);
	}
	document.documentElement.dataset.aiPreviewTheme = state.theme;
})();`
}

function securityHead(runtime) {
	return [
		'<meta charset="utf-8">',
		`<meta http-equiv="Content-Security-Policy" content="${escapeHtmlAttribute(AI_HTML_PREVIEW_RUNTIME_CSP)}">`,
		'<meta name="referrer" content="no-referrer">',
		'<meta name="viewport" content="width=device-width,initial-scale=1">',
		'<script>',
		bridgeScript(runtime),
		'</script>'
	].join('')
}

export function createPreviewRuntimeDocument(code, runtime) {
	let shellUrl
	try {
		shellUrl = new URL(String(runtime?.shellOrigin || ''))
	} catch (_) {
		throw new Error('HTML 运行文档缺少有效的沙箱 Origin')
	}
	if (
		shellUrl.protocol !== 'https:' ||
		shellUrl.username ||
		shellUrl.password ||
		shellUrl.pathname !== '/' ||
		shellUrl.search ||
		shellUrl.hash
	) {
		throw new Error('HTML 运行文档缺少有效的沙箱 Origin')
	}
	const source = String(code || '').replace(/^\uFEFF/, '')
	const injectedHead = securityHead({ ...runtime, shellOrigin: shellUrl.origin })
	if (/<head(?:\s[^>]*)?>/i.test(source)) {
		return source.replace(/<head(?:\s[^>]*)?>/i, match => match + injectedHead)
	}
	if (/<html(?:\s[^>]*)?>/i.test(source)) {
		return source.replace(/<html(?:\s[^>]*)?>/i, match => match + '<head>' + injectedHead + '</head>')
	}
	return '<!doctype html><html><head>' + injectedHead + '</head><body>' + source + '</body></html>'
}
