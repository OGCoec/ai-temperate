/**
 * 同步启动显式取消后立即关闭当前传输；返回的 Promise 用于页面收敛确认状态，但不会延迟本地断流。
 */
export function startDirectResponseCancellation({
	requestCancellation,
	closeTransport
} = {}) {
	let cancellation
	try {
		cancellation = Promise.resolve(requestCancellation?.())
	} catch (error) {
		cancellation = Promise.reject(error)
	}

	let closeFailure = null
	try {
		closeTransport?.()
	} catch (error) {
		closeFailure = error
	}

	// 取消失败优先向页面暴露；取消已确认后再报告本地关闭失败，二者都不会撤销已经发出的请求。
	return cancellation.then(
		value => {
			if (closeFailure) throw closeFailure
			return value
		},
		error => { throw error }
	)
}
