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

test('administrator H5 entry keeps viewport behavior in an external script', () => {
	assertExternalResourcesOnly('index.html')

	const index = source('index.html')
	const scriptSource = index.match(/script-src ([^;]+)/)?.[1]
	assert.ok(scriptSource, 'administrator index must declare script-src')
	assert.doesNotMatch(scriptSource, /'unsafe-inline'/)
	assert.match(index, /\/static\/bootstrap\/initial-shell\.css/)
	assert.match(index, /\/static\/bootstrap\/viewport-bootstrap\.js/)
	assert.match(index, /\/static\/bootstrap\/admin-workspace-route-bootstrap\.js/)
	assert.ok(
		index.indexOf('/static/bootstrap/admin-workspace-route-bootstrap.js')
			< index.indexOf('/main.js')
	)
	assert.equal(fs.existsSync(path.join(root, 'static/bootstrap/initial-shell.css')), true)
	assert.equal(fs.existsSync(path.join(root, 'static/bootstrap/viewport-bootstrap.js')), true)
	assert.equal(
		fs.existsSync(path.join(root, 'static/bootstrap/admin-workspace-route-bootstrap.js')),
		true
	)
})

test('administrator WebRTC probe keeps its style and behavior in sibling resources', () => {
	assertExternalResourcesOnly('hybrid/html/webrtc-probe.html')

	const html = source('hybrid/html/webrtc-probe.html')
	assert.match(html, /style-src 'self'/)
	assert.doesNotMatch(html, /style-src 'unsafe-inline'/)
	assert.match(html, /\.\/webrtc-probe\.css/)
	assert.match(html, /\.\/webrtc-probe\.js/)
	assert.equal(fs.existsSync(path.join(root, 'hybrid/html/webrtc-probe.css')), true)
	assert.equal(fs.existsSync(path.join(root, 'hybrid/html/webrtc-probe.js')), true)
})
