const fs = require('node:fs')
const path = require('node:path')

const DEFAULT_H5_RELEASE_ROOT = path.resolve(
	__dirname,
	'..',
	'unpackage',
	'dist',
	'build',
	'h5'
)
const LEGACY_WEB_RELEASE_ROOT = path.resolve(
	__dirname,
	'..',
	'unpackage',
	'dist',
	'build',
	'web'
)

const PUBLIC_ROOTS = new Set(['assets', 'static'])
const PUBLIC_EXTENSIONS = new Set([
	'.avif',
	'.css',
	'.gif',
	'.ico',
	'.jpeg',
	'.jpg',
	'.js',
	'.json',
	'.png',
	'.svg',
	'.txt',
	'.ttf',
	'.wasm',
	'.webp',
	'.woff',
	'.woff2'
])

function normalizeRelative(value) {
	return value.split(path.sep).join('/')
}

function isTestArtifact(relative) {
	return relative.split('/').includes('__tests__')
		|| /(?:^|\/)[^/]+\.(?:test|spec)\.[^/]+$/i.test(relative)
}

function walk(root, directory, assets) {
	for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
		if (entry.isSymbolicLink()) {
			throw new Error(`H5 public asset directory must not contain symlinks: ${entry.name}`)
		}
		const absolute = path.join(directory, entry.name)
		if (entry.isDirectory()) {
			walk(root, absolute, assets)
			continue
		}
		if (!entry.isFile()) continue
		const relative = normalizeRelative(path.relative(root, absolute))
		const [publicRoot] = relative.split('/', 1)
		if (!PUBLIC_ROOTS.has(publicRoot)) continue
		if (!PUBLIC_EXTENSIONS.has(path.extname(relative).toLowerCase())) continue
		if (isTestArtifact(relative)) continue
		if (!/^[A-Za-z0-9@._/-]+$/.test(relative)) {
			throw new Error(`H5 public asset path is not canonical ASCII: ${relative}`)
		}
		assets.push(`/${relative}`)
	}
}

function collectPublicAssetPaths(root) {
	const resolvedRoot = path.resolve(root)
	if (!fs.existsSync(resolvedRoot)) {
		throw new Error(`H5 release directory does not exist: ${resolvedRoot}`)
	}
	const assets = []
	walk(resolvedRoot, resolvedRoot, assets)
	return [...new Set(assets)].sort()
}

function renderAssetManifest(assetPaths) {
	const sorted = [...new Set(assetPaths)].sort()
	return [
		'// 本文件由前端生产产物生成；Worker 只允许这些精确路径访问 Pages。',
		`export const H5_ASSET_PATHS = Object.freeze(${JSON.stringify(sorted, null, 2)})`,
		''
	].join('\n')
}

function argument(name) {
	const index = process.argv.indexOf(name)
	return index >= 0 ? process.argv[index + 1] : undefined
}

function assertSupportedH5ReleaseRoot(root, options = {}) {
	const resolvedRoot = path.resolve(root)
	const legacySuffix = path.normalize(
		path.join('unpackage', 'dist', 'build', 'web')
	).toLowerCase()
	if (resolvedRoot.toLowerCase().endsWith(legacySuffix)) {
		throw new Error(
			`Legacy web output cannot be published; use the canonical H5 release directory: ${DEFAULT_H5_RELEASE_ROOT}`
		)
	}
	if (!options.allowNonCanonicalRoot
		&& resolvedRoot.toLowerCase() !== DEFAULT_H5_RELEASE_ROOT.toLowerCase()) {
		throw new Error(
			`Only the canonical H5 release directory can be published: ${DEFAULT_H5_RELEASE_ROOT}`
		)
	}
	return resolvedRoot
}

function main() {
	const root = assertSupportedH5ReleaseRoot(
		argument('--dir') || DEFAULT_H5_RELEASE_ROOT
	)
	const output = path.resolve(argument('--out')
		|| path.join(
			__dirname,
			'..',
			'..',
			'cloudflare',
			'api-gateway',
			'src',
			'generated',
			'h5-assets.js'))
	const source = renderAssetManifest(collectPublicAssetPaths(root))
	fs.mkdirSync(path.dirname(output), { recursive: true })
	fs.writeFileSync(output, source, 'utf8')
	console.log(`Generated exact H5 edge asset manifest: ${output}`)
}

if (require.main === module) main()

module.exports = {
	DEFAULT_H5_RELEASE_ROOT,
	LEGACY_WEB_RELEASE_ROOT,
	assertSupportedH5ReleaseRoot,
	collectPublicAssetPaths,
	renderAssetManifest
}
