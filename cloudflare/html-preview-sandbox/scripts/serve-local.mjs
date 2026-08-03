import fs from 'node:fs'
import https from 'node:https'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const publicRoot = path.join(projectRoot, 'public')
const host = '127.0.0.1'
const port = Number(process.env.HTML_PREVIEW_PORT || 4174)
const p12Path = String(process.env.LOCAL_HTTPS_P12_PATH || '').trim()
const passphrase = String(process.env.SERVER_SSL_KEY_STORE_PASSWORD || '')

if (!p12Path || !passphrase) {
	throw new Error('本地预览沙箱需要 LOCAL_HTTPS_P12_PATH 和 SERVER_SSL_KEY_STORE_PASSWORD。')
}
if (!fs.existsSync(p12Path)) {
	throw new Error(`本地 HTTPS 证书不存在：${p12Path}`)
}
if (!Number.isInteger(port) || port < 1024 || port > 65535) {
	throw new Error('HTML_PREVIEW_PORT 必须是 1024 到 65535 之间的端口。')
}

const securityHeaders = {
	'Cache-Control': 'no-store, no-cache, must-revalidate',
	'Content-Security-Policy': "default-src https: data: blob:; script-src 'self' https: data: blob: 'unsafe-inline' 'unsafe-eval'; style-src 'self' https: 'unsafe-inline'; frame-src 'self' https: data: blob:; child-src 'self' https: data: blob:; img-src https: data: blob:; connect-src https: wss:; worker-src https: blob:; font-src https: data:; media-src https: data: blob:; object-src 'none'; base-uri 'none'; form-action https:; frame-ancestors https://niko000o.site https://dev.niko000o.site https://localhost:3000 https://127.0.0.1:3000",
	'Referrer-Policy': 'no-referrer',
	'X-Content-Type-Options': 'nosniff',
	'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), clipboard-read=(), clipboard-write=(), fullscreen=(), payment=(), usb=(), serial=(), hid=()',
	'Cross-Origin-Resource-Policy': 'cross-origin',
	'Origin-Agent-Cluster': '?1'
}

const contentTypes = new Map([
	['.html', 'text/html; charset=utf-8'],
	['.js', 'text/javascript; charset=utf-8'],
	['.css', 'text/css; charset=utf-8'],
	['.json', 'application/json; charset=utf-8'],
	['.txt', 'text/plain; charset=utf-8']
])

function resolvePublicFile(requestUrl) {
	let pathname
	try {
		pathname = decodeURIComponent(new URL(requestUrl, `https://${host}:${port}`).pathname)
	} catch (_) {
		return ''
	}
	if (pathname === '/') pathname = '/index.html'
	const candidate = path.resolve(publicRoot, '.' + pathname.replaceAll('\\', '/'))
	if (candidate !== publicRoot && !candidate.startsWith(publicRoot + path.sep)) return ''
	return candidate
}

const server = https.createServer({
	pfx: fs.readFileSync(p12Path),
	passphrase
}, (request, response) => {
	if (!['GET', 'HEAD'].includes(request.method || '')) {
		response.writeHead(405, { ...securityHeaders, Allow: 'GET, HEAD' })
		response.end()
		return
	}
	let filePath = resolvePublicFile(request.url || '/')
	if (!filePath || !fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
		filePath = path.join(publicRoot, 'index.html')
	}
	const contentType = contentTypes.get(path.extname(filePath).toLowerCase()) || 'application/octet-stream'
	response.writeHead(200, { ...securityHeaders, 'Content-Type': contentType })
	if (request.method === 'HEAD') {
		response.end()
		return
	}
	fs.createReadStream(filePath).pipe(response)
})

server.listen(port, host, () => {
	process.stdout.write(`HTML preview sandbox: https://${host}:${port}\n`)
})
