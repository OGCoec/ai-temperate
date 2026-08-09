const fs = require('node:fs')
const path = require('node:path')
const { pathToFileURL } = require('node:url')

const esmResolverPromise = import(pathToFileURL(
	path.join(__dirname, 'ai-code-test-esm-resolver.mjs')
).href)

function sourceUrl(source) {
	return 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
}

async function moduleUrl(filePath, cache) {
	const absolutePath = path.resolve(filePath)
	if (cache.has(absolutePath)) return cache.get(absolutePath)

	let source = fs.readFileSync(absolutePath, 'utf8')
	const placeholder = Promise.resolve('').then(async () => {
		const staticSpecifiers = Array.from(
			source.matchAll(/\bfrom\s+(['"])([^'"]+)\1/g),
			match => match[2]
		)
		const dynamicSpecifiers = Array.from(
			source.matchAll(/\bimport\(\s*(['"])([^'"]+)\1\s*\)/g),
			match => match[2]
		)
		const specifiers = [...staticSpecifiers, ...dynamicSpecifiers]
		for (const specifier of new Set(specifiers)) {
			let replacement = ''
			if (specifier.startsWith('.')) {
				replacement = await moduleUrl(path.resolve(path.dirname(absolutePath), specifier), cache)
			} else {
				const { resolveEsmSpecifier } = await esmResolverPromise
				replacement = resolveEsmSpecifier(specifier)
			}
			source = source.replaceAll(JSON.stringify(specifier), JSON.stringify(replacement))
			source = source.replaceAll("'" + specifier + "'", JSON.stringify(replacement))
		}
		return sourceUrl(source)
	})
	cache.set(absolutePath, placeholder)
	return placeholder
}

async function loadEsmModule(filePath) {
	const url = await moduleUrl(filePath, new Map())
	return import(url + '#' + Date.now() + '-' + Math.random())
}

module.exports = { loadEsmModule }
