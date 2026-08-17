export const GENERATED_IMAGE_RIPPLE_PATH_DURATION_MS = 1200
export const GENERATED_IMAGE_RIPPLE_TEXTURE_DURATION_MS = 1400

const MAXIMUM_DEVICE_PIXEL_RATIO = 2
const MAXIMUM_TEXTURE_EDGE_PIXELS = 2048
const HTTPS_IMAGE_SOURCE = /^https:\/\/[^\s]+$/i
const DATA_IMAGE_SOURCE = /^data:image\/(?:png|jpe?g|webp);base64,[a-z0-9+/=\s]+$/i
const GENERATED_IMAGE_RIPPLE_NATIVE_CANVAS_SELECTOR =
	'canvas.generated-image-ripple-native-canvas'

function isGeneratedImageRippleCanvas(candidate) {
	return Boolean(
		candidate
		&& String(candidate.tagName || '').toUpperCase() === 'CANVAS'
		&& typeof candidate.getContext === 'function'
	)
}

export function ensureGeneratedImageRippleCanvas(
	host,
	documentRef = globalThis.document
) {
	if (!host) return null
	const existing = host.querySelector?.(
		GENERATED_IMAGE_RIPPLE_NATIVE_CANVAS_SELECTOR) || null
	if (isGeneratedImageRippleCanvas(existing)) return existing
	if (typeof documentRef?.createElement !== 'function'
		|| typeof host.appendChild !== 'function') return null

	const canvas = documentRef.createElement('canvas')
	if (!isGeneratedImageRippleCanvas(canvas)) return null
	canvas.className = 'generated-image-ripple-native-canvas'
	canvas.setAttribute?.('aria-hidden', 'true')
	canvas.setAttribute?.(
		'data-generated-image-ripple-owner', 'renderer-native')
	host.appendChild(canvas)
	return canvas
}

const VERTEX_SHADER_SOURCE = `
attribute vec2 aPosition;
varying vec2 vUv;

void main() {
  vUv = aPosition * 0.5 + 0.5;
  gl_Position = vec4(aPosition, 0.0, 1.0);
}
`

const FRAGMENT_SHADER_SOURCE = `
precision mediump float;

uniform sampler2D uTexture;
uniform sampler2D uTextureNext;
uniform float uProgress;
uniform float uDistortionStrength;
uniform vec2 uMeshSize;
uniform vec2 uImageSizeCurrent;
uniform vec2 uImageSizeNext;

varying vec2 vUv;

vec2 containUv(vec2 meshSize, vec2 imageSize, vec2 uv) {
  if (imageSize.x <= 0.0 || imageSize.y <= 0.0 || meshSize.x <= 0.0 || meshSize.y <= 0.0) {
    return uv;
  }

  float meshRatio = meshSize.x / meshSize.y;
  float imageRatio = imageSize.x / imageSize.y;

  if (meshRatio > imageRatio) {
    float occupiedWidth = imageRatio / meshRatio;
    return vec2((uv.x - 0.5) / occupiedWidth + 0.5, uv.y);
  }

  float occupiedHeight = meshRatio / imageRatio;
  return vec2(uv.x, (uv.y - 0.5) / occupiedHeight + 0.5);
}

float insideUv(vec2 uv) {
  return step(0.0, uv.x) * step(uv.x, 1.0)
    * step(0.0, uv.y) * step(uv.y, 1.0);
}

vec4 sampleContained(sampler2D textureSampler, vec2 uv) {
  float visible = insideUv(uv);
  vec4 sampled = texture2D(textureSampler, clamp(uv, 0.0, 1.0));
  return mix(vec4(0.0, 0.0, 0.0, 1.0), sampled, visible);
}

void main() {
  vec2 uv = vUv;
  vec2 center = vec2(0.5);
  float dist = distance(uv, center);
  float waveProgress = smoothstep(0.0, 1.0, uProgress);
  float rippleRadius = waveProgress;
  float waveIntensity = smoothstep(0.0, 0.2, uProgress)
    * (1.0 - smoothstep(0.6, 1.0, uProgress));
  float wavePattern = sin((dist - rippleRadius) * 8.0) * waveIntensity * 0.15;
  vec2 waveNormalOffset = normalize(uv - center + vec2(0.0001)) * wavePattern;
  vec2 distortion = waveNormalOffset * uDistortionStrength;

  float rippleThickness = 0.15;
  float mask = smoothstep(rippleRadius - rippleThickness, rippleRadius, dist);
  float maskWave = sin((dist - rippleRadius) * 6.0) * 0.5 + 0.5;
  mask = mix(mask, mask * (1.0 - maskWave * 0.35), waveIntensity);
  mask = clamp(mask, 0.0, 1.0);

  vec2 currentUv = containUv(uMeshSize, uImageSizeCurrent, uv) + distortion;
  vec2 nextUv = containUv(uMeshSize, uImageSizeNext, uv) + distortion;
  vec4 currentColor = sampleContained(uTexture, currentUv);
  vec4 nextColor = sampleContained(uTextureNext, nextUv);
  vec4 finalColor = mix(nextColor, currentColor, mask);
  finalColor.rgb *= clamp(1.0 - length(waveNormalOffset) * 0.6, 0.6, 1.0);

  gl_FragColor = finalColor;
}
`

function clamp(value, minimum, maximum) {
	return Math.min(maximum, Math.max(minimum, value))
}

export function easeOutPower3(value) {
	const normalized = clamp(Number(value) || 0, 0, 1)
	return 1 - (1 - normalized) ** 3
}

export function generatedImageRippleSource(item) {
	const preview = String(item?.attachment?.url || '').trim()
	if (DATA_IMAGE_SOURCE.test(preview)) return preview
	return String(item?.displaySrc || preview || '').trim()
}

export function generatedImageRippleFinalSource(item) {
	return String(item?.displaySrc || item?.attachment?.url || '').trim()
}

export function buildGeneratedImageRipplePath(frames, fromIdentity, toIdentity) {
	const source = Array.isArray(frames) ? frames : []
	const fromIndex = source.findIndex(frame => frame?.identity === fromIdentity)
	const toIndex = source.findIndex(frame => frame?.identity === toIdentity)
	if (fromIndex < 0 || toIndex < 0 || fromIndex === toIndex) return []

	const direction = toIndex > fromIndex ? 1 : -1
	const path = []
	for (let index = fromIndex + direction; ; index += direction) {
		path.push(source[index])
		if (index === toIndex) break
	}
	return path
}

export function interruptedRippleOrigin(transition, progress) {
	if (!transition) return null
	return Number(progress) >= 0.5 ? transition.toFrame : transition.fromFrame
}

function requiredFrame(value) {
	const identity = String(value?.identity || '').trim()
	const source = String(value?.source || '').trim()
	if (!identity || !source) throw new Error('Ripple frame identity and source are required')
	const width = Math.max(0, Number(value?.width) || 0)
	const height = Math.max(0, Number(value?.height) || 0)
	return Object.freeze({ identity, source, width, height })
}

function createShader(gl, type, source) {
	const shader = gl.createShader(type)
	if (!shader) throw new Error('Failed to create ripple shader')
	gl.shaderSource(shader, source)
	gl.compileShader(shader)
	if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
		const message = gl.getShaderInfoLog(shader) || 'Ripple shader compilation failed'
		gl.deleteShader(shader)
		throw new Error(message)
	}
	return shader
}

function createProgram(gl) {
	const program = gl.createProgram()
	if (!program) throw new Error('Failed to create ripple program')
	let vertexShader = null
	let fragmentShader = null
	try {
		vertexShader = createShader(gl, gl.VERTEX_SHADER, VERTEX_SHADER_SOURCE)
		fragmentShader = createShader(gl, gl.FRAGMENT_SHADER, FRAGMENT_SHADER_SOURCE)
		gl.attachShader(program, vertexShader)
		gl.attachShader(program, fragmentShader)
		gl.linkProgram(program)
		if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
			throw new Error(gl.getProgramInfoLog(program) || 'Ripple program linking failed')
		}
		return program
	} catch (error) {
		gl.deleteProgram(program)
		throw error
	} finally {
		if (vertexShader) gl.deleteShader(vertexShader)
		if (fragmentShader) gl.deleteShader(fragmentShader)
	}
}

function createPositionBuffer(gl) {
	const buffer = gl.createBuffer()
	if (!buffer) throw new Error('Failed to create ripple position buffer')
	try {
		gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
		gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([
			-1, -1,
			1, -1,
			-1, 1,
			-1, 1,
			1, -1,
			1, 1
		]), gl.STATIC_DRAW)
		return buffer
	} catch (error) {
		gl.deleteBuffer(buffer)
		throw error
	}
}

function browserNow() {
	return typeof globalThis.performance?.now === 'function'
		? globalThis.performance.now()
		: Date.now()
}

function scheduleFrame(callback) {
	if (typeof globalThis.requestAnimationFrame !== 'function') {
		throw new Error('Animation frames are unavailable')
	}
	return globalThis.requestAnimationFrame(callback)
}

function cancelFrame(handle) {
	if (handle != null && typeof globalThis.cancelAnimationFrame === 'function') {
		globalThis.cancelAnimationFrame(handle)
	}
}

function loadBrowserImage(source, pendingImageLoads) {
	return new Promise((resolve, reject) => {
		let image
		let settled = false
		try {
			image = new globalThis.Image()
		} catch (error) {
			reject(error)
			return
		}
		const request = {
			cancel() {
				if (settled) return
				settled = true
				pendingImageLoads.delete(request)
				image.onload = null
				image.onerror = null
				try { image.src = '' } catch (_) {}
				reject(new Error('Ripple image load cancelled'))
			}
		}
		const settle = callback => value => {
			if (settled) return
			settled = true
			pendingImageLoads.delete(request)
			image.onload = null
			image.onerror = null
			callback(value)
		}
		pendingImageLoads.add(request)
		try {
			image.decoding = 'async'
			image.onload = settle(() => resolve(image))
			image.onerror = settle(() => reject(new Error('Ripple image load failed')))
			if (HTTPS_IMAGE_SOURCE.test(source)) image.crossOrigin = 'anonymous'
			image.src = source
		} catch (error) {
			settle(reject)(error)
		}
	})
}

function releaseAsset(asset) {
	if (!asset?.uploadSource || asset.uploadSource === asset.image) return
	try {
		asset.uploadSource.width = 0
		asset.uploadSource.height = 0
	} catch (_) {
		// 浏览器拒绝释放临时画布时由垃圾回收兜底，不能影响最终图片切换。
	}
}

function downsampleImage(image, canvas) {
	const sourceWidth = Math.max(1, Number(image.naturalWidth || image.width || 1))
	const sourceHeight = Math.max(1, Number(image.naturalHeight || image.height || 1))
	const maximumWidth = Math.max(1, Math.min(
		Number(canvas.width || sourceWidth), MAXIMUM_TEXTURE_EDGE_PIXELS))
	const maximumHeight = Math.max(1, Math.min(
		Number(canvas.height || sourceHeight), MAXIMUM_TEXTURE_EDGE_PIXELS))
	const scale = Math.min(1, maximumWidth / sourceWidth, maximumHeight / sourceHeight)
	const width = Math.max(1, Math.round(sourceWidth * scale))
	const height = Math.max(1, Math.round(sourceHeight * scale))
	if (scale >= 1 || typeof document === 'undefined') {
		return { image, uploadSource: image, width: sourceWidth, height: sourceHeight }
	}

	const temporary = document.createElement('canvas')
	temporary.width = width
	temporary.height = height
	const context = temporary.getContext('2d')
	if (!context) return { image, uploadSource: image, width: sourceWidth, height: sourceHeight }
	context.drawImage(image, 0, 0, width, height)
	return { image, uploadSource: temporary, width, height }
}

async function prepareFrameAsset(frame, canvas, pendingImageLoads) {
	const normalized = requiredFrame(frame)
	const image = await loadBrowserImage(normalized.source, pendingImageLoads)
	return Object.freeze({
		frame: normalized,
		...downsampleImage(image, canvas)
	})
}

function uploadTexture(gl, asset) {
	const texture = gl.createTexture()
	if (!texture) throw new Error('Failed to create ripple texture')
	try {
		gl.bindTexture(gl.TEXTURE_2D, texture)
		gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, 1)
		gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
		gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
		gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
		gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
		gl.texImage2D(
			gl.TEXTURE_2D,
			0,
			gl.RGBA,
			gl.RGBA,
			gl.UNSIGNED_BYTE,
			asset.uploadSource
		)
	} catch (error) {
		gl.deleteTexture(texture)
		throw error
	} finally {
		gl.bindTexture(gl.TEXTURE_2D, null)
	}
	return {
		frame: asset.frame,
		texture,
		width: asset.width,
		height: asset.height
	}
}

export function createGeneratedImageRippleRenderer(options = {}) {
	const canvas = options.canvas
	if (!canvas?.getContext) throw new Error('Ripple canvas is required')
	const onVisualChange = typeof options.onVisualChange === 'function'
		? options.onVisualChange : () => {}
	const onSettled = typeof options.onSettled === 'function'
		? options.onSettled : () => {}
	const onFailure = typeof options.onFailure === 'function'
		? options.onFailure : () => {}
	const gl = canvas.getContext('webgl', {
		alpha: false,
		antialias: true,
		premultipliedAlpha: false
	})
	if (!gl) throw new Error('WebGL is unavailable')

	const program = createProgram(gl)
	let positionBuffer
	try {
		positionBuffer = createPositionBuffer(gl)
	} catch (error) {
		gl.deleteProgram(program)
		throw error
	}
	const positionLocation = gl.getAttribLocation(program, 'aPosition')
	const uniforms = {
		texture: gl.getUniformLocation(program, 'uTexture'),
		textureNext: gl.getUniformLocation(program, 'uTextureNext'),
		progress: gl.getUniformLocation(program, 'uProgress'),
		distortionStrength: gl.getUniformLocation(program, 'uDistortionStrength'),
		meshSize: gl.getUniformLocation(program, 'uMeshSize'),
		imageSizeCurrent: gl.getUniformLocation(program, 'uImageSizeCurrent'),
		imageSizeNext: gl.getUniformLocation(program, 'uImageSizeNext')
	}
	if (positionLocation < 0 || Object.values(uniforms).some(location => location == null)) {
		gl.deleteBuffer(positionBuffer)
		gl.deleteProgram(program)
		throw new Error('Ripple shader locations are unavailable')
	}

	let destroyed = false
	let failed = false
	let requestSequence = 0
	let pathFrameHandle = null
	let textureFrameHandle = null
	let currentResource = null
	let transition = null
	let pendingPath = null
	const resources = new Set()
	const pendingImageLoads = new Set()

	function fail(error) {
		if (failed || destroyed) return
		failed = true
		cancel()
		onFailure(error instanceof Error ? error : new Error(String(error || 'Ripple failed')))
	}

	function resize() {
		if (destroyed || failed) return
		const rect = canvas.getBoundingClientRect?.()
		const cssWidth = Math.max(1, Math.round(Number(rect?.width || canvas.clientWidth || 1)))
		const cssHeight = Math.max(1, Math.round(Number(rect?.height || canvas.clientHeight || 1)))
		const dpr = Math.min(MAXIMUM_DEVICE_PIXEL_RATIO,
			Math.max(1, Number(globalThis.devicePixelRatio || 1)))
		const width = Math.max(1, Math.round(cssWidth * dpr))
		const height = Math.max(1, Math.round(cssHeight * dpr))
		if (canvas.width !== width) canvas.width = width
		if (canvas.height !== height) canvas.height = height
		gl.viewport(0, 0, width, height)
		if (transition) drawTransition(getTransitionProgress())
		else if (currentResource) draw(currentResource, currentResource, 0)
	}

	function draw(fromResource, toResource, progress) {
		if (destroyed || failed || !fromResource || !toResource) return
		gl.clearColor(0, 0, 0, 1)
		gl.clear(gl.COLOR_BUFFER_BIT)
		gl.useProgram(program)
		gl.bindBuffer(gl.ARRAY_BUFFER, positionBuffer)
		gl.enableVertexAttribArray(positionLocation)
		gl.vertexAttribPointer(positionLocation, 2, gl.FLOAT, false, 0, 0)

		gl.activeTexture(gl.TEXTURE0)
		gl.bindTexture(gl.TEXTURE_2D, fromResource.texture)
		gl.activeTexture(gl.TEXTURE1)
		gl.bindTexture(gl.TEXTURE_2D, toResource.texture)
		gl.uniform1i(uniforms.texture, 0)
		gl.uniform1i(uniforms.textureNext, 1)
		gl.uniform1f(uniforms.progress, clamp(progress, 0, 1))
		gl.uniform1f(uniforms.distortionStrength, 0.2)
		gl.uniform2f(uniforms.meshSize, canvas.width, canvas.height)
		gl.uniform2f(uniforms.imageSizeCurrent, fromResource.width, fromResource.height)
		gl.uniform2f(uniforms.imageSizeNext, toResource.width, toResource.height)
		gl.drawArrays(gl.TRIANGLES, 0, 6)
	}

	function getTransitionProgress(time = browserNow()) {
		if (!transition) return 1
		return clamp((time - transition.startedAt) / transition.duration, 0, 1)
	}

	function drawTransition(rawProgress) {
		if (!transition) return
		draw(transition.fromResource, transition.toResource, easeOutPower3(rawProgress))
	}

	function keepOnly(keptResources) {
		for (const resource of [...resources]) {
			if (keptResources.includes(resource)) continue
			gl.deleteTexture(resource.texture)
			resources.delete(resource)
		}
	}

	function upload(asset) {
		const resource = uploadTexture(gl, asset)
		resources.add(resource)
		releaseAsset(asset)
		return resource
	}

	function settleTransition() {
		if (!transition) return
		currentResource = transition.toResource
		transition = null
		keepOnly([currentResource])
		draw(currentResource, currentResource, 0)
		completePendingPathIfReady()
	}

	function completePendingPathIfReady() {
		if (pendingPath?.pathComplete
			&& currentResource?.frame.identity === pendingPath.finalIdentity) {
			const completed = pendingPath
			pendingPath = null
			onSettled(currentResource.frame)
			completed.resolve(currentResource.frame)
		}
	}

	function renderTextureFrame(time) {
		textureFrameHandle = null
		if (destroyed || failed || !transition) return
		try {
			const progress = getTransitionProgress(time)
			drawTransition(progress)
			if (progress >= 1) {
				settleTransition()
				return
			}
			textureFrameHandle = scheduleFrame(renderTextureFrame)
		} catch (error) {
			fail(error)
		}
	}

	function startTextureTransition(asset, duration) {
		const nextResource = upload(asset)
		const currentProgress = getTransitionProgress()
		const originFrame = interruptedRippleOrigin(transition && {
			fromFrame: transition.fromResource,
			toFrame: transition.toResource
		}, currentProgress)
		const originResource = originFrame || currentResource || nextResource
		cancelFrame(textureFrameHandle)
		textureFrameHandle = null
		transition = {
			fromResource: originResource,
			toResource: nextResource,
			startedAt: browserNow(),
			duration
		}
		currentResource = originResource
		keepOnly([originResource, nextResource])
		onVisualChange(nextResource.frame)
		drawTransition(0)
		textureFrameHandle = scheduleFrame(renderTextureFrame)
	}

	function releasePendingAssets(path = pendingPath) {
		for (const asset of path?.assets || []) releaseAsset(asset)
	}

	function cancelPendingPath() {
		if (!pendingPath) return
		const cancelled = pendingPath
		pendingPath = null
		releasePendingAssets(cancelled)
		cancelled.resolve(null)
	}

	function cancel() {
		requestSequence += 1
		for (const request of [...pendingImageLoads]) request.cancel()
		cancelFrame(pathFrameHandle)
		cancelFrame(textureFrameHandle)
		pathFrameHandle = null
		textureFrameHandle = null
		cancelPendingPath()
		if (transition) {
			const progress = getTransitionProgress()
			currentResource = progress >= 0.5
				? transition.toResource
				: transition.fromResource
			transition = null
			keepOnly(currentResource ? [currentResource] : [])
			if (currentResource) {
				draw(currentResource, currentResource, 0)
				onVisualChange(currentResource.frame)
			}
		}
	}

	async function prime(frame) {
		cancel()
		const sequence = requestSequence
		try {
			resize()
			const asset = await prepareFrameAsset(frame, canvas, pendingImageLoads)
			if (destroyed || failed || sequence !== requestSequence) {
				releaseAsset(asset)
				return null
			}
			const resource = upload(asset)
			currentResource = resource
			keepOnly([resource])
			draw(resource, resource, 0)
			onVisualChange(resource.frame)
			onSettled(resource.frame)
			return resource.frame
		} catch (error) {
			if (sequence === requestSequence) fail(error)
			return null
		}
	}

	async function transitionPath(frames, settings = {}) {
		const candidates = (Array.isArray(frames) ? frames : []).map(requiredFrame)
		if (!candidates.length || destroyed || failed) return null
		const sequence = requestSequence + 1
		cancel()
		requestSequence = sequence
		const pathDuration = Math.max(1,
			Number(settings.pathDuration || GENERATED_IMAGE_RIPPLE_PATH_DURATION_MS))
		const textureDuration = Math.max(1,
			Number(settings.textureDuration || GENERATED_IMAGE_RIPPLE_TEXTURE_DURATION_MS))

		try {
			resize()
			const assets = []
			for (let index = 0; index < candidates.length; index += 1) {
				try {
					const asset = await prepareFrameAsset(
						candidates[index], canvas, pendingImageLoads)
					if (destroyed || failed || sequence !== requestSequence) {
						releaseAsset(asset)
						assets.forEach(releaseAsset)
						return null
					}
					assets.push(asset)
				} catch (error) {
					if (destroyed || failed || sequence !== requestSequence) {
						assets.forEach(releaseAsset)
						return null
					}
					if (index === candidates.length - 1) throw error
				}
			}
			if (destroyed || failed || sequence !== requestSequence) {
				assets.forEach(releaseAsset)
				return null
			}
			if (!assets.length) return null

			return await new Promise(resolve => {
				pendingPath = {
					assets,
					finalIdentity: assets[assets.length - 1].frame.identity,
					pathComplete: false,
					resolve
				}
				const startedAt = browserNow()
				let lastStep = 0

				const advancePath = time => {
					pathFrameHandle = null
					if (destroyed || failed || sequence !== requestSequence || !pendingPath) return
					try {
						const rawProgress = clamp((time - startedAt) / pathDuration, 0, 1)
						const virtualIndex = assets.length * easeOutPower3(rawProgress)
						const activeStep = clamp(Math.round(virtualIndex), 0, assets.length)
						// 即使浏览器掉帧让虚拟索引一次跨过多个位置，也必须按固定快照逐个经过中间图片。
						while (lastStep < activeStep) {
							startTextureTransition(assets[lastStep], textureDuration)
							lastStep += 1
						}
						if (rawProgress < 1) {
							pathFrameHandle = scheduleFrame(advancePath)
							return
						}
						pendingPath.pathComplete = true
						if (!transition) completePendingPathIfReady()
					} catch (error) {
						if (sequence === requestSequence) fail(error)
					}
				}

				pathFrameHandle = scheduleFrame(advancePath)
			})
		} catch (error) {
			if (sequence === requestSequence) fail(error)
			return null
		}
	}

	function handleContextLost(event) {
		event?.preventDefault?.()
		fail(new Error('WebGL context was lost'))
	}

	function destroy() {
		if (destroyed) return
		cancel()
		destroyed = true
		canvas.removeEventListener?.('webglcontextlost', handleContextLost)
		for (const resource of resources) gl.deleteTexture(resource.texture)
		resources.clear()
		gl.deleteBuffer(positionBuffer)
		gl.deleteProgram(program)
	}

	canvas.addEventListener?.('webglcontextlost', handleContextLost)
	try {
		resize()
	} catch (error) {
		canvas.removeEventListener?.('webglcontextlost', handleContextLost)
		gl.deleteBuffer(positionBuffer)
		gl.deleteProgram(program)
		throw error
	}

	return Object.freeze({ prime, transitionPath, resize, cancel, destroy })
}
