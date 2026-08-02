import { aiConversationApi } from './ai-conversation-api.js'
// #ifdef APP-PLUS
import { putFile } from '@/uni_modules/ait-oss-put'
// #endif

export const MAX_CONCURRENCY = 3
export const MAX_FILE_BYTES = 100 * 1024 * 1024
export const MAX_TOTAL_BYTES = 200 * 1024 * 1024

function uploadError(code, message) {
	return Object.assign(new Error(message), { code })
}

export function conversationFileMetadata(file) {
	const fileName = String(file?.fileName || file?.name || 'attachment.bin')
	const contentType = String(file?.contentType || file?.type || 'application/octet-stream')
	const sizeBytes = Number(file?.sizeBytes || file?.size || 0)
	if (!Number.isSafeInteger(sizeBytes) || sizeBytes <= 0) {
		throw uploadError('AI_ATTACHMENT_SIZE_INVALID', '无法读取文件大小。')
	}
	if (sizeBytes > MAX_FILE_BYTES) {
		throw uploadError('AI_ATTACHMENT_TOO_LARGE', '单个附件不能超过 100 MB。')
	}
	return { fileName, contentType, sizeBytes }
}

function uploadWithAndroid(file, signed, onProgress) {
	// #ifdef APP-PLUS
	let connection = null
	let settled = false
	let rejectCompletion = null
	const completed = new Promise((resolve, reject) => {
		rejectCompletion = reject
		if (!file.path) {
			reject(uploadError('AI_ATTACHMENT_READ_UNSUPPORTED', '当前平台无法读取所选文件。'))
			return
		}
		connection = putFile({
			url: signed.uploadUrl,
			headers: signed.uploadHeaders,
			filePath: file.path,
			sizeBytes: Number(signed.sizeBytes),
			onProgress(progress) {
				if (!settled) onProgress?.(Number(progress))
			},
			onSuccess() {
				if (settled) return
				settled = true
				resolve()
			},
			onError(failure) {
				if (settled) return
				settled = true
				reject(Object.assign(
					new Error(failure?.message || '附件上传失败。'),
					{ code: failure?.code || 'OSS_NETWORK_ERROR' }
				))
			}
		})
	})
	return {
		completed,
		cancel() {
			if (settled) return
			settled = true
			connection?.close?.()
			rejectCompletion?.(uploadError('OSS_UPLOAD_CANCELLED', '附件上传已取消。'))
		}
	}
	// #endif
	// #ifndef APP-PLUS
	return {
		completed: Promise.reject(uploadError(
			'AI_ATTACHMENT_UPLOAD_UNSUPPORTED',
			'当前平台不支持 Android 附件上传。'
		)),
		cancel() {}
	}
	// #endif
}

function uploadWithH5(file, signed, onProgress) {
	let request = null
	let settled = false
	const completed = new Promise((resolve, reject) => {
		const body = file.raw || file.file || file
		if (Number.isFinite(body?.size) && Number(body.size) !== Number(signed.sizeBytes)) {
			reject(uploadError('AI_ATTACHMENT_LOCAL_FILE_CHANGED', '附件临时文件已变化，请重新选择。'))
			return
		}
		request = new XMLHttpRequest()
		request.open('PUT', signed.uploadUrl, true)
		Object.entries(signed.uploadHeaders).forEach(([name, value]) => {
			// Content-Length 由浏览器依据请求体设置；脚本写入该受保护请求头会被浏览器拒绝。
			if (String(name).toLowerCase() === 'content-length') return
			request.setRequestHeader(name, value)
		})
		request.upload.onprogress = event => {
			if (!settled && event.lengthComputable && event.total > 0) {
				onProgress?.(Math.min(99, Math.floor(event.loaded * 100 / event.total)))
			}
		}
		request.onload = () => {
			if (settled) return
			settled = true
			if (request.status >= 200 && request.status < 300) {
				onProgress?.(100)
				resolve()
			} else {
				reject(uploadError(`OSS_${request.status}`, '附件上传失败。'))
			}
		}
		request.onerror = () => {
			if (settled) return
			settled = true
			reject(uploadError('OSS_NETWORK_ERROR', '附件上传失败。'))
		}
		request.onabort = () => {
			if (settled) return
			settled = true
			reject(uploadError('OSS_UPLOAD_CANCELLED', '附件上传已取消。'))
		}
		request.send(body)
	})
	return {
		completed,
		cancel() {
			if (!settled) request?.abort?.()
		}
	}
}

function platformUpload(file, signed, onProgress) {
	// #ifdef H5
	return uploadWithH5(file, signed, onProgress)
	// #endif
	// #ifndef H5
	return uploadWithAndroid(file, signed, onProgress)
	// #endif
}

export function isRetryableUploadFailure(error) {
	const code = String(error?.code || '')
	if (code === 'OSS_NETWORK_ERROR') return true
	const match = /^OSS_(\d{3})$/.exec(code)
	if (!match) return false
	const status = Number(match[1])
	return status === 408 || status === 429 || status >= 500
}

function deferred() {
	let resolve
	let reject
	const promise = new Promise((onResolve, onReject) => {
		resolve = onResolve
		reject = onReject
	})
	return { promise, resolve, reject }
}

/**
 * 创建共享三并发队列；每个选择批次只申请一次预签名，失败重试则为单文件申请新地址。
 */
export function createConversationUploadManager({
	createPreuploads = files => aiConversationApi.createPreuploads(files),
	startUpload = platformUpload,
	maxConcurrency = MAX_CONCURRENCY
} = {}) {
	const queue = []
	let active = 0

	function drain() {
		while (active < maxConcurrency && queue.length) {
			const job = queue.shift()
			if (job.cancelled) {
				job.rejectCancelled()
				continue
			}
			active += 1
			run(job)
				.finally(() => {
					active -= 1
					drain()
				})
		}
	}

	async function run(job) {
		let signed = job.signed
		let uploadSessionId = job.uploadSessionId
		for (let attempt = 0; attempt < 2; attempt += 1) {
			if (job.cancelled) {
				job.rejectCancelled()
				return
			}
			job.handlers.onState?.(job.index, 'UPLOADING', { retrying: attempt > 0 })
			const controller = startUpload(job.file, signed, progress => {
				if (!job.cancelled) job.handlers.onProgress?.(job.index, progress)
			})
			job.activeController = controller
			try {
				await controller.completed
				if (job.cancelled) {
					job.rejectCancelled()
					return
				}
				const uploaded = Object.freeze({
					uploadSessionId,
					attachmentId: signed.attachmentId,
					fileName: signed.fileName,
					contentType: signed.contentType,
					sizeBytes: String(signed.sizeBytes),
					progress: 100
				})
				job.handlers.onUploaded?.(job.index, uploaded)
				job.resolve(uploaded)
				return
			} catch (error) {
				if (job.cancelled || error?.code === 'OSS_UPLOAD_CANCELLED') {
					job.rejectCancelled()
					return
				}
				if (attempt === 0 && isRetryableUploadFailure(error)) {
					try {
						const refreshed = await createPreuploads([job.declared])
						if (!refreshed?.files?.[0]) throw uploadError(
							'AI_ATTACHMENT_PREUPLOAD_INVALID',
							'附件重试地址无效。'
						)
						signed = refreshed.files[0]
						uploadSessionId = refreshed.uploadSessionId
						continue
					} catch (refreshError) {
						job.handlers.onFailed?.(job.index, refreshError)
						job.reject(refreshError)
						return
					}
				}
				job.handlers.onFailed?.(job.index, error)
				job.reject(error)
				return
			} finally {
				job.activeController = null
			}
		}
	}

	function enqueueBatch(files, handlers = {}) {
		const selected = Array.from(files || [])
		if (!selected.length) throw uploadError('AI_ATTACHMENT_INPUT_INVALID', '请选择附件。')
		if (selected.length > 8) throw uploadError(
			'AI_ATTACHMENT_COUNT_EXCEEDED',
			'每条消息最多添加 8 个附件。'
		)
		const declared = selected.map(conversationFileMetadata)
		const totalBytes = declared.reduce((total, file) => total + file.sizeBytes, 0)
		if (!Number.isSafeInteger(totalBytes) || totalBytes > MAX_TOTAL_BYTES) {
			throw uploadError('AI_ATTACHMENT_TOTAL_SIZE_EXCEEDED', '单条消息的附件总大小不能超过 200 MB。')
		}

		const jobs = selected.map((file, index) => {
			const completion = deferred()
			const job = {
				file,
				declared: declared[index],
				index,
				handlers,
				cancelled: false,
				settled: false,
				activeController: null,
				resolve(value) {
					if (job.settled) return
					job.settled = true
					completion.resolve(value)
				},
				reject(error) {
					if (job.settled) return
					job.settled = true
					completion.reject(error)
				},
				rejectCancelled() {
					job.reject(uploadError('OSS_UPLOAD_CANCELLED', '附件上传已取消。'))
				}
			}
			handlers.onState?.(index, 'PREPARING', { retrying: false })
			return { job, completion }
		})

		Promise.resolve()
			.then(() => createPreuploads(declared))
			.then(preuploads => {
				if (!preuploads || !Array.isArray(preuploads.files)
					|| preuploads.files.length !== selected.length) {
					throw uploadError('AI_ATTACHMENT_PREUPLOAD_INVALID', '附件预上传响应不完整。')
				}
				jobs.forEach(({ job }, index) => {
					job.signed = preuploads.files[index]
					job.uploadSessionId = preuploads.uploadSessionId
					queue.push(job)
				})
				drain()
			})
			.catch(error => {
				jobs.forEach(({ job }) => {
					if (job.cancelled) job.rejectCancelled()
					else {
						handlers.onFailed?.(job.index, error)
						job.reject(error)
					}
				})
			})

		const tasks = jobs.map(({ job, completion }) => Object.freeze({
			completed: completion.promise,
			cancel() {
				if (job.settled || job.cancelled) return
				job.cancelled = true
				job.activeController?.cancel?.()
				job.rejectCancelled()
			}
		}))
		const completed = Promise.allSettled(tasks.map(task => task.completed))
		return Object.freeze({
			tasks: Object.freeze(tasks),
			completed,
			cancel() { tasks.forEach(task => task.cancel()) }
		})
	}

	return Object.freeze({ enqueueBatch })
}

const defaultUploadManager = createConversationUploadManager()

/**
 * 立即把一批新选择的文件加入共享上传队列，并返回可取消的批次与逐文件控制器。
 */
export function uploadConversationFiles(files, handlers = {}) {
	return defaultUploadManager.enqueueBatch(files, handlers)
}
