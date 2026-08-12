function scalarValue(value) {
	const type = typeof value
	if (type !== 'string' && type !== 'number' && type !== 'boolean') {
		throw new TypeError('查询参数值必须是字符串、数字或布尔值。')
	}
	return String(value)
}

/**
 * 按调用方给定的顺序编码查询参数；调用方必须显式省略可选参数，避免隐式空值语义。
 */
export function buildQueryString(entries) {
	if (!Array.isArray(entries)) {
		throw new TypeError('查询参数必须使用有序键值对数组。')
	}
	return entries.map((entry) => {
		if (!Array.isArray(entry) || entry.length !== 2) {
			throw new TypeError('每个查询参数必须是包含两个元素的键值对。')
		}
		const key = entry[0]
		if (typeof key !== 'string' || key.length === 0) {
			throw new TypeError('查询参数名称必须是非空字符串。')
		}
		return `${encodeURIComponent(key)}=${encodeURIComponent(scalarValue(entry[1]))}`
	}).join('&')
}
