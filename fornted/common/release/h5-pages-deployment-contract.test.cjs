const assert = require('node:assert/strict')
const test = require('node:test')

const {
	immutablePagesOrigin,
	verifyH5PagesDeployment
} = require('../../scripts/verify-h5-pages-deployment.cjs')

function response(status, contentType, body = '') {
	return {
		status,
		headers: {
			get(name) {
				return name.toLowerCase() === 'content-type' ? contentType : ''
			}
		},
		async text() { return body }
	}
}

test('rejects the mutable Pages project URL', () => {
	assert.equal(
		immutablePagesOrigin('https://ai-temperate-frontend.pages.dev'),
		null)
})

test('accepts an immutable deployment only after every manifest asset returns 200', async () => {
	const requested = []
	const result = await verifyH5PagesDeployment({
		origin: 'https://7f55a57b.ai-temperate-frontend.pages.dev',
		assetPaths: [
			'/assets/index-a1b2c3.js',
			'/assets/index-a1b2c3.css',
			'/assets/unicons-a1b2c3.ttf'
		],
		fetchImpl: async (url, options) => {
			requested.push([url.pathname, options.method])
			if (url.pathname === '/') {
				return response(
					200,
					'text/html; charset=utf-8',
					'<script src="/assets/index-a1b2c3.js"></script>'
					+ '<link href="/assets/index-a1b2c3.css" rel="stylesheet">')
			}
			return response(200, 'application/octet-stream')
		}
	})

	assert.deepEqual(result.errors, [])
	assert.deepEqual(requested.sort(), [
		['/assets/index-a1b2c3.css', 'HEAD'],
		['/assets/index-a1b2c3.js', 'HEAD'],
		['/assets/unicons-a1b2c3.ttf', 'HEAD'],
		['/', 'GET']
	].sort())
})

test('blocks Worker origin switching when a hashed asset returns 404', async () => {
	const result = await verifyH5PagesDeployment({
		origin: 'https://7f55a57b.ai-temperate-frontend.pages.dev',
		assetPaths: ['/assets/index-missing.js'],
		fetchImpl: async (url) => url.pathname === '/'
			? response(
				200,
				'text/html',
				'<script src="/assets/index-missing.js"></script>')
			: response(404, 'text/html')
	})

	assert.match(result.errors.join('\n'), /returned HTTP 404/)
})

test('rejects a Pages SPA fallback that returns HTML for a missing asset', async () => {
	const result = await verifyH5PagesDeployment({
		origin: 'https://7f55a57b.ai-temperate-frontend.pages.dev',
		assetPaths: ['/assets/index-wrong.js'],
		fetchImpl: async (url) => url.pathname === '/'
			? response(
				200,
				'text/html',
				'<script src="/assets/index-wrong.js"></script>')
			: response(200, 'text/html')
	})

	assert.match(result.errors.join('\n'), /returned HTML/)
})
