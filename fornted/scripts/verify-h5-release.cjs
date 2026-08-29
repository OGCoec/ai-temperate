const fs = require('node:fs')
const path = require('node:path')
const {
	DEFAULT_H5_RELEASE_ROOT,
	assertSupportedH5ReleaseRoot,
	collectPublicAssetPaths
} = require('./generate-h5-edge-assets.cjs')

const REQUIRED_FILES = ['index.html', '404.html', '_headers', '_redirects']
const DEFAULT_ASSET_MANIFEST = path.resolve(
	__dirname,
	'..',
	'..',
	'cloudflare',
	'api-gateway',
	'src',
	'generated',
	'h5-assets.js')
const PUBLIC_HTML_PREVIEW_ORIGIN = 'https://ai-temperate-html-preview.pages.dev'
const API_KEY_ULID_CONTRACT_SOURCE = '^[0-7][0-9A-HJKMNP-TV-Z]{25}$'
const REQUIRED_UNI_COMPONENT_IMPLEMENTATIONS = [
	{ re: /\bname\s*:\s*["']uniPopup["']/, label: 'UniPopup' },
	{ re: /\bname\s*:\s*["']uniTransition["']/, label: 'UniTransition' },
	{ re: /\bname\s*:\s*["']UniSearchBar["']/, label: 'UniSearchBar' }
]
const FORBIDDEN_FILE_PATTERNS = [
	{ re: /\.vue(?:$|[?#])/, label: 'Vue source module' },
	{ re: /\.map$/, label: 'source map' },
	{ re: /(?:^|\/)[^/]+\.(?:test|spec)\.[^/]+$/i, label: 'test artifact' },
	{ re: /(?:^|\/)__tests__(?:\/|$)/, label: 'test artifact directory' },
	{ re: /(?:^|\/)node_modules(?:\/|$)/, label: 'node_modules content' },
	{ re: /(?:^|\/)\.env(?:\.|$)/, label: 'environment file' }
]
const FORBIDDEN_TEXT_PATTERNS = [
	{ re: /\/@vite\/client|@vite\/client|__vite_ping|import\.meta\.hot/, label: 'Vite development module' },
	{ re: /\/@fs\//, label: 'Vite file-system module' },
	{
		re: /(?:\.vue\?(?:vue&type=|[^'"\s]*)|['"`](?:\/|\.{1,2}\/|@\/|[A-Za-z]:[\\/])[^'"`\r\n]*\.vue(?:\?[^'"`\r\n]*)?['"`])/,
		label: 'Vue source module'
	},
	{ re: /pages-json-js/, label: 'uni-app development route module' },
	{
		re: /https:\/\/(?:localhost|127(?:\.\d{1,3}){3}|\[::1\]):4174/i,
		label: 'loopback HTML preview origin'
	}
]

function normalizeRelative(value) {
	return value.split(path.sep).join('/')
}

function walk(dir, files) {
	for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
		const absolute = path.join(dir, entry.name)
		if (entry.isDirectory()) {
			walk(absolute, files)
		} else if (entry.isFile()) {
			files.push(absolute)
		}
	}
}

function readIfExists(file) {
	return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : ''
}

function headerBlock(source, headerPath) {
	const block = []
	let matching = false
	for (const line of source.split(/\r?\n/)) {
		if (line.startsWith('/')) {
			if (matching) break
			matching = line.trim() === headerPath
			continue
		}
		if (matching) block.push(line)
	}
	return block.join('\n')
}

function verifyHeaders(root, errors) {
	const source = readIfExists(path.join(root, '_headers'))
	const indexHeaders = headerBlock(source, '/index.html')
	const pageHeaders = headerBlock(source, '/pages/*')
	const assetHeaders = headerBlock(source, '/assets/*')

	if (!/Cache-Control:\s*no-cache,\s*no-store,\s*must-revalidate/i.test(indexHeaders)) {
		errors.push('_headers must keep /index.html on no-cache, no-store, must-revalidate')
	}
	if (!/CDN-Cache-Control:\s*no-store/i.test(indexHeaders)) {
		errors.push('_headers must keep /index.html on CDN-Cache-Control: no-store')
	}
	if (!/Cache-Control:\s*no-cache,\s*no-store,\s*must-revalidate/i.test(pageHeaders)) {
		errors.push('_headers must keep /pages/* on no-cache, no-store, must-revalidate')
	}
	if (!/CDN-Cache-Control:\s*no-store/i.test(pageHeaders)) {
		errors.push('_headers must keep /pages/* on CDN-Cache-Control: no-store')
	}
	if (!/Cache-Control:\s*public,\s*max-age=31536000,\s*immutable/i.test(assetHeaders)) {
		errors.push('_headers must keep /assets/* on public, max-age=31536000, immutable')
	}
}

function verifyRedirects(root, errors) {
	const source = readIfExists(path.join(root, '_redirects'))
	if (/^\s*\/\*\s+\/index\.html\s+200(?:\s|$)/m.test(source)) {
		errors.push(
			'_redirects must not restore the global SPA fallback; '
			+ 'the main-site Worker owns exact page routing')
	}
}

function parseAssetManifest(source) {
	const match = source.match(
		/export const H5_ASSET_PATHS = Object\.freeze\((\[[\s\S]*\])\)\s*$/)
	if (!match) {
		throw new Error('H5 edge asset manifest has an invalid module format')
	}
	const values = JSON.parse(match[1])
	if (!Array.isArray(values)
		|| values.some(value => typeof value !== 'string')) {
		throw new Error('H5 edge asset manifest must contain only path strings')
	}
	return [...new Set(values)].sort()
}

function expectedAssetPaths(options) {
	if (Array.isArray(options.assetManifestPaths)) {
		return [...new Set(options.assetManifestPaths)].sort()
	}
	const manifest = path.resolve(
		options.assetManifestPath || DEFAULT_ASSET_MANIFEST)
	if (!fs.existsSync(manifest)) {
		throw new Error(`H5 edge asset manifest does not exist: ${manifest}`)
	}
	return parseAssetManifest(fs.readFileSync(manifest, 'utf8'))
}

function verifyAssetManifest(root, errors, options) {
	let expected
	try {
		expected = expectedAssetPaths(options)
	} catch (error) {
		errors.push(error.message)
		return
	}
	const actual = collectPublicAssetPaths(root)
	if (JSON.stringify(actual) !== JSON.stringify(expected)) {
		errors.push(
			'H5 edge asset manifest does not match the exact /assets and /static release files')
	}
}

function verifyIndexAssetReferences(indexSource, assetPaths, errors) {
	const references = [...indexSource.matchAll(
		/(?:src|href)\s*=\s*["'](\/(?:assets|static)\/[^"'?#]+)(?:[?#][^"']*)?["']/g)]
		.map(match => match[1])
	const allowed = new Set(assetPaths)
	for (const reference of references) {
		if (!allowed.has(reference)) {
			errors.push(
				`index.html references an asset outside the exact edge manifest: ${reference}`)
		}
	}
}

function verifyH5ReleaseArtifacts(options = {}) {
	const root = path.resolve(options.root || DEFAULT_H5_RELEASE_ROOT)
	const errors = []
	try {
		assertSupportedH5ReleaseRoot(root, {
			allowNonCanonicalRoot: options.allowNonCanonicalRoot === true
		})
	} catch (error) {
		return { root, errors: [error.message] }
	}

	if (!fs.existsSync(root)) {
		return { root, errors: [`H5 release directory does not exist: ${root}`] }
	}

	for (const relativePath of REQUIRED_FILES) {
		if (!fs.existsSync(path.join(root, relativePath))) errors.push(`Missing required release file: ${relativePath}`)
	}

	verifyHeaders(root, errors)
	verifyRedirects(root, errors)
	verifyAssetManifest(root, errors, options)
	const indexSource = readIfExists(path.join(root, 'index.html'))
	try {
		verifyIndexAssetReferences(indexSource, expectedAssetPaths(options), errors)
	} catch (error) {
		// 清单格式错误已经由 verifyAssetManifest 报告，避免重复输出相同的配置异常。
	}
	const frameSource = indexSource.match(/frame-src ([^;]+)/)?.[1] || ''
	if (!frameSource.includes(PUBLIC_HTML_PREVIEW_ORIGIN)) {
		errors.push(
			`index.html frame-src must include the public HTML preview origin: ${PUBLIC_HTML_PREVIEW_ORIGIN}`)
	}

	const files = []
	walk(root, files)
	let previewOriginFoundInScript = false
	let apiKeyUlidContractFoundInScript = false
	const bundledUniComponents = new Set()
	for (const file of files) {
		const relative = normalizeRelative(path.relative(root, file))
		for (const pattern of FORBIDDEN_FILE_PATTERNS) {
			if (pattern.re.test(relative)) errors.push(`${pattern.label} must not be published: ${relative}`)
		}

		if (!/\.(?:html|js|css|json|map|txt|svg)$/.test(relative)) continue
		const source = readIfExists(file)
		if (/\.js$/.test(relative) && source.includes(PUBLIC_HTML_PREVIEW_ORIGIN)) {
			previewOriginFoundInScript = true
		}
		if (/\.js$/.test(relative) && source.includes(API_KEY_ULID_CONTRACT_SOURCE)) {
			apiKeyUlidContractFoundInScript = true
		}
		if (/\.js$/.test(relative)) {
			for (const component of REQUIRED_UNI_COMPONENT_IMPLEMENTATIONS) {
				if (component.re.test(source)) bundledUniComponents.add(component.label)
			}
		}
		for (const pattern of FORBIDDEN_TEXT_PATTERNS) {
			if (pattern.re.test(source)) errors.push(`${pattern.label} reference found in ${relative}`)
		}
	}
	if (!previewOriginFoundInScript) {
		errors.push(
			`H5 JavaScript bundle must include the public HTML preview origin: ${PUBLIC_HTML_PREVIEW_ORIGIN}`)
	}
	if (!apiKeyUlidContractFoundInScript) {
		errors.push(
			'H5 JavaScript bundle must include the 26-character API Key ULID contract')
	}
	for (const component of REQUIRED_UNI_COMPONENT_IMPLEMENTATIONS) {
		if (!bundledUniComponents.has(component.label)) {
			errors.push(
				`H5 JavaScript bundle must include the ${component.label} implementation`)
		}
	}

	return { root, errors }
}

function main() {
	const explicitDir = process.argv.includes('--dir')
		? process.argv[process.argv.indexOf('--dir') + 1]
		: process.argv[2]
	const result = verifyH5ReleaseArtifacts({ root: explicitDir })
	if (!result.errors.length) {
		console.log(`H5 release artifact verified: ${result.root}`)
		return
	}
	for (const error of result.errors) console.error(error)
	process.exitCode = 1
}

if (require.main === module) main()

module.exports = { verifyH5ReleaseArtifacts }
