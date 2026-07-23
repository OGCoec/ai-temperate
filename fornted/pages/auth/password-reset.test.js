describe('pages/auth/password-reset.vue', () => {
	let page

	beforeAll(async () => {
		page = await program.reLaunch('/pages/auth/password-reset')
		await page.waitFor(500)
	})

	it('validates the active recovery identifier before creating a flow', async () => {
		await page.callMethod('start')
		let fieldErrors = await page.data('fieldErrors')
		expect(fieldErrors.email).toBe('请输入有效邮箱。')

		await page.callMethod('changeChannel', 'SMS')
		await page.callMethod('start')
		fieldErrors = await page.data('fieldErrors')
		expect(fieldErrors.phoneNumber).toBe('请输入与所选国家或地区匹配的有效本地手机号。')
		expect(await page.data('busy')).toBe(false)
	})

	it('keeps a manual country choice while switching recovery channels', async () => {
		await page.callMethod('handleCountryUserSelection', 'jp-81')
		await page.callMethod('changeChannel', 'EMAIL')
		await page.callMethod('changeChannel', 'SMS')

		expect(await page.data('countryId')).toBe('jp-81')
		const dialCode = await page.$('.dial-prefix')
		expect(await dialCode.text()).toBe('+81')
	})
})
