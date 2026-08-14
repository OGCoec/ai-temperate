export const API_KEY_EXPIRY_OPTION = Object.freeze({
	PERMANENT: 'permanent',
	ONE_DAY: 'one_day',
	THREE_DAYS: 'three_days',
	ONE_WEEK: 'one_week',
	ONE_MONTH: 'one_month',
	THREE_MONTHS: 'three_months',
	ONE_YEAR: 'one_year',
	CUSTOM: 'custom'
})

const PRESET_OFFSETS = Object.freeze({
	[API_KEY_EXPIRY_OPTION.ONE_DAY]: Object.freeze({ days: 1 }),
	[API_KEY_EXPIRY_OPTION.THREE_DAYS]: Object.freeze({ days: 3 }),
	[API_KEY_EXPIRY_OPTION.ONE_WEEK]: Object.freeze({ days: 7 }),
	[API_KEY_EXPIRY_OPTION.ONE_MONTH]: Object.freeze({ months: 1 }),
	[API_KEY_EXPIRY_OPTION.THREE_MONTHS]: Object.freeze({ months: 3 }),
	[API_KEY_EXPIRY_OPTION.ONE_YEAR]: Object.freeze({ months: 12 })
})

function expiryError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}

function validDate(value) {
	const date = value instanceof Date ? new Date(value.getTime()) : new Date(value)
	if (!Number.isFinite(date.getTime())) {
		throw expiryError('API_KEY_EXPIRY_DATE_INVALID', '请输入有效的过期日期。')
	}
	return date
}

function pad(value) {
	return String(value).padStart(2, '0')
}

function localDateFromDate(date) {
	return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function dateParts(localDate) {
	const canonical = parseExpiryDateInput(localDate)
	const [year, month, day] = canonical.split('-').map(Number)
	return { canonical, year, month, day }
}

function localEndOfDay(localDate) {
	const { year, month, day } = dateParts(localDate)
	return new Date(year, month - 1, day, 23, 59, 59, 999)
}

function requireFuture(localDate, now) {
	if (localEndOfDay(localDate).getTime() <= validDate(now).getTime()) {
		throw expiryError('API_KEY_EXPIRY_DATE_NOT_FUTURE', '过期日期必须晚于当前时间。')
	}
}

function daysInMonth(year, monthIndex) {
	return new Date(year, monthIndex + 1, 0, 12).getDate()
}

function addCalendarMonthsClamped(source, amount) {
	const absoluteMonth = source.getFullYear() * 12 + source.getMonth() + amount
	const year = Math.floor(absoluteMonth / 12)
	const monthIndex = ((absoluteMonth % 12) + 12) % 12
	const day = Math.min(source.getDate(), daysInMonth(year, monthIndex))
	return new Date(year, monthIndex, day, 12)
}

function selection(option, localDate) {
	return Object.freeze({ option, localDate })
}

export function createPermanentExpirySelection() {
	return selection(API_KEY_EXPIRY_OPTION.PERMANENT, null)
}

export function createPresetExpirySelection(option, now = new Date()) {
	const offset = PRESET_OFFSETS[option]
	if (!offset) {
		throw expiryError('API_KEY_EXPIRY_OPTION_INVALID', '请选择有效的过期方式。')
	}
	const source = validDate(now)
	let target
	if (offset.days) {
		target = new Date(
			source.getFullYear(),
			source.getMonth(),
			source.getDate() + offset.days,
			12)
	} else {
		target = addCalendarMonthsClamped(source, offset.months)
	}
	return selection(option, localDateFromDate(target))
}

export function parseExpiryDateInput(input) {
	const value = typeof input === 'string' ? input.trim() : ''
	const match = value.match(/^(\d{4})-(\d{2})-(\d{2})$/)
		|| value.match(/^(\d{4})\/(\d{2})\/(\d{2})$/)
		|| value.match(/^(\d{4})年(\d{1,2})月(\d{1,2})(?:日|号)$/)
	if (!match) {
		throw expiryError('API_KEY_EXPIRY_DATE_INVALID', '请输入有效的过期日期。')
	}
	const year = Number(match[1])
	const month = Number(match[2])
	const day = Number(match[3])
	if (year < 1000 || year > 9999 || month < 1 || month > 12 || day < 1 || day > 31) {
		throw expiryError('API_KEY_EXPIRY_DATE_INVALID', '请输入有效的过期日期。')
	}
	const candidate = new Date(year, month - 1, day, 12)
	if (candidate.getFullYear() !== year
		|| candidate.getMonth() !== month - 1
		|| candidate.getDate() !== day) {
		throw expiryError('API_KEY_EXPIRY_DATE_INVALID', '请输入有效的过期日期。')
	}
	return `${year}-${pad(month)}-${pad(day)}`
}

export function createCustomExpirySelection(input, now = new Date()) {
	const localDate = parseExpiryDateInput(input)
	requireFuture(localDate, now)
	return selection(API_KEY_EXPIRY_OPTION.CUSTOM, localDate)
}

export function expirySelectionFromExpiresAt(expiresAt) {
	if (expiresAt == null) return createPermanentExpirySelection()
	if (typeof expiresAt !== 'string') {
		throw expiryError('API_KEY_EXPIRY_DATE_INVALID', 'API Key 过期时间无效。')
	}
	const date = validDate(expiresAt)
	return selection(API_KEY_EXPIRY_OPTION.CUSTOM, localDateFromDate(date))
}

export function expiresAtFromExpirySelection(value, now = new Date()) {
	if (value?.option === API_KEY_EXPIRY_OPTION.PERMANENT) return null
	if (!value || !Object.hasOwn(PRESET_OFFSETS, value.option)
		&& value.option !== API_KEY_EXPIRY_OPTION.CUSTOM) {
		throw expiryError('API_KEY_EXPIRY_OPTION_INVALID', '请选择有效的过期方式。')
	}
	const localDate = parseExpiryDateInput(value.localDate)
	requireFuture(localDate, now)
	return localEndOfDay(localDate).toISOString()
}

export function formatExpiryDateZhCn(localDate) {
	const { year, month, day } = dateParts(localDate)
	return `${year}年${month}月${day}日`
}

export function buildExpiryCalendarMonth(
	year,
	monthIndex,
	now = new Date(),
	selectedLocalDate = null) {
	if (!Number.isInteger(year) || year < 1000 || year > 9999
		|| !Number.isInteger(monthIndex) || monthIndex < 0 || monthIndex > 11) {
		throw expiryError('API_KEY_EXPIRY_DATE_INVALID', '日历月份无效。')
	}
	const current = validDate(now)
	const today = localDateFromDate(current)
	const first = new Date(year, monthIndex, 1, 12)
	const start = new Date(year, monthIndex, 1 - first.getDay(), 12)
	return Object.freeze(Array.from({ length: 42 }, (_, index) => {
		const date = new Date(
			start.getFullYear(),
			start.getMonth(),
			start.getDate() + index,
			12)
		const localDate = localDateFromDate(date)
		return Object.freeze({
			localDate,
			day: date.getDate(),
			inVisibleMonth: date.getFullYear() === year && date.getMonth() === monthIndex,
			isToday: localDate === today,
			selected: localDate === selectedLocalDate,
			disabled: localEndOfDay(localDate).getTime() <= current.getTime()
		})
	}))
}
