/**
 * Android 原生 OAuth 单一执行协调器，负责把原生回调、App 生命周期恢复和后台请求
 * 收敛到同一个内存 Promise；它不持久化、不记录任何 OAuth 或会话令牌。
 */

export const AndroidOAuthPhase = Object.freeze({
	IDLE: 'IDLE',
	PREAUTH_READY: 'PREAUTH_READY',
	FLOW_STARTED: 'FLOW_STARTED',
	NATIVE_PICKER: 'NATIVE_PICKER',
	NATIVE_COMPLETE: 'NATIVE_COMPLETE',
	SESSION_COMPLETE: 'SESSION_COMPLETE',
	CREDENTIALS_COMMITTED: 'CREDENTIALS_COMMITTED',
	DONE: 'DONE'
})

export const AndroidOAuthFailurePhase = Object.freeze({
	NETWORK_UNKNOWN: 'NETWORK_UNKNOWN',
	FLOW_EXPIRED: 'FLOW_EXPIRED',
	PREAUTH_MISMATCH: 'PREAUTH_MISMATCH',
	FAILED: 'FAILED'
})

const TERMINAL_PHASES = new Set([
	AndroidOAuthPhase.DONE,
	...Object.values(AndroidOAuthFailurePhase)
])

let activeOperation = null

function requireOperationKey(operationKey) {
	if (typeof operationKey !== 'string' || operationKey.trim() === '') {
		throw new TypeError('Android OAuth operation key is required.')
	}
	return operationKey.trim()
}

function isTerminal(phase) {
	return TERMINAL_PHASES.has(phase)
}

function createOperationContext(key) {
	return {
		operationKey: key,
		phase: AndroidOAuthPhase.IDLE,
		failure: null,
		promise: null,
		settled: false
	}
}

/**
 * 为同一 operationKey 建立唯一 Promise；不同登录操作会在旧操作进入终态后排队，避免
 * 第二个原生选择器抢占同一 Flow。operation 接收一个仅含状态控制方法的上下文。
 */
export function run(operationKey, operation) {
	const key = requireOperationKey(operationKey)
	if (typeof operation !== 'function') throw new TypeError('Android OAuth operation is required.')

	if (activeOperation) {
		const existing = activeOperation
		if (existing.operationKey === key) return existing.promise
		// 不同操作必须等待旧操作进入终态；旧操作失败不应吞掉新操作自己的结果。
		return existing.promise
			.catch(() => undefined)
			.then(() => run(key, operation))
	}

	const context = createOperationContext(key)
	const setPhase = phase => {
		if (!Object.values(AndroidOAuthPhase).includes(phase)
			&& !Object.values(AndroidOAuthFailurePhase).includes(phase)) {
			throw new TypeError(`Unknown Android OAuth phase: ${phase}`)
		}
		if (context.settled && !isTerminal(phase)) return context.phase
		context.phase = phase
		return phase
	}
	const finish = value => {
		setPhase(AndroidOAuthPhase.DONE)
		context.settled = true
		return value
	}
	const fail = (failurePhase = AndroidOAuthFailurePhase.FAILED, error = null) => {
		const phase = Object.values(AndroidOAuthFailurePhase).includes(failurePhase)
			? failurePhase : AndroidOAuthFailurePhase.FAILED
		context.failure = error || null
		setPhase(phase)
		context.settled = true
		return error
	}
	activeOperation = context
	const operationContext = Object.freeze({
		operationKey: key,
		setPhase,
		finish,
		fail
	})
	context.promise = Promise.resolve()
		.then(() => operation(operationContext))
		.then(value => {
			if (!isTerminal(context.phase)) finish(value)
			return value
		}, error => {
			if (!isTerminal(context.phase)) fail(AndroidOAuthFailurePhase.FAILED, error)
			throw error
		})
		.finally(() => {
			context.settled = true
			if (activeOperation === context) activeOperation = null
		})
	return context.promise
}

/** 加入当前同一 Flow；没有活动操作或 operationKey 不匹配时返回 null。 */
export function join(operationKey) {
	if (!activeOperation) return null
	if (operationKey != null && activeOperation.operationKey !== operationKey) return null
	return activeOperation.promise
}

export function isActive() {
	return activeOperation !== null
}

export function currentPhase() {
	return activeOperation?.phase || AndroidOAuthPhase.IDLE
}

/** OAuth 尚未完成时阻止后台 WebRTC，避免旧 PreAuth/epoch 被异步探测改写。 */
export function isBlockingWebRtc() {
	return activeOperation !== null && !isTerminal(activeOperation.phase)
}

export function finish(value) {
	if (!activeOperation) return value
	activeOperation.phase = AndroidOAuthPhase.DONE
	return value
}

export function fail(failurePhase = AndroidOAuthFailurePhase.FAILED, error = null) {
	if (!activeOperation) return error
	const phase = Object.values(AndroidOAuthFailurePhase).includes(failurePhase)
		? failurePhase : AndroidOAuthFailurePhase.FAILED
	activeOperation.failure = error || null
	activeOperation.phase = phase
	return error
}

export const androidOAuthCoordinator = Object.freeze({
	run,
	join,
	isActive,
	currentPhase,
	isBlockingWebRtc,
	finish,
	fail,
	setPhase: phase => {
		if (!activeOperation) return AndroidOAuthPhase.IDLE
		if (!Object.values(AndroidOAuthPhase).includes(phase)
			&& !Object.values(AndroidOAuthFailurePhase).includes(phase)) {
			throw new TypeError(`Unknown Android OAuth phase: ${phase}`)
		}
		activeOperation.phase = phase
		return phase
	}
})
