const fs = require('node:fs')
const path = require('node:path')

const DEFAULT_SOURCE_DIRS = ['common', 'components', 'pages']
const DEFAULT_ROOT_FILES = ['App.vue', 'main.js']
const IMPORT_RE = /import\s*\{([\s\S]*?)\}\s*from\s*['"]([^'"]+)['"]/g
const DECLARED_EXPORT_RE = /export\s+(?:async\s+)?(?:function|class|const|let|var)\s+([A-Za-z_$][\w$]*)/g
const NAMED_EXPORT_RE = /export\s*\{([\s\S]*?)\}/g

function normalizeRelative(value) {
	return value.split(path.sep).join('/')
}

function listSourceFiles(root) {
	const files = []
	for (const dir of DEFAULT_SOURCE_DIRS) {
		const absolute = path.join(root, dir)
		if (fs.existsSync(absolute)) walk(absolute, files)
	}
	for (const file of DEFAULT_ROOT_FILES) {
		const absolute = path.join(root, file)
		if (fs.existsSync(absolute)) files.push(absolute)
	}
	return files.filter(file => /\.(?:js|vue)$/.test(file))
}

function walk(dir, files) {
	for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
		if (entry.name === 'node_modules' || entry.name === 'unpackage' || entry.name.startsWith('.')) continue
		const absolute = path.join(dir, entry.name)
		if (entry.isDirectory()) {
			walk(absolute, files)
		} else if (entry.isFile()) {
			files.push(absolute)
		}
	}
}

function read(file) {
	return fs.readFileSync(file, 'utf8')
}

function parseImportedNames(importBlock) {
	return importBlock
		.split(',')
		.map(part => part.trim())
		.filter(Boolean)
		.map(part => part.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/g, '').trim())
		.filter(Boolean)
		.map(part => part.split(/\s+as\s+/i)[0].trim())
		.filter(Boolean)
}

function parseExportNames(source) {
	const exports = new Set()
	for (const match of source.matchAll(DECLARED_EXPORT_RE)) exports.add(match[1])
	for (const match of source.matchAll(NAMED_EXPORT_RE)) {
		for (const rawPart of match[1].split(',')) {
			const part = rawPart.trim()
			if (!part) continue
			const aliasMatch = part.match(/\s+as\s+([A-Za-z_$][\w$]*)$/)
			const nameMatch = part.match(/^([A-Za-z_$][\w$]*)/)
			if (aliasMatch) {
				exports.add(aliasMatch[1])
			} else if (nameMatch) {
				exports.add(nameMatch[1])
			}
		}
	}
	return exports
}

function resolveLocalJsImport(root, sourceFile, specifier) {
	if (specifier.startsWith('@/')) {
		return path.resolve(root, specifier.slice(2))
	}
	if (specifier.startsWith('./') || specifier.startsWith('../')) {
		return path.resolve(path.dirname(sourceFile), specifier)
	}
	return null
}

function verifyLocalEsmContracts(options = {}) {
	const root = path.resolve(options.root || process.cwd())
	const errors = []
	const imports = []
	const modules = new Map()

	for (const sourceFile of listSourceFiles(root)) {
		const source = read(sourceFile)
		const sourceRelative = normalizeRelative(path.relative(root, sourceFile))
		for (const match of source.matchAll(IMPORT_RE)) {
			const specifier = match[2]
			const target = resolveLocalJsImport(root, sourceFile, specifier)
			if (!target || path.extname(target) !== '.js') continue

			const importedNames = parseImportedNames(match[1])
			const targetRelative = normalizeRelative(path.relative(root, target))
			const record = { sourceRelative, specifier, targetRelative, importedNames }
			imports.push(record)

			if (!fs.existsSync(target)) {
				errors.push(`${sourceRelative} imports missing module ${specifier}`)
				continue
			}

			let moduleRecord = modules.get(targetRelative)
			if (!moduleRecord) {
				moduleRecord = {
					targetRelative,
					exports: parseExportNames(read(target))
				}
				modules.set(targetRelative, moduleRecord)
			}

			for (const importedName of importedNames) {
				if (!moduleRecord.exports.has(importedName)) {
					errors.push(
						`${sourceRelative} imports ${importedName} from ${specifier}, but ${targetRelative} does not export it`
					)
				}
			}
		}
	}

	return { errors, imports, modules }
}

function main() {
	const result = verifyLocalEsmContracts({ root: path.resolve(__dirname, '..') })
	if (!result.errors.length) {
		console.log(`Local ESM contracts verified: ${result.imports.length} named imports checked.`)
		return
	}
	for (const error of result.errors) console.error(error)
	process.exitCode = 1
}

if (require.main === module) main()

module.exports = { verifyLocalEsmContracts }
