const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')

const { verifyH5ReleaseArtifacts } = require('../../scripts/verify-h5-release.cjs')
const {
	collectPublicAssetPaths
} = require('../../scripts/generate-h5-edge-assets.cjs')

function withFixture(files, run) {
	const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ait-h5-release-'))
	try {
		const fixtureFiles = {
			'404.html': '<!doctype html><title>页面不存在</title>',
			'assets/api-key-id-contract.js': 'const apiKeyIdPattern=/^[0-7][0-9A-HJKMNP-TV-Z]{25}$/',
			...files
		}
		for (const [relativePath, content] of Object.entries(fixtureFiles)) {
			if (content == null) continue
			const target = path.join(root, relativePath)
			fs.mkdirSync(path.dirname(target), { recursive: true })
			fs.writeFileSync(target, content)
		}
		return run(root)
	} finally {
		fs.rmSync(root, { recursive: true, force: true })
	}
}

function verifyFixture(root, overrides = {}) {
	return verifyH5ReleaseArtifacts({
		root,
		assetManifestPaths: collectPublicAssetPaths(root),
		...overrides
	})
}

const headers = [
	'/*',
	'  X-Content-Type-Options: nosniff',
	'',
	'/index.html',
	'  Cache-Control: no-cache, no-store, must-revalidate',
	'  CDN-Cache-Control: no-store',
	'',
	'/pages/*',
	'  Cache-Control: no-cache, no-store, must-revalidate',
	'  CDN-Cache-Control: no-store',
	'',
	'/assets/*',
	'  Cache-Control: public, max-age=31536000, immutable',
	''
].join('\n')

test('accepts a static H5 release artifact with no Vite development modules', () => {
	withFixture({
		'index.html': '<!doctype html><meta http-equiv="Content-Security-Policy" content="frame-src https://ai-temperate-html-preview.pages.dev"><script type="module" src="/assets/index-a1b2c3.js"></script>',
		'_headers': headers,
		'_redirects': '# SPA routes are resolved by the main-site Worker.\n',
		'assets/index-a1b2c3.js': 'const previewOrigin="https://ai-temperate-html-preview.pages.dev";console.log("release")'
	}, root => {
		assert.deepEqual(verifyFixture(root).errors, [])
	})
})

test('rejects an H5 artifact that still validates API Key IDs as 11-character Base64URL values', () => {
	withFixture({
		'index.html': '<!doctype html><meta http-equiv="Content-Security-Policy" content="frame-src https://ai-temperate-html-preview.pages.dev"><script type="module" src="/assets/index-a1b2c3.js"></script>',
		'_headers': headers,
		'_redirects': '# SPA routes are resolved by the main-site Worker.\n',
		'assets/index-a1b2c3.js': 'const previewOrigin="https://ai-temperate-html-preview.pages.dev"',
		'assets/api-key-id-contract.js': 'const apiKeyIdPattern=/^[A-Za-z0-9_-]{11}$/'
	}, root => {
		assert.match(
			verifyFixture(root).errors.join('\n'),
			/26-character API Key ULID contract/
		)
	})
})

test('accepts bundled Vue syntax-highlighting grammar scope names', () => {
	withFixture({
		'index.html': '<!doctype html><meta http-equiv="Content-Security-Policy" content="frame-src https://ai-temperate-html-preview.pages.dev"><script type="module" src="/assets/index-a1b2c3.js"></script>',
		'_headers': headers,
		'_redirects': '# SPA routes are resolved by the main-site Worker.\n',
		'assets/index-a1b2c3.js': 'const previewOrigin="https://ai-temperate-html-preview.pages.dev"',
		'assets/vue-grammar.js': 'const grammar={scopeName:"text.html.vue",patterns:[{name:"source.directive.vue"},{name:"entity.name.tag.html.vue"}]}'
	}, root => {
		assert.deepEqual(verifyFixture(root).errors, [])
	})
})

test('rejects an H5 artifact without a top-level 404 page that disables the Pages SPA fallback', () => {
	withFixture({
		'404.html': null,
		'index.html': '<!doctype html><meta http-equiv="Content-Security-Policy" content="frame-src https://ai-temperate-html-preview.pages.dev"><script type="module" src="/assets/index-a1b2c3.js"></script>',
		'_headers': headers,
		'_redirects': '# SPA routes are resolved by the main-site Worker.\n',
		'assets/index-a1b2c3.js': 'const previewOrigin="https://ai-temperate-html-preview.pages.dev"'
	}, root => {
		assert.match(
			verifyFixture(root).errors.join('\n'),
			/Missing required release file: 404\.html/
		)
	})
})

test('rejects H5 artifacts that contain a loopback preview origin', () => {
	withFixture({
		'index.html': '<meta http-equiv="Content-Security-Policy" content="frame-src https://ai-temperate-html-preview.pages.dev">',
		'_headers': headers,
		'_redirects': '# SPA routes are resolved by the main-site Worker.\n',
		'assets/index-a1b2c3.js': 'const previewOrigin="https://localhost:4174"'
	}, root => {
		assert.match(
			verifyFixture(root).errors.join('\n'),
			/loopback HTML preview origin/
		)
	})
})

test('rejects H5 artifacts that omit the public preview origin', () => {
	withFixture({
		'index.html': '<!doctype html><script type="module" src="/assets/index-a1b2c3.js"></script>',
		'_headers': headers,
		'_redirects': '# SPA routes are resolved by the main-site Worker.\n',
		'assets/index-a1b2c3.js': 'console.log("release")'
	}, root => {
		assert.match(
			verifyFixture(root).errors.join('\n'),
			/public HTML preview origin/
		)
	})
})

test('rejects H5 artifacts that still expose Vite source modules', () => {
	withFixture({
		'index.html': '<script type="module" src="/@vite/client"></script>',
		'_headers': headers,
		'_redirects': '# SPA routes are resolved by the main-site Worker.\n',
		'assets/index-a1b2c3.js': 'import page from "/components/user/workspace/user-model-catalog.vue?vue&type=script"'
	}, root => {
		assert.match(
			verifyFixture(root).errors.join('\n'),
			/Vite development module|Vue source module/
		)
	})
})

test('rejects H5 headers that allow cached deep page fallbacks', () => {
	const missingPageHeaders = [
		'/*',
		'  X-Content-Type-Options: nosniff',
		'',
		'/index.html',
		'  Cache-Control: no-cache, no-store, must-revalidate',
		'  CDN-Cache-Control: no-store',
		'',
		'/assets/*',
		'  Cache-Control: public, max-age=31536000, immutable',
		''
	].join('\n')

	withFixture({
		'index.html': '<!doctype html><script type="module" src="/assets/index-a1b2c3.js"></script>',
		'_headers': missingPageHeaders,
		'_redirects': '# SPA routes are resolved by the main-site Worker.\n',
		'assets/index-a1b2c3.js': 'console.log("release")'
	}, root => {
		assert.match(
			verifyFixture(root).errors.join('\n'),
			/\/pages\/\*/
		)
	})
})

test('rejects source maps and tests from the public Pages release', () => {
	withFixture({
		'index.html': '<!doctype html><meta http-equiv="Content-Security-Policy" content="frame-src https://ai-temperate-html-preview.pages.dev"><script type="module" src="/assets/index-a1b2c3.js"></script>',
		'_headers': headers,
		'_redirects': '# SPA routes are resolved by the main-site Worker.\n',
		'assets/index-a1b2c3.js': 'const previewOrigin="https://ai-temperate-html-preview.pages.dev"',
		'assets/index-a1b2c3.js.map': '{}',
		'static/runtime.test.js': 'throw new Error("test only")'
	}, root => {
		const errors = verifyFixture(root).errors.join('\n')
		assert.match(errors, /source map/)
		assert.match(errors, /test artifact/)
	})
})

test('rejects a release artifact that restores the global SPA fallback', () => {
	withFixture({
		'index.html': '<!doctype html><meta http-equiv="Content-Security-Policy" content="frame-src https://ai-temperate-html-preview.pages.dev"><script type="module" src="/assets/index-a1b2c3.js"></script>',
		'_headers': headers,
		'_redirects': '/* /index.html 200\n',
		'assets/index-a1b2c3.js': 'const previewOrigin="https://ai-temperate-html-preview.pages.dev"'
	}, root => {
		assert.match(
			verifyFixture(root).errors.join('\n'),
			/global SPA fallback/
		)
	})
})

test('rejects a release artifact whose exact edge asset manifest is stale', () => {
	withFixture({
		'index.html': '<!doctype html><meta http-equiv="Content-Security-Policy" content="frame-src https://ai-temperate-html-preview.pages.dev"><script type="module" src="/assets/index-a1b2c3.js"></script>',
		'_headers': headers,
		'_redirects': '# SPA routes are resolved by the main-site Worker.\n',
		'assets/index-a1b2c3.js': 'const previewOrigin="https://ai-temperate-html-preview.pages.dev"'
	}, root => {
		assert.match(
			verifyFixture(root, { assetManifestPaths: [] }).errors.join('\n'),
			/asset manifest does not match/
		)
	})
})

test('rejects index references outside the exact edge asset manifest', () => {
	withFixture({
		'index.html': '<!doctype html><meta http-equiv="Content-Security-Policy" content="frame-src https://ai-temperate-html-preview.pages.dev"><script type="module" src="/assets/missing.js"></script>',
		'_headers': headers,
		'_redirects': '# SPA routes are resolved by the main-site Worker.\n',
		'assets/index-a1b2c3.js': 'const previewOrigin="https://ai-temperate-html-preview.pages.dev"'
	}, root => {
		assert.match(
			verifyFixture(root).errors.join('\n'),
			/index\.html references an asset outside/
		)
	})
})
