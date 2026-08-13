export const MEDIA_PHASES = Object.freeze({
	IDLE: 'IDLE',
	OBSERVING: 'OBSERVING',
	LOADING: 'LOADING',
	WAITING_REMOTE: 'WAITING_REMOTE',
	METADATA_READY: 'METADATA_READY',
	READY: 'READY',
	PLAYING: 'PLAYING',
	WAITING: 'WAITING',
	ERROR: 'ERROR'
})

export const ANDROID_TEXT_PREVIEW_MAX_BYTES = 512 * 1024

const TEXT_EXTENSIONS = Object.freeze(new Set([
	'txt', 'md', 'markdown',
	'java', 'js', 'jsx', 'ts', 'tsx', 'py', 'c', 'h', 'cc', 'cpp', 'cxx',
	'cs', 'go', 'kt', 'kts', 'rs', 'sh', 'bash', 'zsh', 'sql', 'vue',
	'json', 'xml', 'yaml', 'yml', 'toml', 'ini', 'properties', 'gradle'
]))

function positiveFiniteNumber(value) {
	const number = Number(value)
	return Number.isFinite(number) && number > 0 ? number : null
}

function publicAttachmentKey(attachment) {
	for (const value of [
		attachment?.attachmentId,
		attachment?.attachmentPublicId,
		attachment?.publicId,
		attachment?.localId
	]) {
		const normalized = String(value || '').trim()
		if (normalized) return normalized
	}
	return ''
}

function requireHttpsSource(value) {
	const source = String(value || '').trim()
	if (!/^https:\/\/[^\s]+$/i.test(source)) {
		throw new TypeError('Media source must be a non-empty HTTPS URL.')
	}
	return source
}

function requireAppLocalSource(value) {
	const rawSource = String(value || '')
	const source = rawSource.trim()
	const absoluteAppPath = source.startsWith('/') && !source.startsWith('//')
	const appDocumentPath = source.startsWith('_doc/')
	const hasParentSegment = source.split('/').includes('..')
	const hasControlCharacter = /[\u0000-\u001f\u007f]/.test(rawSource)
	if ((!absoluteAppPath && !appDocumentPath)
		|| hasParentSegment
		|| hasControlCharacter) {
		throw new TypeError('App local media source is invalid.')
	}
	return source
}

function resolveMediaSource(attachment, options) {
	const localSrc = String(options?.localSrc || '')
	if (localSrc.trim()) {
		return Object.freeze({
			src: requireAppLocalSource(localSrc),
			kind: 'APP_LOCAL'
		})
	}
	return Object.freeze({
		src: requireHttpsSource(attachment.url || attachment.src),
		kind: 'REMOTE_HTTPS'
	})
}

function normalizedDurationMillis(value) {
	const duration = Number(value)
	return Number.isFinite(duration) && duration >= 0 ? duration : null
}

export function createMediaDescriptor(attachment, metadata = null, options = {}) {
	if (!attachment || typeof attachment !== 'object' || Array.isArray(attachment)) {
		throw new TypeError('Attachment must be an object.')
	}
	const key = publicAttachmentKey(attachment)
	if (!key) throw new TypeError('Attachment public key is required.')
	const source = resolveMediaSource(attachment, options)

	return Object.freeze({
		key,
		src: source.src,
		sourceKind: source.kind,
		fileName: String(attachment.fileName || '').trim(),
		contentType: String(attachment.contentType || '').trim().toLowerCase(),
		width: positiveFiniteNumber(metadata?.width) ?? positiveFiniteNumber(attachment.width),
		height: positiveFiniteNumber(metadata?.height) ?? positiveFiniteNumber(attachment.height),
		durationMillis: normalizedDurationMillis(
			metadata?.durationMillis ?? attachment.durationMillis)
	})
}

export function resolveMediaAspectRatio(descriptor, fallbackRatio = 16 / 9) {
	const width = positiveFiniteNumber(descriptor?.width)
	const height = positiveFiniteNumber(descriptor?.height)
	if (width != null && height != null) return width / height
	return positiveFiniteNumber(fallbackRatio) ?? 16 / 9
}

export function classifyHtmlMediaError(errorCode) {
	return ({
		1: 'ABORTED',
		2: 'NETWORK',
		3: 'DECODE',
		4: 'UNSUPPORTED'
	})[Number(errorCode)] || 'UNKNOWN'
}

function fileExtension(fileName) {
	const normalized = String(fileName || '').trim().toLowerCase()
	const match = normalized.match(/\.([a-z0-9]+)$/)
	return match ? match[1] : ''
}

export function isAndroidPreviewableTextFile(attachment) {
	const sizeBytes = Number(attachment?.sizeBytes)
	if (!Number.isSafeInteger(sizeBytes)
		|| sizeBytes < 0
		|| sizeBytes > ANDROID_TEXT_PREVIEW_MAX_BYTES) return false

	const extension = fileExtension(attachment?.fileName)
	if (TEXT_EXTENSIONS.has(extension)) return true
	const contentType = String(attachment?.contentType || '').trim().toLowerCase()
	return contentType.startsWith('text/plain') || contentType.startsWith('text/markdown')
}
