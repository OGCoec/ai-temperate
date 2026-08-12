function arrayLength(value) {
	const length = Number(value)
	if (!length || length < 0) return 0
	return Math.min(Math.floor(length), Number.MAX_SAFE_INTEGER)
}

function appArrayFlatMap(callback, thisArg) {
	if (this == null) throw new TypeError('Array.prototype.flatMap called on null or undefined')
	if (typeof callback !== 'function') throw new TypeError('flatMap callback must be a function')
	const source = Object(this)
	const length = arrayLength(source.length)
	const result = []
	for (let index = 0; index < length; index += 1) {
		if (!(index in source)) continue
		const mapped = callback.call(thisArg, source[index], index, source)
		if (!Array.isArray(mapped)) {
			result.push(mapped)
			continue
		}
		for (let mappedIndex = 0; mappedIndex < mapped.length; mappedIndex += 1) {
			if (mappedIndex in mapped) result.push(mapped[mappedIndex])
		}
	}
	return result
}

function appArrayAt(relativeIndex) {
	if (this == null) throw new TypeError('Array.prototype.at called on null or undefined')
	const source = Object(this)
	const length = arrayLength(source.length)
	let index = Number(relativeIndex)
	if (Number.isNaN(index)) index = 0
	else if (Number.isFinite(index)) index = Math.trunc(index)
	if (index < 0) index += length
	if (index < 0 || index >= length) return undefined
	return source[index]
}

function installArrayMethod(prototype, name, implementation) {
	if (typeof prototype?.[name] === 'function') return false
	try {
		Object.defineProperty(prototype, name, {
			configurable: true,
			writable: true,
			value: implementation
		})
	} catch (_) {
		prototype[name] = implementation
	}
	return true
}

/**
 * HBuilderX 的 Android app-service 不会为第三方依赖注入现代 Array 方法。
 * Shiki 4.4.1 首次构建 TextMate grammar 时需要 flatMap，流式视图合并时还需要 at。
 */
export function ensureAppAiCodeRuntimeCompatibility(prototype = Array.prototype) {
	return {
		flatMap: installArrayMethod(prototype, 'flatMap', appArrayFlatMap),
		at: installArrayMethod(prototype, 'at', appArrayAt)
	}
}
