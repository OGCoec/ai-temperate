const fs = require('node:fs')
const path = require('node:path')

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

function main() {
	const root = path.resolve(argument('--dir')
		|| path.join(__dirname, '..', 'unpackage', 'dist', 'build', 'h5'))
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
	collectPublicAssetPaths,
	renderAssetManifest
}
