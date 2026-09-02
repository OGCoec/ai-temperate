const {
	DEFAULT_H5_RELEASE_ROOT,
	collectPublicAssetPaths
} = require('./generate-h5-edge-assets.cjs')

const IMMUTABLE_PAGES_SUFFIX = '.ai-temperate-frontend.pages.dev'
const DEFAULT_CONCURRENCY = 8

function argument(name) {
	const index = process.argv.indexOf(name)
	return index >= 0 ? process.argv[index + 1] : undefined
}

function immutablePagesOrigin(value) {
	try {
		const url = new URL(String(value || ''))
		const deployment = url.hostname.endsWith(IMMUTABLE_PAGES_SUFFIX)
			? url.hostname.slice(0, -IMMUTABLE_PAGES_SUFFIX.length)
			: ''
		if (url.protocol !== 'https:'
			|| url.port
			|| url.username
			|| url.password
			|| (url.pathname !== '/' && url.pathname !== '')
			|| url.search
			|| url.hash
			|| !/^[a-z0-9][a-z0-9-]{3,63}$/.test(deployment)) {
			return null
		}
		return new URL(`https://${url.hostname}/`)
	} catch (_) {
		return null
	}
}

function indexAssetReferences(source) {
	return [...String(source || '').matchAll(
		/(?:src|href)\s*=\s*["'](\/(?:assets|static)\/[^"'?#]+)(?:[?#][^"']*)?["']/g)]
		.map(match => match[1])
		.filter(path => /^\/(?:assets|static)\/[A-Za-z0-9@._/-]+$/.test(path))
}

function header(response, name) {
	if (typeof response?.headers?.get === 'function') {
		return String(response.headers.get(name) || '')
	}
	const entry = Object.entries(response?.headers || {})
		.find(([key]) => key.toLowerCase() === name.toLowerCase())
	return entry ? String(entry[1] || '') : ''
}

async function verifyRemoteAsset(fetchImpl, origin, path) {
	let response
	try {
		response = await fetchImpl(new URL(path, origin), {
			method: 'HEAD',
			redirect: 'manual',
			headers: {
				'Cache-Control': 'no-cache',
				Pragma: 'no-cache'
			}
		})
	} catch (error) {
		return `${path} could not be requested: ${error?.message || 'network error'}`
	}
	if (response.status !== 200) {
		return `${path} returned HTTP ${response.status}; expected 200`
	}
	if (header(response, 'Content-Type').toLowerCase().startsWith('text/html')) {
		return `${path} returned HTML instead of the immutable asset`
	}
	return ''
}

async function verifyInBatches(values, concurrency, operation) {
	const errors = []
	let index = 0
	async function worker() {
		while (index < values.length) {
			const current = values[index]
			index += 1
			const error = await operation(current)
			if (error) errors.push(error)
		}
	}
	await Promise.all(Array.from(
		{ length: Math.min(concurrency, values.length) },
		() => worker()))
	return errors
}

async function verifyH5PagesDeployment(options = {}) {
	const origin = immutablePagesOrigin(options.origin)
	if (!origin) {
		return {
			origin: null,
			errors: [
				'Pages origin must be an immutable https://<deployment>.ai-temperate-frontend.pages.dev URL'
			]
		}
	}
	const fetchImpl = options.fetchImpl || globalThis.fetch
	if (typeof fetchImpl !== 'function') {
		return { origin: origin.href, errors: ['Global fetch is unavailable'] }
	}
	const assetPaths = Array.isArray(options.assetPaths)
		? [...new Set(options.assetPaths)].sort()
		: collectPublicAssetPaths(options.root || DEFAULT_H5_RELEASE_ROOT)
	let indexResponse
	try {
		// Pages 会把 /index.html 规范化重定向到 /；直接检查规范入口，避免把正常 308 当成发布失败。
		indexResponse = await fetchImpl(new URL('/', origin), {
			method: 'GET',
			redirect: 'manual',
			headers: {
				'Cache-Control': 'no-cache',
				Pragma: 'no-cache'
			}
		})
	} catch (error) {
		return {
			origin: origin.href,
			errors: [`/ could not be requested: ${error?.message || 'network error'}`]
		}
	}
	const errors = []
	if (indexResponse.status !== 200) {
		errors.push(`/ returned HTTP ${indexResponse.status}; expected 200`)
		return { origin: origin.href, errors }
	}
	if (!header(indexResponse, 'Content-Type').toLowerCase().startsWith('text/html')) {
		errors.push('/ did not return text/html')
	}
	const indexSource = await indexResponse.text()
	const manifest = new Set(assetPaths)
	for (const reference of indexAssetReferences(indexSource)) {
		if (!manifest.has(reference)) {
			errors.push(`/ references an asset outside the release manifest: ${reference}`)
		}
	}
	if (errors.length) return { origin: origin.href, errors }

	const concurrency = Number.isInteger(options.concurrency)
		&& options.concurrency > 0
		? Math.min(options.concurrency, 32)
		: DEFAULT_CONCURRENCY
	errors.push(...await verifyInBatches(
		assetPaths,
		concurrency,
		path => verifyRemoteAsset(fetchImpl, origin, path)))
	return { origin: origin.href, errors }
}

async function main() {
	const result = await verifyH5PagesDeployment({
		origin: argument('--origin'),
		root: argument('--dir') || DEFAULT_H5_RELEASE_ROOT
	})
	if (!result.errors.length) {
		console.log(`Immutable Pages deployment verified: ${result.origin}`)
		return
	}
	for (const error of result.errors) console.error(error)
	process.exitCode = 1
}

if (require.main === module) {
	main().catch(error => {
		console.error(error?.message || error)
		process.exitCode = 1
	})
}

module.exports = {
	immutablePagesOrigin,
	indexAssetReferences,
	verifyH5PagesDeployment
}
