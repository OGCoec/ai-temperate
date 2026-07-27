const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const projectRoot = path.resolve(__dirname, '../..')
const source = file => fs.readFileSync(path.join(projectRoot, file), 'utf8')

test('administrator identity form composes the H5-safe email and phone controls', () => {
	const form = source('components/admin/admin-identity-form.vue')

	assert.match(form, /import IdentifierFields from ['"]@\/components\/auth\/identifier-fields\.vue['"]/)
	assert.match(form, /<identifier-fields[\s\S]*type="EMAIL"/)
	assert.match(form, /<identifier-fields[\s\S]*type="PHONE"/)
	assert.match(form, /:country-resolving="countryResolving"/)
	assert.doesNotMatch(form, /class="auth-input"/)
	assert.doesNotMatch(form, /class="phone-control"/)
})

test('administrator verification codes own their H5 control boundaries', () => {
	const fields = source('components/admin/admin-verification-code-fields.vue')

	assert.match(fields, /class="auth-control"/)
	assert.match(fields, /class="auth-control-input"/)
	assert.match(fields, /inputmode="numeric"/)
	assert.match(fields, /maxlength="6"/)
	assert.match(fields, /@import '@\/common\/auth\/auth-controls\.scss'/)
})

test('administrator password setup fields own their H5 control boundaries', () => {
	const fields = source('components/admin/admin-password-fields.vue')

	assert.match(fields, /class="auth-control password-input-wrap"/)
	assert.match(fields, /class="auth-control-input password-input"/)
	assert.match(fields, /class="password-toggle"/)
	assert.match(fields, /@import '@\/common\/auth\/auth-controls\.scss'/)
	assert.doesNotMatch(fields, /class="auth-input"/)
})

test('administrator login password field owns its H5 control boundary', () => {
	const field = source('components/admin/admin-login-password-field.vue')

	assert.match(field, /class="auth-control password-input-wrap"/)
	assert.match(field, /class="auth-control-input password-input"/)
	assert.match(field, /autocomplete="current-password"/)
	assert.match(field, /@import '@\/common\/auth\/auth-controls\.scss'/)
	assert.doesNotMatch(field, /class="auth-input"/)
})

test('administrator page delegates verification and login password rendering to self-styled controls', () => {
	const page = source('pages/index/index.vue')

	assert.match(page, /import VerificationCodeFields from ['"]@\/components\/admin\/admin-verification-code-fields\.vue['"]/)
	assert.match(page, /import LoginPasswordField from ['"]@\/components\/admin\/admin-login-password-field\.vue['"]/)
	assert.match(page, /components: \{ IdentityForm, PasswordFields, VerificationCodeFields, LoginPasswordField \}/)
	assert.match(page, /<verification-code-fields/)
	assert.match(page, /<login-password-field/)
	assert.doesNotMatch(page, /<input id="admin-email-code"/)
	assert.doesNotMatch(page, /<input\s+id="admin-password"/)
})
