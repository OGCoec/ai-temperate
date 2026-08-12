function twoDigits(value) {
	return value < 10 ? `0${value}` : String(value)
}

/**
 * 使用设备本地时区生成稳定的中文日期时间；无效输入统一返回 null，避免中断页面渲染。
 */
export function formatLocalDateTimeZhCn(value) {
	if (value == null || value === '') return null
	if (typeof value !== 'string'
		&& typeof value !== 'number'
		&& !(value instanceof Date)) {
		return null
	}
	try {
		const date = value instanceof Date
			? new Date(value.getTime())
			: new Date(value)
		const timestamp = date.getTime()
		if (timestamp !== timestamp) return null
		return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${twoDigits(date.getHours())}:${twoDigits(date.getMinutes())}`
	} catch (_) {
		return null
	}
}
