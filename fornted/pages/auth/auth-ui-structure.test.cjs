const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const frontendRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

function assertInputWrapped(source, id) {
	const inputIndex = source.indexOf(`id="${id}"`)
	assert.notEqual(inputIndex, -1, `Missing input #${id}`)

	const nearbyMarkup = source.slice(Math.max(0, inputIndex - 500), inputIndex)
	assert.match(
		nearbyMarkup,
		/<view\s+class="[^"]*\bauth-control\b[^"]*"/,
		`Input #${id} must be inside an auth-control wrapper`
	)
}

test('auth pages render fields through the shared input shell', () => {
	const files = {
		identifier: read('components/auth/identifier-fields.vue'),
		login: read('pages/auth/login.vue'),
		register: read('pages/auth/register.vue'),
		reset: read('pages/auth/password-reset.vue'),
		passwordFields: read('components/auth/auth-password-fields.vue')
	}

	assertInputWrapped(files.identifier, 'auth-email')
	assertInputWrapped(files.identifier, 'auth-phone')
	assertInputWrapped(files.login, 'auth-login-password')
	assertInputWrapped(files.login, 'auth-login-code')
	assertInputWrapped(files.register, 'auth-register-email')
	assertInputWrapped(files.register, 'auth-register-phone')
	assertInputWrapped(files.register, 'auth-register-email-code')
	assertInputWrapped(files.register, 'auth-register-sms-code')
	assertInputWrapped(files.reset, 'auth-reset-code')
	assertInputWrapped(files.passwordFields, 'auth-new-password')
	assertInputWrapped(files.passwordFields, 'auth-password-confirmation')

	for (const [name, source] of Object.entries(files)) {
		assert.doesNotMatch(source, /class="auth-input"/, `${name} still has a bare auth-input`)
	}
})

test('input-owning auth components carry their own input shell styles', () => {
	const identifier = read('components/auth/identifier-fields.vue')
	const passwordFields = read('components/auth/auth-password-fields.vue')

	assert.match(identifier, /@import ['"]@\/common\/auth\/auth-controls\.scss['"]/)
	assert.match(passwordFields, /@import ['"]@\/common\/auth\/auth-controls\.scss['"]/)
})

test('h5 desktop auth layout centers against the viewport', () => {
	const source = read('common/auth/auth.scss')

	assert.match(source, /\/\*\s*#ifdef H5\s*\*\//)
	assert.match(source, /\.auth-page\s*\{[\s\S]*position:\s*fixed/)
	assert.match(source, /\.auth-page\s*\{[\s\S]*inset:\s*0/)
	assert.match(source, /\.auth-page\s*\{[\s\S]*display:\s*flex/)
	assert.match(source, /\.auth-page\s*\{[\s\S]*align-items:\s*center/)
	assert.match(source, /\.auth-page\s*\{[\s\S]*justify-content:\s*center/)
	assert.match(source, /\.auth-container\s*\{[\s\S]*margin:\s*auto/)
})

test('country picker renders bundled image flags instead of emoji text', () => {
	const source = read('components/auth/phone-country-picker.vue')

	assert.match(source, /<image[\s\S]*:src="currentCountry\.flag"/)
	assert.match(source, /<image[\s\S]*:src="country\.flag"/)
	assert.doesNotMatch(source, /flagEmoji/)
})

test('registration identity summary is read-only and renders a bundled flag image', () => {
	const source = read('components/auth/registration-identity-summary.vue')

	assert.match(source, /<image[\s\S]*:src="phonePresentation\.flag"/)
	assert.match(source, /phonePresentation\.countryName/)
	assert.match(source, /phonePresentation\.countryIso2/)
	assert.match(source, /phonePresentation\.dialCode/)
	assert.match(source, /phonePresentation\.nationalDisplay/)
	assert.doesNotMatch(source, /<input\b/)
	assert.doesNotMatch(source, /<button\b/)
	assert.doesNotMatch(source, /phone-country-picker/)
})

test('phone inputs use tel keyboards and shared country-aware formatting', () => {
	const identifier = read('components/auth/identifier-fields.vue')
	const register = read('pages/auth/register.vue')

	assert.match(identifier, /type="tel"/)
	assert.match(register, /type="tel"/)
	assert.match(identifier, /:value="phoneDisplay"/)
	assert.match(register, /:value="phoneDisplay"/)
	assert.match(identifier, /:key="phoneInputKey"/)
	assert.match(register, /:key="phoneInputKey"/)
	assert.match(identifier, /normalizePhoneInputForCountry/)
	assert.match(register, /normalizePhoneInputForCountry/)
	assert.match(identifier, /normalizePhoneInputForCountry\([\s\S]*this\.phoneDisplay/)
	assert.match(register, /normalizePhoneInputForCountry\([\s\S]*this\.phoneDisplay/)
	assert.match(identifier, /formatLocalPhoneNumberInput/)
	assert.match(register, /formatLocalPhoneNumberInput/)
	assert.match(identifier, /@input="updatePhone"/)
	assert.match(register, /@input="handlePhoneNumberInput"/)
	assert.doesNotMatch(identifier, /type="number"[\s\S]*id="auth-phone"/)
	assert.doesNotMatch(register, /type="number"[\s\S]*id="auth-register-phone"/)
})

test('country search resets stale scroll targets while typing', () => {
	const source = read('components/auth/phone-country-picker.vue')

	assert.match(source, /:key="countryListKey"/)
	assert.match(source, /countryListKey\(\)/)
	assert.match(source, /keyword\(\)\s*\{[\s\S]*clearTimeout\(this\.scrollTimer\)/)
	assert.match(source, /keyword\(\)\s*\{[\s\S]*this\.scrollTarget = ''/)
	assert.match(source, /if \(this\.keyword \|\| !this\.currentCountry\) return/)
})

test('auth phone submissions keep phoneNumber as the cleaned local digits', () => {
	const login = read('pages/auth/login.vue')
	const register = read('pages/auth/register.vue')
	const reset = read('pages/auth/password-reset.vue')

	assert.match(login, /phoneNumber:\s*this\.phoneNumber/)
	assert.match(register, /phoneNumber:\s*this\.phoneNumber/)
	assert.match(reset, /phoneNumber:\s*this\.phoneNumber/)
	assert.doesNotMatch(login, /phoneNumber:\s*formatLocalPhoneNumberInput/)
	assert.doesNotMatch(register, /phoneNumber:\s*formatLocalPhoneNumberInput/)
	assert.doesNotMatch(reset, /phoneNumber:\s*formatLocalPhoneNumberInput/)
})
