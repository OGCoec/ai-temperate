const assert = require('node:assert/strict')
const path = require('node:path')
const { pathToFileURL } = require('node:url')
const test = require('node:test')

async function loadModule() {
	const url = pathToFileURL(path.resolve(__dirname, 'api-key-expiry.js'))
	url.searchParams.set('test', `${Date.now()}-${Math.random()}`)
	return import(url.href)
}

test('permanent expiry remains null and never uses a numeric sentinel', async () => {
	const {
		API_KEY_EXPIRY_OPTION,
		createPermanentExpirySelection,
		expiresAtFromExpirySelection
	} = await loadModule()
	const selection = createPermanentExpirySelection()

	assert.deepEqual(selection, {
		option: API_KEY_EXPIRY_OPTION.PERMANENT,
		localDate: null
	})
	assert.equal(expiresAtFromExpirySelection(selection, new Date(2026, 7, 14, 15)), null)
	assert.notEqual(expiresAtFromExpirySelection(selection), -1)
})

test('day and week presets use local calendar days', async () => {
	const { API_KEY_EXPIRY_OPTION, createPresetExpirySelection } = await loadModule()
	const now = new Date(2026, 7, 14, 15, 30)

	assert.equal(createPresetExpirySelection(API_KEY_EXPIRY_OPTION.ONE_DAY, now).localDate, '2026-08-15')
	assert.equal(createPresetExpirySelection(API_KEY_EXPIRY_OPTION.THREE_DAYS, now).localDate, '2026-08-17')
	assert.equal(createPresetExpirySelection(API_KEY_EXPIRY_OPTION.ONE_WEEK, now).localDate, '2026-08-21')
})

test('preset expiry serialization works when Android does not provide Object.hasOwn', async () => {
	const originalHasOwn = Object.hasOwn
	try {
		Object.hasOwn = undefined
		const {
			API_KEY_EXPIRY_OPTION,
			createPresetExpirySelection,
			expiresAtFromExpirySelection
		} = await loadModule()
		const now = new Date(2026, 7, 14, 15, 30)
		const selection = createPresetExpirySelection(API_KEY_EXPIRY_OPTION.ONE_WEEK, now)
		const localExpiry = new Date(expiresAtFromExpirySelection(selection, now))

		assert.equal(localExpiry.getFullYear(), 2026)
		assert.equal(localExpiry.getMonth(), 7)
		assert.equal(localExpiry.getDate(), 21)
		assert.equal(localExpiry.getHours(), 23)
	} finally {
		Object.hasOwn = originalHasOwn
	}
})

test('month and year presets clamp to the last valid target day', async () => {
	const { API_KEY_EXPIRY_OPTION, createPresetExpirySelection } = await loadModule()

	assert.equal(
		createPresetExpirySelection(API_KEY_EXPIRY_OPTION.ONE_MONTH, new Date(2026, 0, 31, 9)).localDate,
		'2026-02-28')
	assert.equal(
		createPresetExpirySelection(API_KEY_EXPIRY_OPTION.ONE_MONTH, new Date(2024, 0, 31, 9)).localDate,
		'2024-02-29')
	assert.equal(
		createPresetExpirySelection(API_KEY_EXPIRY_OPTION.THREE_MONTHS, new Date(2026, 10, 30, 9)).localDate,
		'2027-02-28')
	assert.equal(
		createPresetExpirySelection(API_KEY_EXPIRY_OPTION.ONE_YEAR, new Date(2024, 1, 29, 9)).localDate,
		'2025-02-28')
})

test('manual date input accepts only the four declared unambiguous formats', async () => {
	const { parseExpiryDateInput } = await loadModule()

	for (const input of ['2026-08-20', '2026/08/20', '2026年8月20日', '2026年8月20号']) {
		assert.equal(parseExpiryDateInput(input), '2026-08-20')
	}
	for (const input of ['', '2026-8', '26-08-20', '2026-02-30', '-1', '1787200000000']) {
		assert.throws(
			() => parseExpiryDateInput(input),
			error => error.code === 'API_KEY_EXPIRY_DATE_INVALID')
	}
})

test('custom dates must end after now and serialize at local end of day', async () => {
	const {
		createCustomExpirySelection,
		expiresAtFromExpirySelection,
		formatExpiryDateZhCn
	} = await loadModule()
	const now = new Date(2026, 7, 14, 15, 30)
	const selection = createCustomExpirySelection('2026年8月20号', now)
	const expiresAt = expiresAtFromExpirySelection(selection, now)
	const localExpiry = new Date(expiresAt)

	assert.equal(selection.localDate, '2026-08-20')
	assert.equal(formatExpiryDateZhCn(selection.localDate), '2026年8月20日')
	assert.equal(localExpiry.getFullYear(), 2026)
	assert.equal(localExpiry.getMonth(), 7)
	assert.equal(localExpiry.getDate(), 20)
	assert.equal(localExpiry.getHours(), 23)
	assert.equal(localExpiry.getMinutes(), 59)
	assert.equal(localExpiry.getSeconds(), 59)
	assert.equal(localExpiry.getMilliseconds(), 999)
	assert.throws(
		() => createCustomExpirySelection('2026-08-13', now),
		error => error.code === 'API_KEY_EXPIRY_DATE_NOT_FUTURE')
})

test('server expiry values return permanent or custom local-date selections', async () => {
	const { API_KEY_EXPIRY_OPTION, expirySelectionFromExpiresAt } = await loadModule()
	const serverExpiry = new Date(2026, 7, 20, 23, 59, 59, 999).toISOString()

	assert.deepEqual(expirySelectionFromExpiresAt(null), {
		option: API_KEY_EXPIRY_OPTION.PERMANENT,
		localDate: null
	})
	assert.deepEqual(expirySelectionFromExpiresAt(serverExpiry), {
		option: API_KEY_EXPIRY_OPTION.CUSTOM,
		localDate: '2026-08-20'
	})
})

test('calendar month contains forty-two stable cells and disables elapsed dates', async () => {
	const { buildExpiryCalendarMonth } = await loadModule()
	const cells = buildExpiryCalendarMonth(2026, 7, new Date(2026, 7, 14, 15, 30), '2026-08-20')

	assert.equal(cells.length, 42)
	assert.equal(cells[0].localDate, '2026-07-26')
	assert.equal(cells[41].localDate, '2026-09-05')
	assert.equal(cells.find(cell => cell.localDate === '2026-08-13').disabled, true)
	assert.equal(cells.find(cell => cell.localDate === '2026-08-14').isToday, true)
	assert.equal(cells.find(cell => cell.localDate === '2026-08-14').disabled, false)
	assert.equal(cells.find(cell => cell.localDate === '2026-08-20').selected, true)
	assert.equal(cells.find(cell => cell.localDate === '2026-07-31').inVisibleMonth, false)
})
