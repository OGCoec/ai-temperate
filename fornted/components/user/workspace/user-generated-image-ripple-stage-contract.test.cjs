const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const component = fs.readFileSync(
	path.resolve(__dirname, 'user-generated-image-ripple-stage.vue'),
	'utf8'
)
const renderer = fs.readFileSync(
	path.resolve(__dirname, 'user-generated-image-ripple-render.js'),
	'utf8'
)

test('exposes an H5-only native canvas host with stable identity inputs', () => {
	assert.match(component, /<!-- #ifdef H5 -->/)
	assert.match(component, /ref="canvasHost"/)
	assert.match(component, /class="generated-image-ripple-native-host"/)
	assert.doesNotMatch(component, /<canvas(?:\s|>)/)
	assert.doesNotMatch(component, /uni-canvas-canvas/)
	assert.match(component, /aria-hidden="true"/)
	assert.match(component, /activeIdentity/)
	assert.match(component, /reducedMotion/)
	assert.match(component, /'visual-change'/)
	assert.match(component, /'transitioning-change'/)
	assert.match(component, /currentTargetIdentity/)
	assert.match(component, /requestSequence/)
})

test('creates and releases a renderer-owned native Canvas on H5', () => {
	assert.match(renderer, /export function ensureGeneratedImageRippleCanvas/)
	assert.match(renderer, /documentRef\.createElement\('canvas'\)/)
	assert.match(renderer, /generated-image-ripple-native-canvas/)
	assert.match(renderer, /data-generated-image-ripple-owner/)
	assert.match(component, /ensureGeneratedImageRippleCanvas/)
	assert.match(component, /nativeCanvasElement/)
	assert.match(component, /removeNativeCanvasElement/)
	assert.match(component, /canvas\?\.remove\?\.\(\)/)
})

test('logs one renderer failure before preserving the static image fallback', () => {
	assert.match(component, /if \(this\.webglFailed\) return/)
	assert.match(component, /console\?\.warn\?\.\(/)
	assert.match(component, /this\.\$emit\('failure'/)
})

test('uses native WebGL with only the center-opening transition wave', () => {
	assert.match(renderer, /getContext\('webgl'/)
	assert.match(renderer, /uniform sampler2D uTexture;/)
	assert.match(renderer, /uniform sampler2D uTextureNext;/)
	assert.match(renderer, /uniform float uProgress;/)
	assert.match(renderer, /waveNormalOffset/)
	assert.match(renderer, /containUv/)
	assert.doesNotMatch(renderer, /uDisplacementMap/)
	assert.doesNotMatch(renderer, /mouseNormalOffset/)
	assert.doesNotMatch(renderer, /MOUSE_BRUSH|pointermove|pointerleave/i)
})

test('does not autoplay and only schedules animation frames for transitions', () => {
	assert.doesNotMatch(component, /setInterval/)
	assert.doesNotMatch(renderer, /setInterval/)
	assert.match(renderer, /requestAnimationFrame/)
	assert.match(renderer, /cancelAnimationFrame/)
	assert.match(renderer, /while \(lastStep < activeStep\)/)
})

test('loads anonymous HTTPS textures before assigning image src', () => {
	assert.match(renderer, /crossOrigin\s*=\s*'anonymous'[\s\S]*image\.src\s*=/)
	assert.match(renderer, /pendingImageLoads/)
	assert.match(renderer, /image\.src = ''/)
	assert.match(renderer, /sequence === requestSequence/)
	assert.match(renderer, /webglcontextlost/)
	assert.match(renderer, /deleteTexture/)
	assert.match(renderer, /deleteBuffer/)
	assert.match(renderer, /deleteProgram/)
})

test('keeps the final high-resolution source separate from intermediate previews', () => {
	assert.match(component, /generatedImageRippleFinalSource/)
	assert.match(component, /path\[path\.length - 1\] = finalFrame/)
	assert.match(component, /reprimeIfFinalSourceChanged/)
})

test('snaps without animation for reduced motion and releases every observer and renderer', () => {
	assert.match(component, /if \(reduced\) this\.snapToActiveIdentity\(\)/)
	assert.match(component, /renderer\?\.cancel\?\.\(\)/)
	assert.match(component, /resizeObserver\?\.disconnect/)
	assert.match(component, /removeEventListener\('visibilitychange'/)
	assert.match(component, /renderer\?\.destroy/)
})

test('uses stable identities when the items snapshot changes', () => {
	assert.match(component, /frameByIdentity\(identity/)
	assert.match(component, /startIdentity = this\.visualIdentity \|\| this\.currentTargetIdentity/)
	assert.match(component, /buildGeneratedImageRipplePath\([\s\S]*startIdentity, identity/)
	assert.doesNotMatch(component, /currentTargetIndex|visualIndex/)
})
