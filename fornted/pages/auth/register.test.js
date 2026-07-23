describe('pages/auth/register.vue', () => {
	let page

	beforeAll(async () => {
		page = await program.reLaunch('/pages/auth/register')
		await page.waitFor(500)
	})

	it('shows field errors before starting registration with empty identifiers', async () => {
		await page.callMethod('start')

		const fieldErrors = await page.data('fieldErrors')
		expect(fieldErrors.email).toBe('请输入有效邮箱。')
		expect(fieldErrors.phoneNumber).toBe('请输入与所选国家或地区匹配的有效本地手机号。')
		expect(await page.data('busy')).toBe(false)
	})

	it('applies a manual country choice immediately', async () => {
		await page.callMethod('handleCountryUserSelection', 'ca-1')

		expect(await page.data('countryId')).toBe('ca-1')
		expect(await page.data('countryResolving')).toBe(false)
		const dialCode = await page.$('.dial-prefix')
		expect(await dialCode.text()).toBe('+1')
	})

	it('accepts only human-verified server contacts as the authoritative identity', async () => {
		await page.setData({
			registrationIdentity: { email: '', phoneE164: '' }
		})
		await page.callMethod('applyRegistrationIdentity', {
			humanVerified: false,
			email: 'ignored@example.test',
			phoneE164: '+14155550100'
		})

		expect(await page.data('registrationIdentity')).toEqual({
			email: '',
			phoneE164: ''
		})

		await page.callMethod('applyRegistrationIdentity', {
			humanVerified: true,
			email: 'server@example.test',
			phoneE164: '+8613800138000'
		})

		expect(await page.data('registrationIdentity')).toEqual({
			email: 'server@example.test',
			phoneE164: '+8613800138000'
		})
	})
})
