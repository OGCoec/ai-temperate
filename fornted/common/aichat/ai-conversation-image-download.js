const HTTPS_SOURCE = /^https:\/\/[^\s]+$/i
const BLOB_SOURCE = /^blob:[^\s]+$/i
const DATA_IMAGE_SOURCE = /^data:image\/(?:png|jpe?g|webp);base64,[a-z0-9+/=\s]+$/i

function safeSource(value) {
	const raw = String(value || '')
	if (!raw || /[\u0000-\u001f\u007f]/.test(raw)) return ''
	return raw.trim()
}

function extensionForContentType(contentType) {
	return ({
		'image/jpeg': 'jpg',
		'image/png': 'png',
		'image/webp': 'webp'
	})[String(contentType || '').trim().toLowerCase()] || 'png'
}

export function generatedImageFileName(item) {
	const original = String(item?.attachment?.fileName || '').trim()
	if (original && original.length <= 128
		&& !/[\\/\u0000-\u001f\u007f]/.test(original)
		&& /\.(png|jpe?g|webp)$/i.test(original)) return original
	const outputIndex = Number(item?.outputIndex)
	const ordinal = Number.isSafeInteger(outputIndex) && outputIndex >= 0
		? outputIndex + 1 : 1
	return `generated-${ordinal}.${extensionForContentType(
		item?.attachment?.contentType)}`
}

export function h5GeneratedImageSource(item) {
	const source = safeSource(item?.displaySrc || item?.attachment?.url)
	if (HTTPS_SOURCE.test(source)) return Object.freeze({ source, kind: 'HTTPS' })
	if (BLOB_SOURCE.test(source)) return Object.freeze({ source, kind: 'BLOB' })
	if (DATA_IMAGE_SOURCE.test(source)) {
		return Object.freeze({ source, kind: 'DATA_IMAGE' })
	}
	throw new TypeError('Generated image source is not downloadable.')
}

export function androidGeneratedImageSavePath(value) {
	const filePath = safeSource(value)
	const controlledShape = filePath.startsWith('/') || filePath.startsWith('_doc/')
	const parentSegment = filePath.split('/').includes('..')
	if (!controlledShape || parentSegment) {
		throw new TypeError('Android generated image path is not controlled.')
	}
	return filePath
}

export async function downloadGeneratedImageOnH5(item, environment = {}) {
	const { source, kind } = h5GeneratedImageSource(item)
	const fetchImpl = environment.fetchImpl || globalThis.fetch
	const documentRef = environment.documentRef || globalThis.document
	const urlApi = environment.urlApi || globalThis.URL
	if (typeof fetchImpl !== 'function' || !documentRef || !urlApi) {
		throw new Error('IMAGE_DOWNLOAD_ENVIRONMENT_UNAVAILABLE')
	}
	let objectUrl = ''
	try {
		if (kind === 'BLOB') {
			objectUrl = source
		} else {
			const response = await fetchImpl(source, { credentials: 'omit' })
			if (!response?.ok) throw new Error('IMAGE_DOWNLOAD_HTTP_FAILED')
			const blob = await response.blob()
			if (!blob?.size) throw new Error('IMAGE_DOWNLOAD_EMPTY')
			objectUrl = urlApi.createObjectURL(blob)
		}
		const link = documentRef.createElement('a')
		link.href = objectUrl
		link.download = generatedImageFileName(item)
		link.rel = 'noopener'
		link.style.display = 'none'
		documentRef.body.appendChild(link)
		link.click()
		link.remove()
	} finally {
		if (objectUrl && kind !== 'BLOB') {
			setTimeout(() => urlApi.revokeObjectURL(objectUrl), 1000)
		}
	}
}
