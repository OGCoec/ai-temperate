const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const modulePath = path.resolve(__dirname, 'date-time.js')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadDateTime() {
	const source = fs.readFileSync(modulePath, 'utf8')
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

async function withoutGlobal(name, callback) {
	const descriptor = Object.getOwnPropertyDescriptor(globalThis, name)
	try {
		Object.defineProperty(globalThis, name, {
			configurable: true,
			writable: true,
			value: undefined
		})
		return await callback()
	} finally {
		if (descriptor) Object.defineProperty(globalThis, name, descriptor)
		else delete globalThis[name]
	}
}

test('formats local date and time without Intl', async () => {
	await withoutGlobal('Intl', async () => {
		const { formatLocalDateTimeZhCn } = await loadDateTime()
		const local = new Date(2026, 7, 18, 6, 9, 45)
		assert.equal(formatLocalDateTimeZhCn(local), '2026年8月18日 06:09')
		assert.equal(formatLocalDateTimeZhCn(local.getTime()), '2026年8月18日 06:09')
		assert.equal(formatLocalDateTimeZhCn(local.toISOString()), '2026年8月18日 06:09')
	})
})

test('returns null for missing, invalid, and exceptional values', async () => {
	const { formatLocalDateTimeZhCn } = await loadDateTime()
	const exceptional = {
		valueOf() { throw new Error('cannot convert') },
		toString() { throw new Error('cannot convert') }
	}

	assert.equal(formatLocalDateTimeZhCn(null), null)
	assert.equal(formatLocalDateTimeZhCn(undefined), null)
	assert.equal(formatLocalDateTimeZhCn(''), null)
	assert.equal(formatLocalDateTimeZhCn('not-a-date'), null)
	assert.equal(formatLocalDateTimeZhCn(false), null)
	assert.equal(formatLocalDateTimeZhCn({}), null)
	assert.equal(formatLocalDateTimeZhCn([]), null)
	assert.equal(formatLocalDateTimeZhCn(Symbol('date')), null)
	assert.equal(formatLocalDateTimeZhCn(exceptional), null)
})

test('keeps the platform helper free of locale and browser-only globals', () => {
	const source = fs.readFileSync(modulePath, 'utf8')
	assert.doesNotMatch(source, /\bIntl\b|toLocaleString|\bwindow\b|\bdocument\b/)
})
