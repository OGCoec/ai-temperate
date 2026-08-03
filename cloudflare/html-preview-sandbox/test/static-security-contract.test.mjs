import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

async function read(relativePath) {
	return readFile(path.join(projectRoot, relativePath), 'utf8')
}

test('serves the shell with a runtime-compatible policy and exact frame ancestors', async () => {
	const headers = await read('public/_headers')

	assert.match(headers, /script-src 'self' https: data: blob: 'unsafe-inline' 'unsafe-eval'/)
	assert.match(headers, /connect-src https: wss:/)
	assert.match(headers, /worker-src https: blob:/)
	assert.match(headers, /frame-src 'self' https: data: blob:/)
	assert.match(headers, /frame-ancestors https:\/\/niko000o\.site https:\/\/dev\.niko000o\.site(?:;|\s*$)/m)
	assert.equal(headers.includes('https://localhost:3000'), false)
	assert.equal(headers.includes('https://127.0.0.1:3000'), false)
	assert.match(headers, /Referrer-Policy: no-referrer/)
	assert.match(headers, /X-Content-Type-Options: nosniff/)
	assert.match(headers, /X-Robots-Tag: noindex, nofollow, noarchive/)
	assert.equal(/set-cookie/i.test(headers), false)
})

test('publishes only static assets without Pages Functions or a Worker entry point', () => {
	assert.equal(existsSync(path.join(projectRoot, 'functions')), false)
	assert.equal(existsSync(path.join(projectRoot, 'public', '_worker.js')), false)
})

test('does not grant high-risk iframe capabilities', async () => {
	const shell = await read('public/sandbox-shell.js')

	assert.match(shell, /allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox/)
	assert.equal(shell.includes('allow-top-navigation'), false)
	assert.equal(shell.includes('allow-downloads'), false)
	assert.equal(shell.includes('allow-camera'), false)
	assert.equal(shell.includes('allow-microphone'), false)
})

test('keeps the static entry point free from inline executable code', async () => {
	const html = await read('public/index.html')

	assert.match(html, /<script type="module" src="\.\/sandbox-shell\.js"><\/script>/)
	assert.equal(/<script(?![^>]*\bsrc=)[^>]*>/i.test(html), false)
	assert.equal(/onload\s*=/i.test(html), false)
})
