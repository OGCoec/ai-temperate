const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')

const { verifyH5ReleaseArtifacts } = require('../../scripts/verify-h5-release.cjs')

function withFixture(files, run) {
	const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ait-h5-release-'))
	try {
		for (const [relativePath, content] of Object.entries(files)) {
			const target = path.join(root, relativePath)
			fs.mkdirSync(path.dirname(target), { recursive: true })
			fs.writeFileSync(target, content)
		}
		return run(root)
	} finally {
		fs.rmSync(root, { recursive: true, force: true })
	}
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
		'_redirects': '/* /index.html 200\n',
		'assets/index-a1b2c3.js': 'const previewOrigin="https://ai-temperate-html-preview.pages.dev";console.log("release")'
	}, root => {
		assert.deepEqual(verifyH5ReleaseArtifacts({ root }).errors, [])
	})
})

test('accepts bundled Vue syntax-highlighting grammar scope names', () => {
	withFixture({
		'index.html': '<!doctype html><meta http-equiv="Content-Security-Policy" content="frame-src https://ai-temperate-html-preview.pages.dev"><script type="module" src="/assets/index-a1b2c3.js"></script>',
		'_headers': headers,
		'_redirects': '/* /index.html 200\n',
		'assets/index-a1b2c3.js': 'const previewOrigin="https://ai-temperate-html-preview.pages.dev"',
		'assets/vue-grammar.js': 'const grammar={scopeName:"text.html.vue",patterns:[{name:"source.directive.vue"},{name:"entity.name.tag.html.vue"}]}'
	}, root => {
		assert.deepEqual(verifyH5ReleaseArtifacts({ root }).errors, [])
	})
})

test('rejects H5 artifacts that contain a loopback preview origin', () => {
	withFixture({
		'index.html': '<meta http-equiv="Content-Security-Policy" content="frame-src https://ai-temperate-html-preview.pages.dev">',
		'_headers': headers,
		'_redirects': '/* /index.html 200\n',
		'assets/index-a1b2c3.js': 'const previewOrigin="https://localhost:4174"'
	}, root => {
		assert.match(
			verifyH5ReleaseArtifacts({ root }).errors.join('\n'),
			/loopback HTML preview origin/
		)
	})
})

test('rejects H5 artifacts that omit the public preview origin', () => {
	withFixture({
		'index.html': '<!doctype html><script type="module" src="/assets/index-a1b2c3.js"></script>',
		'_headers': headers,
		'_redirects': '/* /index.html 200\n',
		'assets/index-a1b2c3.js': 'console.log("release")'
	}, root => {
		assert.match(
			verifyH5ReleaseArtifacts({ root }).errors.join('\n'),
			/public HTML preview origin/
		)
	})
})

test('rejects H5 artifacts that still expose Vite source modules', () => {
	withFixture({
		'index.html': '<script type="module" src="/@vite/client"></script>',
		'_headers': headers,
		'_redirects': '/* /index.html 200\n',
		'assets/index-a1b2c3.js': 'import page from "/components/user/workspace/user-model-catalog.vue?vue&type=script"'
	}, root => {
		assert.match(
			verifyH5ReleaseArtifacts({ root }).errors.join('\n'),
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
		'_redirects': '/* /index.html 200\n',
		'assets/index-a1b2c3.js': 'console.log("release")'
	}, root => {
		assert.match(
			verifyH5ReleaseArtifacts({ root }).errors.join('\n'),
			/\/pages\/\*/
		)
	})
})
