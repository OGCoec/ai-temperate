const MEDIA_TYPES = new Set(['IMAGE', 'VIDEO'])
const STATES = new Set(['UPLOADING', 'VERIFYING', 'COMPLETED', 'FAILED'])

function numberInRange(value, minimum, maximum, fallback = null) {
	const number = Number(value)
	return Number.isSafeInteger(number) && number >= minimum && number <= maximum
		? number : fallback
}

/**
 * 为图片和视频生成稳定的内存状态键；该键不包含 URL、OSS Key 或其他敏感媒体引用。
 */
export function mediaUploadProgressKey(value) {
	const mediaType = String(value?.mediaType || '').toUpperCase()
	const outputIndex = numberInRange(value?.outputIndex, 0, 9)
	if (!MEDIA_TYPES.has(mediaType) || outputIndex == null) return ''
	return mediaType === 'VIDEO' ? 'video:0' : `image:${outputIndex}`
}

/**
 * 规范化后端临时 SSE 进度。非法或不完整事件直接忽略，避免它们覆盖已经可预览的附件。
 */
export function normalizeMediaUploadProgress(value) {
	const mediaType = String(value?.mediaType || '').toUpperCase()
	const state = String(value?.state || '').toUpperCase()
	const outputIndex = numberInRange(value?.outputIndex, 0, 9)
	const attempt = numberInRange(value?.attempt, 1, 3)
	const maxAttempts = numberInRange(value?.maxAttempts, attempt || 1, 3)
	const sequence = numberInRange(value?.sequence, 1, Number.MAX_SAFE_INTEGER)
	const transferredBytes = numberInRange(value?.transferredBytes, 0, Number.MAX_SAFE_INTEGER)
	const totalBytes = value?.totalBytes == null
		? null : numberInRange(value.totalBytes, 1, Number.MAX_SAFE_INTEGER)
	const percent = value?.percent == null
		? null : numberInRange(value.percent, 0, 100)
	if (!MEDIA_TYPES.has(mediaType) || !STATES.has(state)
		|| outputIndex == null || attempt == null || maxAttempts == null
		|| sequence == null || transferredBytes == null
		|| (value?.totalBytes != null && totalBytes == null)
		|| (totalBytes != null && transferredBytes > totalBytes)
		|| (value?.percent != null && percent == null)) return null
	return Object.freeze({
		mediaType,
		outputIndex: mediaType === 'VIDEO' ? 0 : outputIndex,
		attempt,
		maxAttempts,
		state,
		transferredBytes,
		totalBytes,
		percent,
		sequence,
		errorCode: state === 'FAILED' ? String(value?.errorCode || 'UPLOAD_FAILED') : ''
	})
}

/**
 * 视频进入 OSS 搬运阶段时创建真实的 0% 起点；后续 FC 字节事件会以相同键覆盖它。
 */
export function initialVideoUploadProgress() {
	return Object.freeze({
		mediaType: 'VIDEO',
		outputIndex: 0,
		attempt: 1,
		maxAttempts: 1,
		state: 'UPLOADING',
		transferredBytes: 0,
		totalBytes: null,
		percent: null,
		sequence: 1,
		errorCode: ''
	})
}

/**
 * 仅接受同一次上传中序号更大的事件；新尝试允许从 0% 重新开始，以如实表达图片实际重传。
 */
export function mergeMediaUploadProgress(progressByKey, value) {
	const next = normalizeMediaUploadProgress(value)
	const key = mediaUploadProgressKey(next)
	if (!next || !key) return progressByKey || {}
	const current = progressByKey?.[key]
	if (current && (next.attempt < current.attempt
		|| (next.attempt === current.attempt && next.sequence <= current.sequence))) {
		return progressByKey
	}
	return Object.freeze({ ...(progressByKey || {}), [key]: next })
}

/**
 * 在完成提示停留并淡出后移除单张媒体的临时进度；失败状态不会调用此函数。
 */
export function removeMediaUploadProgress(progressByKey, key) {
	if (!key || !progressByKey?.[key]) return progressByKey || {}
	const next = { ...progressByKey }
	delete next[key]
	return Object.freeze(next)
}
