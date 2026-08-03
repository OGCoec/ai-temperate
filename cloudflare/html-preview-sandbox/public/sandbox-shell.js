import {
	createShellMessage,
	isHostMessage,
	isRuntimeMessage,
	parseShellLocationHash,
	sanitizeRuntimeMessage
} from './message-protocol.js'
import { createPreviewRuntimeDocument } from './runtime-document.js'

const statusElement = document.querySelector('[data-sandbox-status]')
const runtimeRoot = document.querySelector('[data-runtime-root]')
const shellConfig = parseShellLocationHash(window.location.hash)
let runtimeFrame = null
let activeRenderId = ''
let runtimeErrorCount = 0

function showStatus(message, state = 'visible') {
	statusElement.textContent = message
	statusElement.dataset.state = state
}

function postToHost(type, payload = {}) {
	if (!shellConfig.parentOrigin || !shellConfig.channelId) return
	window.parent.postMessage(
		createShellMessage(shellConfig.channelId, type, payload),
		shellConfig.parentOrigin
	)
}

function disposeRuntime() {
	if (runtimeFrame) runtimeFrame.remove()
	runtimeFrame = null
	activeRenderId = ''
	runtimeErrorCount = 0
}

function renderRuntime(message) {
	disposeRuntime()
	activeRenderId = message.renderId
	const iframe = document.createElement('iframe')
	iframe.className = 'sandbox-runtime-frame'
	iframe.title = 'HTML 运行预览'
	iframe.referrerPolicy = 'no-referrer'
	iframe.setAttribute(
		'sandbox',
		'allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox'
	)
	runtimeFrame = iframe
	runtimeRoot.replaceChildren(iframe)
	try {
		iframe.srcdoc = createPreviewRuntimeDocument(message.html, {
			channelId: shellConfig.channelId,
			renderId: message.renderId,
			theme: message.theme,
			shellOrigin: window.location.origin
		})
	} catch (_) {
		showStatus('无法构造 HTML 运行文档')
		postToHost('runtime-error', {
			renderId: message.renderId,
			message: '无法构造 HTML 运行文档',
			line: 0,
			column: 0
		})
	}
}

function onHostMessage(event) {
	if (
		event.source !== window.parent ||
		event.origin !== shellConfig.parentOrigin ||
		!isHostMessage(event.data, shellConfig.channelId)
	) return
	if (event.data.type === 'dispose') {
		if (event.data.renderId === activeRenderId) disposeRuntime()
		return
	}
	renderRuntime(event.data)
}

function onRuntimeMessage(event) {
	if (!runtimeFrame || event.source !== runtimeFrame.contentWindow) return
	if (event.origin !== window.location.origin && event.origin !== 'null') return
	if (!isRuntimeMessage(event.data, shellConfig.channelId, activeRenderId)) return
	const sanitized = sanitizeRuntimeMessage(event.data)
	if (!sanitized.renderId) return
	if (sanitized.type === 'runtime-error') {
		runtimeErrorCount += 1
		if (runtimeErrorCount > 20) return
	} else if (sanitized.type === 'rendered') {
		showStatus('', 'hidden')
	}
	postToHost(sanitized.type, sanitized)
}

if (!shellConfig.channelId || !shellConfig.parentOrigin) {
	showStatus('预览请求无效或来源未获允许')
} else {
	showStatus('', 'hidden')
	window.addEventListener('message', onHostMessage)
	window.addEventListener('message', onRuntimeMessage)
	window.addEventListener('pagehide', disposeRuntime, { once: true })
	postToHost('ready')
}
