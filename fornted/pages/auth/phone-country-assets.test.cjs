const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const frontendRoot = path.resolve(__dirname, '../..')
const countriesSource = fs.readFileSync(
	path.resolve(frontendRoot, 'common/shared-auth/phone-countries.js'),
	'utf8'
)

function collectFlagPaths() {
	return Array.from(
		new Set(
			Array.from(countriesSource.matchAll(/"flag":\s*"\/static\/phone-country-flags\/([^"]+)"/g))
				.map(match => match[1])
		)
	).sort()
}

test('all country flag paths resolve to bundled static assets', () => {
	const flagNames = collectFlagPaths()

	assert.ok(flagNames.length > 200)

	for (const flagName of flagNames) {
		const flagPath = path.join(frontendRoot, 'static/phone-country-flags', flagName)
		assert.ok(fs.existsSync(flagPath), `Missing country flag asset: ${flagName}`)
	}
})
