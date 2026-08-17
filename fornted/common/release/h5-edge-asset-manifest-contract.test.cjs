const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')

const {
	collectPublicAssetPaths,
	renderAssetManifest
} = require('../../scripts/generate-h5-edge-assets.cjs')

test('collects only exact H5 assets and static files in stable order', () => {
	const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ait-edge-assets-'))
	try {
		const files = [
			'assets/index-b.js',
			'assets/index-a.css',
			'assets/source.vue',
			'assets/source.js.map',
			'assets/runtime.test.js',
			'static/__tests__/fixture.json',
			'static/bootstrap/viewport-bootstrap.js',
			'static/icons/source-globe.svg',
			'static/licenses/NOTICE.txt',
			'hybrid/html/webrtc-probe.html',
			'uni_modules/example/static/image.png'
		]
		for (const relative of files) {
			const target = path.join(root, relative)
			fs.mkdirSync(path.dirname(target), { recursive: true })
			fs.writeFileSync(target, relative)
		}

		assert.deepEqual(collectPublicAssetPaths(root), [
			'/assets/index-a.css',
			'/assets/index-b.js',
			'/static/bootstrap/viewport-bootstrap.js',
			'/static/icons/source-globe.svg',
			'/static/licenses/NOTICE.txt'
		])
	} finally {
		fs.rmSync(root, { recursive: true, force: true })
	}
})

test('renders a deterministic immutable Worker module', () => {
	const source = renderAssetManifest([
		'/static/bootstrap/viewport-bootstrap.js',
		'/assets/index-a.js'
	])

	assert.match(source, /export const H5_ASSET_PATHS = Object\.freeze/)
	assert.ok(source.indexOf('/assets/index-a.js')
		< source.indexOf('/static/bootstrap/viewport-bootstrap.js'))
	assert.doesNotMatch(source, /generatedAt|Date/)
})
