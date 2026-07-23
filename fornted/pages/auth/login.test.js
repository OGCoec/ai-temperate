describe('pages/auth/login.vue', () => {
	let page

	beforeAll(async () => {
		page = await program.reLaunch('/pages/auth/login')
		await page.waitFor(500)
	})

	it('keeps the selected country while switching login methods', async () => {
		await page.callMethod('changeIdentifierType', 'PHONE')
		const trigger = await page.$('.country-trigger')
		await trigger.tap()
		await page.waitFor(300)

		const unitedStates = await page.$('#country-us-1')
		await unitedStates.tap()
		await page.waitFor(200)

		expect(await page.data('countryId')).toBe('us-1')

		await page.callMethod('changeMethod', 'EMAIL_CODE')
		await page.callMethod('changeMethod', 'SMS_CODE')

		expect(await page.data('countryId')).toBe('us-1')
		const dialCode = await page.$('.country-trigger .country-code')
		expect(await dialCode.text()).toBe('+1')
	})

	it('shows a field error before submitting an invalid email login', async () => {
		await page.callMethod('changeMethod', 'PASSWORD')
		await page.callMethod('changeIdentifierType', 'EMAIL')
		await page.callMethod('passwordLogin')

		const fieldErrors = await page.data('fieldErrors')
		expect(fieldErrors.email).toBe('请输入有效邮箱。')
		expect(await page.data('busy')).toBe(false)
	})
})
