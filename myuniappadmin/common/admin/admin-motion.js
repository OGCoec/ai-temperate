export const ADMIN_MOTION_PRESETS = Object.freeze({
	quiet: Object.freeze({ dampingRatio: 1, response: 0.34 }),
	swift: Object.freeze({ dampingRatio: 1, response: 0.3 }),
	sheet: Object.freeze({ dampingRatio: 0.85, response: 0.3 })
})

const activeMotions = new WeakMap()

function h5MotionEnabled() {
	let enabled = false
	// #ifdef H5
	enabled = true
	// #endif
	return enabled
}

function animationFrameApi() {
	if (!h5MotionEnabled()) return { request: null, cancel: null }
	const scope = typeof globalThis === 'undefined' ? {} : globalThis
	return {
		request: typeof scope.requestAnimationFrame === 'function'
			? scope.requestAnimationFrame.bind(scope)
			: null,
		cancel: typeof scope.cancelAnimationFrame === 'function'
			? scope.cancelAnimationFrame.bind(scope)
			: null
	}
}

export function adminSupportsSpringMotion() {
	return Boolean(animationFrameApi().request)
}

export function adminPrefersReducedMotion() {
	return typeof globalThis !== 'undefined'
		&& typeof globalThis.matchMedia === 'function'
		&& globalThis.matchMedia('(prefers-reduced-motion: reduce)').matches
}

function createCompletedMotionHandle() {
	return Object.freeze({
		cancel() {},
		get active() { return false }
	})
}

export function cancelAdminMotion(owner) {
	if (!owner || (typeof owner !== 'object' && typeof owner !== 'function')) return
	activeMotions.get(owner)?.cancel()
}

export function animateAdminSpring({
	owner,
	from = 0,
	to = 1,
	initialVelocity = 0,
	preset = ADMIN_MOTION_PRESETS.quiet,
	precision = 0.001,
	onUpdate = () => {},
	onComplete = () => {}
} = {}) {
	const start = Number(from)
	const target = Number(to)
	if (!Number.isFinite(start) || !Number.isFinite(target)) {
		throw new TypeError('Spring endpoints must be finite numbers.')
	}

	const frames = animationFrameApi()
	if (adminPrefersReducedMotion() || !frames.request) {
		onUpdate(to)
		onComplete(to)
		return createCompletedMotionHandle()
	}

	cancelAdminMotion(owner)

	let current = start
	let velocity = Number(initialVelocity) || 0
	let previousTimestamp = 0
	let frameId = 0
	let active = true
	const response = Math.max(.12, Number(preset?.response) || .34)
	const dampingRatio = Math.max(.1, Number(preset?.dampingRatio) || 1)
	const angularFrequency = (Math.PI * 2) / response

	const handle = {
		cancel() {
			if (!active) return
			active = false
			if (frameId && frames.cancel) frames.cancel(frameId)
			if (owner && activeMotions.get(owner) === handle) activeMotions.delete(owner)
		},
		get active() {
			return active
		}
	}

	const step = timestamp => {
		if (!active) return
		const elapsed = previousTimestamp ? (timestamp - previousTimestamp) / 1000 : 1 / 60
		const delta = Math.min(Math.max(elapsed, 1 / 240), 1 / 30)
		previousTimestamp = timestamp

		const acceleration = angularFrequency * angularFrequency * (target - current)
			- 2 * dampingRatio * angularFrequency * velocity
		velocity += acceleration * delta
		current += velocity * delta
		onUpdate(current)

		if (Math.abs(target - current) <= precision && Math.abs(velocity) <= precision) {
			active = false
			onUpdate(target)
			if (owner && activeMotions.get(owner) === handle) activeMotions.delete(owner)
			onComplete(target)
			return
		}
		frameId = frames.request(step)
	}

	if (owner && (typeof owner === 'object' || typeof owner === 'function')) {
		activeMotions.set(owner, handle)
	}
	onUpdate(start)
	frameId = frames.request(step)
	return handle
}
