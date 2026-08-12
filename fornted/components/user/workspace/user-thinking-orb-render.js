/*
 * Thinking Orbs Canvas engine, adapted from Jakub Antalik's thinking-orbs
 * v0.2.0 (MIT). This file stays self-contained because uni-app renderjs runs
 * in the view layer and cannot rely on the logic-layer module graph.
 */

const COUNT_PAIRS = [
	['latRings', 'lonDensity'],
	['rings', 'lonDensity'],
	['lanes', 'segs']
]
const COUNT_KEYS = ['orbitN', 'ghostN', 'nodeN', 'strandN', 'signals']
const RADIUS_KEYS = [
	'rBase', 'rDepth', 'rActive', 'rDot', 'ghostR', 'partR',
	'partRDepth', 'nodeR', 'nodeRDepth'
]

const EDGE_INSETS = Object.freeze({ 20: 0.75, 40: 1, 64: 1.5 })
const MIN_DOT_RADII = Object.freeze({ 20: 0.5, 40: 0.45, 64: 0.3 })
const MIN_LINE_WIDTHS = Object.freeze({ 20: 0.7, 40: 0.55, 64: 0.3 })
const DOT_ROLES = Object.freeze({ SURFACE: 'SURFACE', GHOST: 'GHOST', SIGNAL: 'SIGNAL', ACTIVE: 'ACTIVE' })
const LINE_ROLES = Object.freeze({ STRUCTURE: 'STRUCTURE', SIGNAL: 'SIGNAL' })
const DARK_DOT_VISIBILITY = Object.freeze({
	20: Object.freeze({
		SURFACE: [150, 0.7], GHOST: [132, 0.55], SIGNAL: [190, 0.82], ACTIVE: [180, 0.78]
	}),
	40: Object.freeze({
		SURFACE: [128, 0.58], GHOST: [112, 0.45], SIGNAL: [180, 0.76], ACTIVE: [168, 0.7]
	}),
	64: Object.freeze({
		SURFACE: [96, 0.38], GHOST: [86, 0.28], SIGNAL: [170, 0.68], ACTIVE: [152, 0.58]
	})
})
const DARK_LINE_VISIBILITY = Object.freeze({
	20: Object.freeze({ STRUCTURE: [126, 0.46], SIGNAL: [186, 0.76] }),
	40: Object.freeze({ STRUCTURE: [108, 0.36], SIGNAL: [176, 0.68] }),
	64: Object.freeze({ STRUCTURE: [82, 0.24], SIGNAL: [164, 0.6] })
})

const BASE_PROFILES = {
	globe: { latRings: 17, lonDensity: 44, rBase: 0.6, rDepth: 1.7, rBoost: 1, inkFar: 0.62, inkSpan: 0.54, rsPow: 0.6, rMin: 0.3 },
	orbits: { orbitN: 12, ghostN: 40, ghostR: 0.9, ghostA: 0.5, particles: 3, partR: 1.2, partRDepth: 1.6, rsPow: 0.6, rMin: 0.3 },
	rubik: { latRings: 15, lonDensity: 40, moveCount: 14, rBase: 0.6, rDepth: 1.7, rActive: 0.3, inkFar: 0.62, inkSpan: 0.54, rsPow: 0.6, rMin: 0.3 },
	wave: { rings: 15, lonDensity: 40, rBase: 0.6, rDepth: 1.7, rsPow: 0.6, rMin: 0.3 },
	web: { nodeN: 30, thr: 0.72, signals: 5, nodeR: 1.4, nodeRDepth: 1.8, lineW: 0.8, rsPow: 0.6, rMin: 0.3 },
	braid: { strandN: 52, turns: 3, ghostN: 150, rBase: 1.2, rDepth: 1.8, rsPow: 0.6, rMin: 0.3 },
	ribbon: { lanes: 5, segs: 88, ghostN: 150, rBase: 1.1, rDepth: 1.7, rsPow: 0.6, rMin: 0.3 },
	ring: { lanes: 5, segs: 88, ghostN: 0, faceOn: 1, rBase: 1.1, rDepth: 1.7, rsPow: 0.6, rMin: 0.3 },
	morph: { rDot: 0.021, iconD: 1, rMin: 0.25 }
}

const PRESETS = {
	orbits: {
		64: { speed: 1.885, count: 1, size: 1 },
		40: { speed: 2.45, count: 0.58, size: 1.35, extra: { orbitN: 12, ghostN: 36, ghostA: 0.62, ghostDepthBase: 0.58, ghostWhite: 0.62 } },
		20: {
			speed: 3.9,
			count: 0.238,
			size: 2.4,
			extra: { orbitN: 9, ghostN: 28, ghostA: 0.85, ghostDepthBase: 0.78, ghostWhite: 0.5 }
		}
	},
	globe: {
		64: { speed: 2.015, count: 0.42, size: 1.15, extra: { scanMul: 4.08, dimBase: 0.58 } },
		40: { speed: 2.3, count: 0.3, size: 1.35, extra: { latRings: 10, lonDensity: 28, scanMul: 4.2, dimBase: 0.56, inkFar: 0.56, inkSpan: 0.48 } },
		20: {
			speed: 2.665,
			count: 0.105,
			size: 1.75,
			extra: { latRings: 8, lonDensity: 20, scanMul: 4.335, dimBase: 0.68, inkFar: 0.5, inkSpan: 0.42 }
		}
	},
	rubik: {
		64: { speed: 1.82, count: 0.35, size: 1.05 },
		40: { speed: 1.88, count: 0.22, size: 1.3, extra: { latRings: 10, lonDensity: 28 } },
		20: { speed: 1.95, count: 0.088, size: 1.9, extra: { latRings: 9, lonDensity: 22 } }
	},
	wave: {
		64: { speed: 4.388, count: 0.341, size: 1 },
		40: { speed: 4.15, count: 0.23, size: 1.25, extra: { rings: 11, lonDensity: 30 } },
		20: { speed: 3.998, count: 0.105, size: 1.6, extra: { rings: 9, lonDensity: 22 } }
	},
	web: {
		64: { speed: 3.315, count: 1.35, size: 0.95 },
		40: { speed: 4.5, count: 0.7, size: 1.2, extra: { nodeN: 23, signals: 4 } },
		20: { speed: 6.63, count: 0.25, size: 1.52, extra: { nodeN: 18, signals: 4 } }
	},
	braid: {
		64: { speed: 1.625, count: 0.5, size: 1 },
		40: { speed: 2.1, count: 0.3, size: 1.17, extra: { strandN: 34, ghostN: 90 } },
		20: { speed: 2.75, count: 0.1125, size: 1.36, extra: { strandN: 20, ghostN: 48 } }
	},
	ribbon: {
		64: { speed: 2.34, count: 0.25, size: 0.85, extra: { spin: 0, bandMul: 3.9, wobMul: 1 } },
		40: { speed: 2.7, count: 0.14, size: 0.96, extra: { spin: 0, lanes: 11, segs: 60, ghostN: 72, bandMul: 1, wobMul: 1 } },
		20: { speed: 3.12, count: 0.051, size: 1.073, extra: { spin: 0, lanes: 10, segs: 40, ghostN: 36, bandMul: 1, wobMul: 1 } }
	},
	ring: {
		64: { speed: 3.24, count: 0.25, size: 0.956, extra: { spin: 0, bandMul: 3.627, wobMul: 0.368 } },
		40: { speed: 3.5, count: 0.12, size: 1.3, extra: { spin: 0, lanes: 10, segs: 60, ghostN: 0, bandMul: 1, wobMul: 0.46 } },
		20: { speed: 3.78, count: 0.028, size: 1.622, extra: { spin: 0, lanes: 8, segs: 40, ghostN: 0, bandMul: 1, wobMul: 0.565 } }
	},
	morph: {
		64: { speed: 2.405, count: 0.702, size: 0.395, extra: { spread: 1.45 } },
		40: { speed: 2.2, count: 0.62, size: 0.68, extra: { iconCount: 40, spread: 1.45 } },
		20: { speed: 2.08, count: 0.53, size: 1.011, extra: { iconCount: 28, spread: 1.45 } }
	}
}

const STATE_TO_MODE = {
	working: 'orbits', searching: 'globe', solving: 'rubik', listening: 'wave',
	connecting: 'web', weaving: 'braid', composing: 'ribbon', breathing: 'ring',
	shaping: 'morph'
}

const PRESET_CACHE = new Map()

function scaleCounts(options, scale) {
	const output = { ...options }
	const done = new Set()
	const root = Math.sqrt(scale)
	for (const [a, b] of COUNT_PAIRS) {
		const left = output[a]
		const right = output[b]
		if (left != null && right != null && !done.has(a) && !done.has(b)) {
			output[a] = Math.max(2, Math.round(left * root))
			output[b] = Math.max(2, Math.round(right * root))
			done.add(a); done.add(b)
		}
	}
	for (const key of COUNT_KEYS) {
		const value = output[key]
		if (value != null && value !== 0 && !done.has(key)) {
			output[key] = Math.max(1, Math.round(value * scale))
		}
	}
	if (output.iconD != null) output.iconD = Math.max(0.02, output.iconD * scale)
	return output
}

function scaleRadii(options, scale) {
	const output = { ...options }
	for (const key of RADIUS_KEYS) {
		if (output[key] != null) output[key] *= scale
	}
	output.rSizeMul = (output.rSizeMul || 1) * scale
	return output
}

function normalizeSize(size) {
	const numericSize = Number(size)
	return numericSize === 64 ? 64 : numericSize === 40 ? 40 : 20
}

function isNativeCanvasCandidate(candidate) {
	return Boolean(
		candidate
		&& typeof candidate.getContext === 'function'
		&& typeof candidate.width === 'number'
		&& typeof candidate.height === 'number'
	)
}

function resolveNativeCanvas(root) {
	const canvasHost = root?.querySelector?.('.user-thinking-orb-canvas') || null
	const candidates = [
		canvasHost?.shadowRoot?.querySelector?.('canvas'),
		canvasHost?.querySelector?.('canvas.uni-canvas-canvas'),
		canvasHost?.querySelector?.('canvas'),
		canvasHost,
		root?.querySelector?.('canvas'),
		root
	]
	const canvas = candidates.find(isNativeCanvasCandidate) || null
	return { canvasHost: canvasHost || canvas, canvas }
}

export function resolveOrbCanvasMetrics(size, dpr = 1) {
	const displaySize = normalizeSize(size)
	const renderSize = displaySize
	const numericDpr = Number(dpr)
	const resolvedDpr = Number.isFinite(numericDpr)
		? Math.min(2, Math.max(1, numericDpr))
		: 1
	return {
		displaySize,
		renderSize,
		dpr: resolvedDpr,
		contextScale: resolvedDpr,
		hostCssSize: `${displaySize}px`,
		canvasCssSize: `${renderSize}px`,
		pixelSize: Math.round(renderSize * resolvedDpr)
	}
}

function resolveModeRadius({ size, desiredRatio, maxProjection = 1, maxPrimitiveRadius = 0, maxLineHalfWidth = 0 }) {
	const normalizedSize = normalizeSize(size)
	const inset = EDGE_INSETS[normalizedSize]
	const available = Math.max(0, normalizedSize / 2 - inset - maxPrimitiveRadius - maxLineHalfWidth)
	const desired = (normalizedSize / 2) * desiredRatio
	return Math.max(0, Math.min(desired, available / Math.max(1e-6, maxProjection)))
}

export function resolveOrbRenderProfile(state, size) {
	const normalizedState = STATE_TO_MODE[state] ? state : 'working'
	const normalizedSize = normalizeSize(size)
	const key = `${normalizedState}-${normalizedSize}`
	if (PRESET_CACHE.has(key)) return PRESET_CACHE.get(key)
	const mode = STATE_TO_MODE[normalizedState]
	const preset = PRESETS[mode][normalizedSize]
	let options = { ...BASE_PROFILES[mode] }
	if (preset.count !== 1) options = scaleCounts(options, preset.count)
	if (preset.size !== 1) options = scaleRadii(options, preset.size)
	if (preset.extra) options = { ...options, ...preset.extra }
	const resolved = { mode, speed: preset.speed, options }
	PRESET_CACHE.set(key, resolved)
	return resolved
}

function lerp(a, b, f) { return a + (b - a) * f }
function frac(value) { return value - Math.floor(value) }
function hashD(a, b) {
	const h = Math.sin(a * 12.9898 + b * 78.233) * 43758.5453
	return h - Math.floor(h)
}
function vnoise(x, y) {
	const xi = Math.floor(x); const yi = Math.floor(y)
	let fx = x - xi; let fy = y - yi
	fx *= fx * (3 - 2 * fx); fy *= fy * (3 - 2 * fy)
	const a = hashD(xi, yi); const b = hashD(xi + 1, yi)
	const c = hashD(xi, yi + 1); const d = hashD(xi + 1, yi + 1)
	return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
}
function fibDir(i, n) {
	const golden = Math.PI * (3 - Math.sqrt(5))
	const y = 1 - (2 * (i + 0.5)) / n
	const radius = Math.sqrt(1 - y * y)
	const angle = i * golden
	return [radius * Math.cos(angle), y, radius * Math.sin(angle)]
}
function angleDelta(a, b) { return Math.atan2(Math.sin(a - b), Math.cos(a - b)) }
function makeProj(yaw, tilt, cx, cy, scale) {
	const st = Math.sin(tilt); const ct = Math.cos(tilt)
	const sy = Math.sin(yaw); const cyw = Math.cos(yaw)
	return (x, y, z) => {
		const x1 = x * cyw + z * sy
		const z1 = -x * sy + z * cyw
		const y1 = y * ct - z1 * st
		const z2 = y * st + z1 * ct
		return [cx + x1 * scale, cy - y1 * scale, z2]
	}
}
function radiusScale(size, power) { return (size / 300) ** power }

function clamp01(value) { return Math.min(1, Math.max(0, Number(value) || 0)) }

function resolvedDotRadius(size, rawRadius, rMin = 0.3) {
	const normalizedSize = normalizeSize(size)
	return Math.max(MIN_DOT_RADII[normalizedSize], Number(rMin) || 0, Number(rawRadius) || 0)
}

function resolvedLineWidth(size, rawWidth) {
	const normalizedSize = normalizeSize(size)
	return Math.max(MIN_LINE_WIDTHS[normalizedSize], Number(rawWidth) || 0)
}

function resolveOrbDotStyle(dot, { size, dark }) {
	const normalizedSize = normalizeSize(size)
	const rawAlpha = dot.a == null ? 1 : clamp01(dot.a)
	const white = clamp01(dot.white)
	if (!dark) {
		const gray = Math.round(white * 255)
		return { gray, alpha: rawAlpha }
	}
	const role = DOT_ROLES[dot.role] || DOT_ROLES.SURFACE
	const [baseMinimumGray, baseMinimumAlpha] = DARK_DOT_VISIBILITY[normalizedSize][role]
	const farInfluence = 1 - clamp01(dot.depth == null ? 0.5 : dot.depth)
	const minimumGray = Math.round(baseMinimumGray * (0.9 + farInfluence * 0.1))
	const minimumAlpha = baseMinimumAlpha * (0.88 + farInfluence * 0.12)
	return {
		gray: Math.max(minimumGray, Math.round((1 - white) * 255)),
		alpha: Math.max(minimumAlpha, rawAlpha)
	}
}

function resolveOrbLineStyle(line, { size, dark }) {
	const normalizedSize = normalizeSize(size)
	const rawAlpha = line.a == null ? 1 : clamp01(line.a)
	const white = clamp01(line.white)
	if (!dark) {
		return { gray: Math.round(white * 255), alpha: rawAlpha }
	}
	const role = LINE_ROLES[line.role] || LINE_ROLES.STRUCTURE
	const [baseMinimumGray, baseMinimumAlpha] = DARK_LINE_VISIBILITY[normalizedSize][role]
	const farInfluence = 1 - clamp01(line.depth == null ? 0.5 : line.depth)
	return {
		gray: Math.max(Math.round(baseMinimumGray * (0.9 + farInfluence * 0.1)), Math.round((1 - white) * 255)),
		alpha: Math.max(baseMinimumAlpha * (0.88 + farInfluence * 0.12), rawAlpha)
	}
}

function paint(ctx, dots, dark, rMin = 0.3, size = 20) {
	dots.sort((left, right) => left.z - right.z)
	for (const dot of dots) {
		const rawAlpha = dot.a == null ? 1 : dot.a
		if (rawAlpha < 0.02) continue
		const { gray, alpha } = resolveOrbDotStyle(dot, { size, dark })
		const radius = resolvedDotRadius(size, dot.r, rMin)
		ctx.fillStyle = `rgba(${gray},${gray},${gray},${alpha})`
		ctx.beginPath(); ctx.arc(dot.x, dot.y, radius, 0, Math.PI * 2); ctx.fill()
	}
}
function paintLines(ctx, lines, dark, size = 20) {
	for (const line of lines) {
		const rawAlpha = line.a == null ? 1 : line.a
		if (rawAlpha < 0.02) continue
		const { gray, alpha } = resolveOrbLineStyle(line, { size, dark })
		const width = resolvedLineWidth(size, line.w)
		ctx.strokeStyle = `rgba(${gray},${gray},${gray},${alpha})`
		ctx.lineWidth = width
		ctx.beginPath()
		ctx.moveTo(line.x1, line.y1)
		ctx.lineTo(line.x2, line.y2)
		ctx.stroke()
	}
}

function drawOrbits(ctx, size, time, dark, options) {
	const cx = size / 2; const cy = size / 2; const scale = radiusScale(size, options.rsPow || 0.6)
	const maxPrimitiveRadius = Math.max(
		resolvedDotRadius(size, (options.ghostR || 0.9) * scale, options.rMin),
		resolvedDotRadius(size, ((options.partR || 1.2) + (options.partRDepth || 1.6)) * scale, options.rMin)
	)
	const radius = resolveModeRadius({ size, desiredRatio: 0.82, maxProjection: 0.97, maxPrimitiveRadius })
	const project = makeProj(time * 0.12, 0.3, cx, cy, 1)
	const dots = []
	const orbitN = options.orbitN || 12; const ghostN = options.ghostN || 40; const particles = options.particles || 3
	for (let orbit = 0; orbit < orbitN; orbit++) {
		const h1 = hashD(orbit, 1.7); const h2 = hashD(orbit, 5.2); const h3 = hashD(orbit, 8.9)
		const orbitRadius = radius * (0.45 + 0.52 * h1); const theta = h1 * 2 * Math.PI
		const phi = Math.acos(2 * h2 - 1); const nx = Math.sin(phi) * Math.cos(theta); const ny = Math.cos(phi); const nz = Math.sin(phi) * Math.sin(theta)
		let ux = -ny; let uy = nx; const uz = 0; const length = Math.max(1e-6, Math.sqrt(ux * ux + uy * uy)); ux /= length; uy /= length
		const vx = ny * uz - nz * uy; const vy = nz * ux - nx * uz; const vz = nx * uy - ny * ux
		const particleSpeed = (0.25 + 0.55 * h3) * (h3 > 0.5 ? 1 : -1)
		for (let k = 0; k < ghostN; k++) {
			const angle = (k / ghostN) * 2 * Math.PI
			const [x, y, z] = project((ux * Math.cos(angle) + vx * Math.sin(angle)) * orbitRadius, (uy * Math.cos(angle) + vy * Math.sin(angle)) * orbitRadius, (uz * Math.cos(angle) + vz * Math.sin(angle)) * orbitRadius)
			const depth = (z / orbitRadius + 1) / 2
			const ghostDepthBase = options.ghostDepthBase || 0.4
			dots.push({
				x,
				y,
				z,
				depth,
				role: DOT_ROLES.GHOST,
				r: (options.ghostR || 0.9) * scale,
				white: options.ghostWhite ?? 0.72,
				a: (options.ghostA || 0.5) * (ghostDepthBase + (1 - ghostDepthBase) * depth)
			})
		}
		for (let particle = 0; particle < particles; particle++) {
			const angle = time * particleSpeed + (particle / particles) * 2 * Math.PI + h2 * 6
			const [x, y, z] = project((ux * Math.cos(angle) + vx * Math.sin(angle)) * orbitRadius, (uy * Math.cos(angle) + vy * Math.sin(angle)) * orbitRadius, (uz * Math.cos(angle) + vz * Math.sin(angle)) * orbitRadius)
			const depth = (z / orbitRadius + 1) / 2
			dots.push({ x, y, z, depth, role: DOT_ROLES.SIGNAL, r: ((options.partR || 1.2) + (options.partRDepth || 1.6) * depth) * scale, white: 0.3 - 0.22 * depth })
		}
	}
	paint(ctx, dots, dark, options.rMin, size)
}

function drawLattice(ctx, size, time, dark, options, mode) {
	const cx = size / 2; const cy = size / 2; const scale = radiusScale(size, options.rsPow || 0.6)
	const dots = []; const rings = options.latRings || options.rings || 15; const density = options.lonDensity || 40
	const maximumRawRadius = mode === 'wave'
		? ((options.rBase || 0.6) + (options.rDepth || 1.7)) * 1.4 * scale
		: ((options.rBase || 0.6) + (options.rDepth || 1.7) + (mode === 'globe' ? (options.rBoost || 1) : (options.rActive || 0))) * scale
	const maxPrimitiveRadius = resolvedDotRadius(size, maximumRawRadius, options.rMin)
	const radius = resolveModeRadius({
		size,
		desiredRatio: mode === 'wave' ? 0.874 : 0.82,
		maxProjection: mode === 'wave' ? 0.985 : 1,
		maxPrimitiveRadius
	})
	const project = makeProj(time * (mode === 'wave' ? 0.18 : 0.5), mode === 'wave' ? 0.38 : 0.4, cx, cy, mode === 'wave' ? 1 : radius)
	let moveState = null
	if (mode === 'rubik') moveState = solveCycle(time, options.moveCount || 14, 0.42, 1.2)
	for (let ring = 0; ring <= rings; ring++) {
		const latitude = -Math.PI / 2 + (ring / rings) * Math.PI; const cosLat = Math.cos(latitude); const sinLat = Math.sin(latitude)
		const longitudeCount = Math.max(1, Math.round(Math.abs(cosLat) * density))
		const wave = mode === 'wave' ? 0.62 * Math.sin(time * 2.1 - ring * 0.52) + 0.38 * Math.sin(time * 1.27 + ring * 0.83) : 0
		const radial = mode === 'wave' ? radius * (0.88 + 0.105 * wave) : 1
		for (let longitudeIndex = 0; longitudeIndex < longitudeCount; longitudeIndex++) {
			const longitude = (longitudeIndex / longitudeCount) * 2 * Math.PI
			let x = cosLat * Math.cos(longitude); let y = sinLat; let z = cosLat * Math.sin(longitude); let active = false
			if (mode === 'rubik') [x, y, z, active] = applyMoves([x, y, z], moveState.moves, moveState.cycle)
			const [px, py, projectedZ] = project(x * radial, y * radial, z * radial)
			const depth = mode === 'wave' ? (projectedZ / radius + 1) / 2 : (projectedZ + 1) / 2
			let boost = 0; let dotRadius = ((options.rBase || 0.6) + (options.rDepth || 1.7) * depth)
			if (mode === 'globe') {
				const scan = time * ((options.scanMul || 1) + 0.5)
				const delta = angleDelta(longitude + time * 0.5, scan)
				boost = Math.exp(-(delta * delta) / 0.18) * Math.max(0, projectedZ)
				dotRadius += (options.rBoost || 1) * boost
			} else if (active) {
				dotRadius += options.rActive || 0.3
			}
			dots.push({
				x: px, y: py, z: projectedZ, depth, role: active ? DOT_ROLES.ACTIVE : DOT_ROLES.SURFACE, r: dotRadius * (mode === 'wave' ? (1 + 0.4 * Math.max(0, wave)) : 1) * scale,
				white: mode === 'wave' ? 0.66 - 0.56 * depth - 0.1 * Math.max(0, wave) : (options.inkFar || 0.62) - (options.inkSpan || 0.54) * depth,
				a: mode === 'globe' ? (options.dimBase || 0.45) + (1 - (options.dimBase || 0.45)) * Math.min(1, boost) : 1
			})
		}
	}
	paint(ctx, dots, dark, options.rMin, size)
}

function solveCycle(time, count, slotDuration, rest) {
	const cycleDuration = 2 * count * slotDuration + rest; const cycleTime = time % cycleDuration
	const amount = new Array(count).fill(0); let active = -1
	if (cycleTime < 2 * count * slotDuration) {
		const slot = Math.floor(cycleTime / slotDuration); const progress = (cycleTime - slot * slotDuration) / slotDuration
		const eased = 1 - (1 - Math.min(1, progress / 0.7)) ** 3
		if (slot < count) { for (let i = 0; i < slot; i++) amount[i] = 1; amount[slot] = eased; active = slot }
		else { const index = 2 * count - 1 - slot; for (let i = 0; i < index; i++) amount[i] = 1; amount[index] = 1 - eased; active = index }
	}
	return { moves: makeMoves(count), cycle: { amount, active } }
}
function makeMoves(count) {
	const moves = []
	for (let i = 0; i < count; i++) {
		const axis = Math.min(2, Math.floor(hashD(i, 2.3) * 3)); const low = -1 + 0.5 * Math.min(3, Math.floor(hashD(i, 5.9) * 4)); const direction = hashD(i, 7.7) < 0.5 ? 1 : -1
		moves.push({ axis, low, high: low + 0.5, angle: direction * Math.PI / 2 })
	}
	return moves
}
function applyMoves(point, moves, cycle) {
	let [x, y, z] = point; let active = false
	for (let i = 0; i < moves.length; i++) {
		if (cycle.amount[i] <= 0) continue
		const move = moves[i]; const coordinate = move.axis === 0 ? x : move.axis === 1 ? y : z
		if (coordinate < move.low || coordinate >= move.high) continue
		if (i === cycle.active) active = true
		const angle = move.angle * cycle.amount[i]; const cosine = Math.cos(angle); const sine = Math.sin(angle)
		if (move.axis === 0) { const y2 = y * cosine - z * sine; z = y * sine + z * cosine; y = y2 }
		else if (move.axis === 1) { const x2 = x * cosine + z * sine; z = -x * sine + z * cosine; x = x2 }
		else { const x2 = x * cosine - y * sine; y = x * sine + y * cosine; x = x2 }
	}
	return [x, y, z, active]
}

function drawBraid(ctx, size, time, dark, options) {
	const cx = size / 2; const cy = size / 2; const scale = radiusScale(size, options.rsPow || 0.6); const dots = []; const ghostCount = options.ghostN ?? 150
	const maxPrimitiveRadius = Math.max(
		resolvedDotRadius(size, 0.8 * scale, options.rMin),
		resolvedDotRadius(size, ((options.rBase || 1.2) + (options.rDepth || 1.8)) * scale, options.rMin)
	)
	const radius = resolveModeRadius({ size, desiredRatio: 0.76, maxProjection: 1.075, maxPrimitiveRadius })
	const project = makeProj(time * 0.4, 0.3, cx, cy, 1)
	for (let i = 0; i < ghostCount; i++) { const direction = fibDir(i, ghostCount); const [x, y, z] = project(direction[0] * radius, direction[1] * radius, direction[2] * radius); const depth = (z / radius + 1) / 2; dots.push({ x, y, z, depth, role: DOT_ROLES.GHOST, r: 0.8 * scale, white: 0.78, a: 0.1 + 0.22 * depth }) }
	const strandCount = options.strandN || 52; const turns = options.turns || 3
	for (let strand = 0; strand < 3; strand++) {
		const phase = (strand / 3) * 2 * Math.PI
		for (let i = 0; i < strandCount; i++) {
			const u = (frac(i / strandCount + time * 0.045) * 2 - 1) * 0.96; const surface = Math.sqrt(Math.max(0, 1 - u * u)); const endFade = Math.min(1, (1 - Math.abs(u)) / 0.1); const angle = u * Math.PI * turns + phase; const weave = 1 + 0.075 * Math.sin(u * Math.PI * turns * 2 + phase * 2 + time * 0.8); const rr = surface * radius * weave; const [x, y, z] = project(Math.cos(angle) * rr, u * radius * weave, Math.sin(angle) * rr); const depth = (z / radius + 1) / 2
			dots.push({ x, y, z, depth, role: DOT_ROLES.SURFACE, r: ((options.rBase || 1.2) + (options.rDepth || 1.8) * depth) * scale, white: 0.55 - 0.45 * depth, a: endFade * (0.45 + 0.55 * depth) })
		}
	}
	paint(ctx, dots, dark, options.rMin, size)
}

function drawRibbon(ctx, size, time, dark, options) {
	const cx = size / 2; const cy = size / 2; const spin = options.spin == null ? 1 : options.spin; const scale = radiusScale(size, options.rsPow || 0.6); const dots = []; const ghostCount = options.ghostN ?? 150
	const wobbleAmplitude = 0.23 * (options.wobMul || 1)
	const faceOnProjection = options.faceOn ? (1 + wobbleAmplitude) / (1 + 0.85 * wobbleAmplitude) : 1
	const maxPrimitiveRadius = Math.max(
		resolvedDotRadius(size, 0.8 * scale, options.rMin),
		resolvedDotRadius(size, ((options.rBase || 1.1) + (options.rDepth || 1.7)) * scale, options.rMin)
	)
	const radius = resolveModeRadius({ size, desiredRatio: 0.78, maxProjection: faceOnProjection, maxPrimitiveRadius })
	const project = makeProj(time * 0.1 * spin, 0.3, cx, cy, 1)
	for (let i = 0; i < ghostCount; i++) { const direction = fibDir(i, ghostCount); const [x, y, z] = project(direction[0] * radius, direction[1] * radius, direction[2] * radius); const depth = (z / radius + 1) / 2; dots.push({ x, y, z, depth, role: DOT_ROLES.GHOST, r: 0.8 * scale, white: 0.78, a: 0.1 + 0.22 * depth }) }
	const yAngle = time * 0.24 * spin; const tilt = options.faceOn ? -0.3 : 0.55 + 0.3 * Math.sin(time * 0.18) * spin; const ux = Math.cos(yAngle); const uy = 0; const uz = Math.sin(yAngle); const vx = -uz * Math.sin(tilt); const vy = Math.cos(tilt); const vz = ux * Math.sin(tilt); const nx = uy * vz - uz * vy; const ny = uz * vx - ux * vz; const nz = ux * vy - uy * vx; const baseRadius = options.faceOn ? radius / (1 + 0.85 * wobbleAmplitude) : radius; const lanes = Math.max(1, Math.round((options.lanes || 5) * (options.bandMul || 1))); const segments = options.segs || 88
	for (let lane = 0; lane < lanes; lane++) { const laneOffset = (lane - (lanes - 1) / 2) * 0.075; const edge = Math.abs(lane - (lanes - 1) / 2) / Math.max(1, (lanes - 1) / 2); for (let segment = 0; segment < segments; segment++) { const angle = (segment / segments) * 2 * Math.PI; const wobble = (0.16 * Math.sin(angle * 3 - time * 1.7 + lane * 0.22) + 0.07 * Math.sin(angle * 5 + time * 1.1)) * (options.wobMul || 1); const radial = options.faceOn ? 1 + wobble : 1; const offset = options.faceOn ? laneOffset : laneOffset + wobble; const x = ux * Math.cos(angle) + vx * Math.sin(angle) + nx * offset; const y = uy * Math.cos(angle) + vy * Math.sin(angle) + ny * offset; const z = uz * Math.cos(angle) + vz * Math.sin(angle) + nz * offset; const length = Math.sqrt(x * x + y * y + z * z); const [px, py, projectedZ] = project((x / length) * baseRadius * radial, (y / length) * baseRadius * radial, (z / length) * baseRadius * radial); const depth = (projectedZ / radius + 1) / 2; dots.push({ x: px, y: py, z: projectedZ, depth, role: DOT_ROLES.SURFACE, r: ((options.rBase || 1.1) + (options.rDepth || 1.7) * depth) * (1 - 0.25 * edge) * scale, white: 0.52 - 0.44 * depth + 0.18 * edge, a: 0.4 + 0.6 * depth }) } }
	paint(ctx, dots, dark, options.rMin, size)
}

function drawWeb(ctx, size, time, dark, options) {
	const cx = size / 2; const cy = size / 2; const scale = radiusScale(size, options.rsPow || 0.6); const nodeCount = options.nodeN || 30; const threshold = options.thr || 0.72; const nodes = []
	const maximumNodeRadius = Math.max(
		((options.nodeR || 1.4) + (options.nodeRDepth || 1.8)) * 1.25 * scale,
		((options.nodeR || 1.4) * 1.5 + (options.nodeRDepth || 1.8)) * scale
	)
	const maxPrimitiveRadius = resolvedDotRadius(size, maximumNodeRadius, options.rMin)
	const lineWidth = resolvedLineWidth(size, Math.max(0.6, (options.lineW || 0.8) * scale))
	const radius = resolveModeRadius({ size, desiredRatio: 0.8 * (options.spread || 1), maxPrimitiveRadius, maxLineHalfWidth: lineWidth / 2 })
	const project = makeProj(time * 0.12, 0.32, cx, cy, radius)
	for (let i = 0; i < nodeCount; i++) { const direction = fibDir(i, nodeCount); const x = direction[0] + 0.3 * (vnoise(i * 0.31 + 9, time * 0.24) - 0.5) * 2; const y = direction[1] + 0.3 * (vnoise(i * 0.53 + 27, time * 0.21) - 0.5) * 2; const z = direction[2] + 0.3 * (vnoise(i * 0.77 + 55, time * 0.27) - 0.5) * 2; const length = Math.sqrt(x * x + y * y + z * z); nodes.push([x / length, y / length, z / length]) }
	const lines = []; const dots = []; for (let i = 0; i < nodeCount; i++) { for (let j = i + 1; j < nodeCount; j++) { const dx = nodes[i][0] - nodes[j][0]; const dy = nodes[i][1] - nodes[j][1]; const dz = nodes[i][2] - nodes[j][2]; const distance = Math.sqrt(dx * dx + dy * dy + dz * dz); if (distance >= threshold) continue; const [x1, y1, z1] = project(nodes[i][0], nodes[i][1], nodes[i][2]); const [x2, y2, z2] = project(nodes[j][0], nodes[j][1], nodes[j][2]); const depth = ((z1 + z2) / 2 + 1) / 2; lines.push({ x1, y1, x2, y2, depth, role: LINE_ROLES.STRUCTURE, white: 0.42, a: (1 - distance / threshold) * (0.3 + 0.55 * depth), w: lineWidth }) } }
	for (let i = 0; i < nodeCount; i++) { const [x, y, z] = project(nodes[i][0], nodes[i][1], nodes[i][2]); const depth = (z + 1) / 2; const pulse = 1 + 0.25 * Math.sin(time * 1.4 + i * 2.7); dots.push({ x, y, z, depth, role: DOT_ROLES.SURFACE, r: ((options.nodeR || 1.4) + (options.nodeRDepth || 1.8) * depth) * pulse * scale, white: 0.55 - 0.45 * depth }) }
	const signals = options.signals || 5; for (let signal = 0; signal < signals; signal++) { const segment = Math.floor(time * 0.55 + signal * 7.31); const a = Math.floor(hashD(segment, signal * 3.1 + 1.7) * nodeCount); const b = Math.floor(hashD(segment, signal * 5.7 + 4.2) * nodeCount); if (a === b) continue; const fraction = frac(time * 0.55 + signal * 7.31); const x = lerp(nodes[a][0], nodes[b][0], fraction); const y = lerp(nodes[a][1], nodes[b][1], fraction); const z = lerp(nodes[a][2], nodes[b][2], fraction); const length = Math.max(1e-6, Math.sqrt(x * x + y * y + z * z)); const [px, py, projectedZ] = project(x / length, y / length, z / length); const depth = (projectedZ + 1) / 2; dots.push({ x: px, y: py, z: projectedZ, depth, role: DOT_ROLES.SIGNAL, r: ((options.nodeR || 1.4) * 1.5 + (options.nodeRDepth || 1.8) * depth) * scale, white: 0.05, a: 0.5 + 0.5 * depth }) }
	paintLines(ctx, lines, dark, size); paint(ctx, dots, dark, options.rMin, size)
}

function smoothE(value) { return value * value * (3 - 2 * value) }
function polyPath(vertices) { const lengths = []; let total = 0; for (let i = 0; i < vertices.length; i++) { const a = vertices[i]; const b = vertices[(i + 1) % vertices.length]; const length = Math.hypot(b[0] - a[0], b[1] - a[1]); lengths.push(length); total += length } return fraction => { let target = fraction * total; let index = 0; while (target > lengths[index] && index < vertices.length - 1) { target -= lengths[index]; index++ } const a = vertices[index]; const b = vertices[(index + 1) % vertices.length]; const progress = lengths[index] ? Math.min(1, target / lengths[index]) : 0; return [a[0] + (b[0] - a[0]) * progress, a[1] + (b[1] - a[1]) * progress] } }
const CIRCLE = fraction => { const angle = -Math.PI / 2 + fraction * 2 * Math.PI; return [Math.cos(angle) * 0.24, Math.sin(angle) * 0.24] }
const TRIANGLE = polyPath([[0, -0.26], [0.24, 0.16], [-0.24, 0.16]])
const SQUARE = polyPath([[0, -0.2], [0.2, -0.2], [0.2, 0.2], [-0.2, 0.2], [-0.2, -0.2]])
function drawMorph(ctx, size, time, dark, options) {
	const cycle = [CIRCLE, TRIANGLE, SQUARE]; const hold = 1.4; const morph = 0.9; const segment = hold + morph; const localTime = time % (segment * cycle.length); const index = Math.floor(localTime / segment); const local = localTime - index * segment; const blend = local > hold ? smoothE((local - hold) / morph) : 0; const spread = options.spread || 1; const points = []; const sampleCount = 160
	for (let i = 0; i < sampleCount; i++) { const fraction = i / sampleCount; const a = cycle[index](fraction); const b = cycle[(index + 1) % cycle.length](fraction); points.push([(a[0] + (b[0] - a[0]) * blend) * spread, (a[1] + (b[1] - a[1]) * blend) * spread]) }
	const lengths = []; let total = 0; for (let i = 0; i < sampleCount; i++) { const a = points[i]; const b = points[(i + 1) % sampleCount]; const length = Math.hypot(b[0] - a[0], b[1] - a[1]); lengths.push(length); total += length }
	const count = Math.max(options.iconCount || 0, 6, Math.round(34 * (options.iconD || 1))); const radius = (options.rDot || 0.021) * 1.35 * spread; const pulse = 1 + 0.02 * Math.sin(local * 3.1); const dots = []; const center = size / 2; let segmentIndex = 0; let accumulated = 0
	const dotRadius = resolvedDotRadius(size, Math.max(0.35, radius * size), options.rMin)
	const shapeRadius = resolveModeRadius({ size, desiredRatio: 0.52 * spread, maxProjection: 1.02, maxPrimitiveRadius: dotRadius })
	const coordinateScale = shapeRadius / Math.max(1e-6, 0.26 * spread)
	for (let i = 0; i < count; i++) { const target = (i / count) * total; while (accumulated + lengths[segmentIndex] < target && segmentIndex < sampleCount - 1) { accumulated += lengths[segmentIndex]; segmentIndex++ } const a = points[segmentIndex]; const b = points[(segmentIndex + 1) % sampleCount]; const progress = lengths[segmentIndex] ? Math.min(1, (target - accumulated) / lengths[segmentIndex]) : 0; dots.push({ x: center + (a[0] + (b[0] - a[0]) * progress) * pulse * coordinateScale, y: center + (a[1] + (b[1] - a[1]) * progress) * pulse * coordinateScale, z: 0, depth: 0.5, role: DOT_ROLES.ACTIVE, r: dotRadius, white: 0.1 }) }
	paint(ctx, dots, dark, options.rMin, size)
}

const MODE_DRAWS = {
	orbits: drawOrbits,
	globe: (ctx, size, time, dark, options) => drawLattice(ctx, size, time, dark, options, 'globe'),
	rubik: (ctx, size, time, dark, options) => drawLattice(ctx, size, time, dark, options, 'rubik'),
	wave: (ctx, size, time, dark, options) => drawLattice(ctx, size, time, dark, options, 'wave'),
	web: drawWeb,
	braid: drawBraid,
	ribbon: drawRibbon,
	ring: drawRibbon,
	morph: drawMorph
}

export function renderOrbFrame(ctx, config, time = 0, sizeOverride = null) {
	const size = normalizeSize(sizeOverride == null ? config?.size : sizeOverride)
	const preset = resolveOrbRenderProfile(config?.state, size)
	MODE_DRAWS[preset.mode](ctx, size, time, config?.dark !== false, preset.options)
	return { size, mode: preset.mode, inset: EDGE_INSETS[size], scale: 1, offset: 0 }
}

function frameTime() {
	return typeof performance !== 'undefined' && performance.now
		? performance.now() / 1000 : Date.now() / 1000
}

export default {
	data() {
		return {
			canvas: null,
			canvasHost: null,
			context: null,
			root: null,
			config: null,
			dpr: 1,
			raf: 0,
			running: false,
			visible: true,
			hidden: false,
			observer: null,
			onVisibilityChange: null
		}
	},
	mounted(event, instance, ownerInstance) {
		this.root = ownerInstance?.$el || instance?.$el || this.$el || null
		this.connectCanvas()
	},
	beforeUnmount() {
		this.teardown()
	},
	beforeDestroy() {
		this.teardown()
	},
	methods: {
		update(value, oldValue, ownerInstance, instance) {
			this.root = this.root || ownerInstance?.$el || instance?.$el || this.$el || null
			this.config = value || null
			this.connectCanvas()
			this.restart()
		},
		connectCanvas() {
			if (!this.root) return
			const { canvasHost, canvas } = resolveNativeCanvas(this.root)
			if (!canvas) return
			this.canvasHost = canvasHost || canvas
			if (canvas === this.canvas && this.context) {
				this.context.__hidpi__ = false
				return
			}
			this.canvas = canvas
			this.context = canvas.getContext?.('2d') || null
			if (this.context) this.context.__hidpi__ = false
			this.dpr = resolveOrbCanvasMetrics(this.config?.size, globalThis.devicePixelRatio || this.dpr).dpr
			this.observer?.disconnect?.()
			if (typeof IntersectionObserver !== 'undefined') {
				this.observer = new IntersectionObserver(entries => {
					this.visible = Boolean(entries[0]?.isIntersecting)
					if (this.visible && !this.hidden) this.restart()
					else this.stop()
				})
				this.observer.observe(canvas)
			}
			if (!this.onVisibilityChange && typeof document !== 'undefined') {
				this.onVisibilityChange = () => {
					this.hidden = document.visibilityState === 'hidden'
					if (this.hidden) this.stop()
					else this.restart()
				}
				document.addEventListener('visibilitychange', this.onVisibilityChange)
			}
		},
		configureCanvas(size) {
			if (!isNativeCanvasCandidate(this.canvas)) return
			const metrics = resolveOrbCanvasMetrics(size, this.dpr)
			const hostStyle = this.canvasHost?.style
			if (hostStyle) {
				hostStyle.width = metrics.hostCssSize
				hostStyle.height = metrics.hostCssSize
				hostStyle.minWidth = metrics.hostCssSize
				hostStyle.minHeight = metrics.hostCssSize
				hostStyle.maxWidth = metrics.hostCssSize
				hostStyle.maxHeight = metrics.hostCssSize
				hostStyle.flexBasis = metrics.hostCssSize
				hostStyle.boxSizing = 'content-box'
				hostStyle.overflow = 'visible'
			}
			// Resizing a canvas clears its bitmap; only do it when its render metrics change.
			if (this.canvas.width !== metrics.pixelSize) this.canvas.width = metrics.pixelSize
			if (this.canvas.height !== metrics.pixelSize) this.canvas.height = metrics.pixelSize
			if (this.canvas.style) {
				this.canvas.style.width = metrics.canvasCssSize
				this.canvas.style.height = metrics.canvasCssSize
				this.canvas.style.minWidth = metrics.canvasCssSize
				this.canvas.style.minHeight = metrics.canvasCssSize
				this.canvas.style.maxWidth = metrics.canvasCssSize
				this.canvas.style.maxHeight = metrics.canvasCssSize
				this.canvas.style.boxSizing = 'content-box'
				this.canvas.style.display = 'block'
			}
			this.context = this.canvas.getContext?.('2d') || this.context || null
			if (!this.context) return
			this.context.__hidpi__ = false
			return metrics
		},
		draw(time) {
			if (!this.config) return
			if (!isNativeCanvasCandidate(this.canvas) || !this.context || this.canvas?.isConnected === false) this.connectCanvas()
			if (!isNativeCanvasCandidate(this.canvas) || !this.context || this.canvas?.isConnected === false) return
			const size = normalizeSize(this.config.size)
			this.dpr = resolveOrbCanvasMetrics(size, globalThis.devicePixelRatio || this.dpr).dpr
			const metrics = this.configureCanvas(size)
			if (!metrics || !this.context) return
			this.context.__hidpi__ = false
			this.context.setTransform(1, 0, 0, 1, 0, 0)
			this.context.clearRect(0, 0, metrics.pixelSize, metrics.pixelSize)
			this.context.setTransform(metrics.contextScale, 0, 0, metrics.contextScale, 0, 0)
			renderOrbFrame(this.context, this.config, time, metrics.renderSize)
		},
		restart() {
			this.stop()
			if (!this.config) return
			if (this.config.reduced) {
				this.draw(0.6)
				return
			}
			const metrics = resolveOrbCanvasMetrics(this.config.size, this.dpr)
			const presetSpeed = resolveOrbRenderProfile(this.config.state, metrics.renderSize).speed
			const effectiveSpeed = presetSpeed * (Number(this.config.speed) || 1)
			this.draw(frameTime() * effectiveSpeed)
			if (this.config.paused || this.hidden || !this.visible) return
			this.running = true
			const loop = () => {
				if (!this.running) return
				if (this.canvas?.isConnected === false) {
					this.teardown()
					return
				}
				this.draw(frameTime() * effectiveSpeed)
				this.raf = requestAnimationFrame(loop)
			}
			this.raf = requestAnimationFrame(loop)
		},
		stop() {
			this.running = false
			if (this.raf) cancelAnimationFrame(this.raf)
			this.raf = 0
		},
		teardown() {
			this.stop()
			this.observer?.disconnect?.()
			if (this.onVisibilityChange && typeof document !== 'undefined') {
				document.removeEventListener('visibilitychange', this.onVisibilityChange)
			}
			this.observer = null
			this.onVisibilityChange = null
			this.canvas = null
			this.canvasHost = null
			this.context = null
			this.root = null
		}
	}
}
