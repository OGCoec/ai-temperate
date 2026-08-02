const fs = require('node:fs')
const path = require('node:path')

const REQUIRED_FILES = ['index.html', '_headers', '_redirects']
const FORBIDDEN_FILE_PATTERNS = [
	{ re: /\.vue(?:$|[?#])/, label: 'Vue source module' },
	{ re: /(?:^|\/)node_modules(?:\/|$)/, label: 'node_modules content' },
	{ re: /(?:^|\/)\.env(?:\.|$)/, label: 'environment file' }
]
const FORBIDDEN_TEXT_PATTERNS = [
	{ re: /\/@vite\/client|@vite\/client|__vite_ping|import\.meta\.hot/, label: 'Vite development module' },
	{ re: /\/@fs\//, label: 'Vite file-system module' },
	{ re: /\.vue(?:\?|['"])/, label: 'Vue source module' },
	{ re: /pages-json-js/, label: 'uni-app development route module' }
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

function verifyH5ReleaseArtifacts(options = {}) {
	const root = path.resolve(options.root || path.join(__dirname, '..', 'unpackage', 'dist', 'build', 'h5'))
	const errors = []

	if (!fs.existsSync(root)) {
		return { root, errors: [`H5 release directory does not exist: ${root}`] }
	}

	for (const relativePath of REQUIRED_FILES) {
		if (!fs.existsSync(path.join(root, relativePath))) errors.push(`Missing required release file: ${relativePath}`)
	}

	verifyHeaders(root, errors)

	const files = []
	walk(root, files)
	for (const file of files) {
		const relative = normalizeRelative(path.relative(root, file))
		for (const pattern of FORBIDDEN_FILE_PATTERNS) {
			if (pattern.re.test(relative)) errors.push(`${pattern.label} must not be published: ${relative}`)
		}

		if (!/\.(?:html|js|css|json|map|txt|svg)$/.test(relative)) continue
		const source = readIfExists(file)
		for (const pattern of FORBIDDEN_TEXT_PATTERNS) {
			if (pattern.re.test(source)) errors.push(`${pattern.label} reference found in ${relative}`)
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
