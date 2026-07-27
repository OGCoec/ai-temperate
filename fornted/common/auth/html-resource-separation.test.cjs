const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const source = file => fs.readFileSync(path.join(root, file), 'utf8')

function assertExternalResourcesOnly(relativePath) {
	const html = source(relativePath)
	assert.doesNotMatch(html, /<style\b/i, relativePath)
	assert.doesNotMatch(html, /\sstyle\s*=/i, relativePath)
	assert.doesNotMatch(html, /\son[a-z]+\s*=/i, relativePath)
	for (const tag of html.matchAll(/<script\b([^>]*)>/gi)) {
		assert.match(tag[1], /\bsrc\s*=/i, relativePath)
	}
}

test('ordinary H5 entry documents keep authored JavaScript outside HTML', () => {
	assertExternalResourcesOnly('index.html')
	assertExternalResourcesOnly('template.h5.html')

	const index = source('index.html')
	const template = source('template.h5.html')
	assert.match(index, /\/static\/bootstrap\/viewport-bootstrap\.js/)
	assert.match(index, /\/static\/auth\/turnstile-sdk-bootstrap\.js/)
	assert.match(template, /static\/bootstrap\/viewport-bootstrap\.js/)
	assert.doesNotMatch(template, /BAIDU_STAT|TouchEmulator|hm\.baidu\.com/)
	assert.equal(fs.existsSync(path.join(root, 'static/bootstrap/viewport-bootstrap.js')), true)
	assert.equal(fs.existsSync(path.join(root, 'static/auth/turnstile-sdk-bootstrap.js')), true)
})

test('ordinary WebRTC probe keeps its style and behavior in sibling resources', () => {
	assertExternalResourcesOnly('hybrid/html/webrtc-probe.html')

	const html = source('hybrid/html/webrtc-probe.html')
	assert.match(html, /style-src 'self'/)
	assert.doesNotMatch(html, /style-src 'unsafe-inline'/)
	assert.match(html, /\.\/webrtc-probe\.css/)
	assert.match(html, /\.\/webrtc-probe\.js/)
	assert.equal(fs.existsSync(path.join(root, 'hybrid/html/webrtc-probe.css')), true)
	assert.equal(fs.existsSync(path.join(root, 'hybrid/html/webrtc-probe.js')), true)
})
