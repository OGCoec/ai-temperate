const REQUIRED_API_METHODS = Object.freeze([
	'createJob',
	'getJob',
	'eventsPath',
	'getRecoveredJobs',
	'resumeJob'
])
const REQUIRED_CONTRACT_VERSION = 4
const VERSION_MISMATCH_MESSAGE = '前端资源版本不一致，请清除本站缓存后重新加载。'

function versionMismatchError() {
	const error = new Error(VERSION_MISMATCH_MESSAGE)
	error.code = 'MAIL_INSPECTION_FRONTEND_VERSION_MISMATCH'
	return error
}

export function requireAdminMailInspectionApi(api) {
	if (!api
		|| api.contractVersion !== REQUIRED_CONTRACT_VERSION
		|| REQUIRED_API_METHODS.some(method => typeof api[method] !== 'function')) {
		throw versionMismatchError()
	}
	return api
}

export function createUnavailableAdminMailInspectionApi(error) {
	const unavailableError = error?.code === 'MAIL_INSPECTION_FRONTEND_VERSION_MISMATCH'
		? error
		: versionMismatchError()
	const reject = async () => { throw unavailableError }
	const rejectPath = () => { throw unavailableError }
	return Object.freeze({
		contractVersion: 0,
		createJob: reject,
		getJob: reject,
		eventsPath: rejectPath,
		getRecoveredJobs: reject,
		resumeJob: reject
	})
}
