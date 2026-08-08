const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'ai-conversation-image-preloader.js'),
		'utf8'
	)
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

test('resolves H5 image only after the browser load event', async () => {
	const module = await loadModule()
	let image
	const pending = module.preloadConversationImage('https://oss.example.test/image.png', {
		platform: 'H5',
		imageFactory: () => (image = {})
	})

	image.onload()

	assert.deepEqual(await pending, {
		displayUrl: 'https://oss.example.test/image.png',
		width: 0,
		height: 0
	})
})

test('uses downloaded Android local path only after getImageInfo succeeds', async () => {
	const module = await loadModule()
	const result = await module.preloadConversationImage(
		'https://oss.example.test/image.png', {
			platform: 'ANDROID',
			getImageInfo: ({ success }) => success({
				path: '_doc/uniapp_temp/image.png',
				width: 2048,
				height: 1024
			})
		})

	assert.deepEqual(result, {
		displayUrl: '_doc/uniapp_temp/image.png',
		width: 2048,
		height: 1024
	})
})

test('rejects preload failures so callers can keep the thumbnail', async () => {
	const module = await loadModule()
	await assert.rejects(
		module.preloadConversationImage('https://oss.example.test/image.png', {
			platform: 'ANDROID',
			getImageInfo: ({ fail }) => fail(new Error('download failed'))
		}),
		/download failed/
	)
})
