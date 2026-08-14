const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'ai-conversation-image-download.js'),
		'utf8'
	)
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

function item(source, overrides = {}) {
	return {
		identity: 'message-1:0',
		outputIndex: 0,
		displaySrc: source,
		attachment: {
			fileName: '',
			contentType: 'image/webp',
			url: source
		},
		...overrides
	}
}

test('keeps a supported generated image filename', async () => {
	const module = await loadModule()
	assert.equal(module.generatedImageFileName(item('https://example.test/a', {
		attachment: { fileName: 'mango.webp', contentType: 'image/webp' }
	})), 'mango.webp')
	assert.equal(module.generatedImageFileName(item('https://example.test/a')), 'generated-1.webp')
})

test('accepts https, blob and supported data image sources for H5', async () => {
	const module = await loadModule()
	assert.equal(module.h5GeneratedImageSource(
		item('https://media.example.test/a.webp')).kind, 'HTTPS')
	assert.equal(module.h5GeneratedImageSource(
		item('blob:https://app.example.test/asset')).kind, 'BLOB')
	assert.equal(module.h5GeneratedImageSource(
		item('data:image/png;base64,aW1hZ2U=')).kind, 'DATA_IMAGE')
})

test('rejects unsafe or unsupported H5 download sources', async () => {
	const module = await loadModule()
	for (const source of [
		'http://media.example.test/a.png',
		'javascript:alert(1)',
		'https://media.example.test/a.png\nSet-Cookie:x',
		'data:image/svg+xml;base64,PHN2Zz4='
	]) {
		assert.throws(() => module.h5GeneratedImageSource(item(source)),
			/Generated image source/)
	}
})

test('accepts only controlled Android local image paths for album saving', async () => {
	const module = await loadModule()
	assert.equal(module.androidGeneratedImageSavePath('_doc/ait-images/generated-1.webp'),
		'_doc/ait-images/generated-1.webp')
	assert.equal(module.androidGeneratedImageSavePath(
		'/data/user/0/com.example/cache/ait-images/generated-1.webp'),
		'/data/user/0/com.example/cache/ait-images/generated-1.webp')
	for (const source of [
		'https://media.example.test/a.webp',
		'data:image/png;base64,aW1hZ2U=',
		'_doc/ait-images/../secret',
		'file:///sdcard/DCIM/a.webp'
	]) {
		assert.throws(() => module.androidGeneratedImageSavePath(source),
			/Android generated image path/)
	}
})
