const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'user-generated-image-ripple-render.js'),
		'utf8'
	)
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

function frames(count) {
	return Array.from({ length: count }, (_, index) => Object.freeze({
		identity: `image-${index + 1}`,
		source: `https://media.example.test/image-${index + 1}.webp`
	}))
}

function createNativeCanvasHostFixture() {
	const children = []
	const host = {
		children,
		querySelector(selector) {
			if (selector !== 'canvas.generated-image-ripple-native-canvas') return null
			return children.find(child =>
				child.className === 'generated-image-ripple-native-canvas') || null
		},
		appendChild(child) {
			child.parentNode = this
			children.push(child)
			return child
		}
	}
	const documentRef = {
		createElement(tagName) {
			return {
				tagName: String(tagName || '').toUpperCase(),
				className: '',
				attributes: {},
				getContext() {},
				setAttribute(name, value) {
					this.attributes[name] = value
				}
			}
		}
	}
	return { documentRef, host }
}

test('creates one renderer-owned native H5 Canvas and reuses it', async () => {
	const module = await loadModule()
	const { documentRef, host } = createNativeCanvasHostFixture()

	const first = module.ensureGeneratedImageRippleCanvas(host, documentRef)
	const second = module.ensureGeneratedImageRippleCanvas(host, documentRef)

	assert.ok(first)
	assert.equal(first, second)
	assert.equal(host.children.length, 1)
	assert.equal(first.tagName, 'CANVAS')
	assert.equal(first.className, 'generated-image-ripple-native-canvas')
	assert.equal(first.attributes['aria-hidden'], 'true')
	assert.equal(
		first.attributes['data-generated-image-ripple-owner'],
		'renderer-native'
	)
})

test('does not create a native H5 Canvas without a usable host or document', async () => {
	const module = await loadModule()

	assert.equal(module.ensureGeneratedImageRippleCanvas(null, {}), null)
	assert.equal(module.ensureGeneratedImageRippleCanvas({}, {}), null)
})

test('builds every forward and reverse intermediate frame without sampling', async () => {
	const module = await loadModule()
	const source = frames(5)

	assert.deepEqual(
		module.buildGeneratedImageRipplePath(source, 'image-1', 'image-5')
			.map(frame => frame.identity),
		['image-2', 'image-3', 'image-4', 'image-5']
	)
	assert.deepEqual(
		module.buildGeneratedImageRipplePath(source, 'image-5', 'image-1')
			.map(frame => frame.identity),
		['image-4', 'image-3', 'image-2', 'image-1']
	)
})

test('uses one frame for adjacent navigation and none for the active image', async () => {
	const module = await loadModule()
	const source = frames(3)

	assert.deepEqual(
		module.buildGeneratedImageRipplePath(source, 'image-1', 'image-2')
			.map(frame => frame.identity),
		['image-2']
	)
	assert.deepEqual(
		module.buildGeneratedImageRipplePath(source, 'image-2', 'image-2'),
		[]
	)
})

test('keeps identity navigation stable when older images are prepended', async () => {
	const module = await loadModule()
	const source = [
		{ identity: 'older-image', source: 'https://media.example.test/older.webp' },
		...frames(5)
	]

	assert.deepEqual(
		module.buildGeneratedImageRipplePath(source, 'image-1', 'image-5')
			.map(frame => frame.identity),
		['image-2', 'image-3', 'image-4', 'image-5']
	)
})

test('uses the reference timings and cubic easing', async () => {
	const module = await loadModule()

	assert.equal(module.GENERATED_IMAGE_RIPPLE_PATH_DURATION_MS, 1200)
	assert.equal(module.GENERATED_IMAGE_RIPPLE_TEXTURE_DURATION_MS, 1400)
	assert.equal(module.easeOutPower3(0), 0)
	assert.equal(module.easeOutPower3(0.5), 0.875)
	assert.equal(module.easeOutPower3(1), 1)
})

test('selects the interrupted transition origin at the halfway boundary', async () => {
	const module = await loadModule()
	const transition = {
		fromFrame: { identity: 'image-1' },
		toFrame: { identity: 'image-2' }
	}

	assert.equal(module.interruptedRippleOrigin(transition, 0.49).identity, 'image-1')
	assert.equal(module.interruptedRippleOrigin(transition, 0.5).identity, 'image-2')
	assert.equal(module.interruptedRippleOrigin(null, 0.5), null)
})

test('prefers a data image preview for transition textures', async () => {
	const module = await loadModule()
	const item = {
		displaySrc: 'https://media.example.test/final.webp',
		attachment: { url: 'data:image/webp;base64,cHJldmlldw==' }
	}

	assert.equal(
		module.generatedImageRippleSource(item),
		'data:image/webp;base64,cHJldmlldw=='
	)
	assert.equal(
		module.generatedImageRippleFinalSource(item),
		'https://media.example.test/final.webp'
	)
	assert.equal(
		module.generatedImageRippleSource({
			displaySrc: 'https://media.example.test/final.webp',
			attachment: { url: 'https://media.example.test/final.webp' }
		}),
		'https://media.example.test/final.webp'
	)
})
