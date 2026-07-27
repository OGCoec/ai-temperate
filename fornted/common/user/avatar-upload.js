const MAX_AVATAR_BYTES = 5 * 1024 * 1024

function normalizeExtension(name) {
	const value = String(name || '').trim().toLowerCase()
	const dot = value.lastIndexOf('.')
	return dot < 0 ? '' : value.slice(dot + 1)
}

function avatarFormat(file) {
	const type = String(file?.type || '').toLowerCase()
	const extension = normalizeExtension(file?.name || file?.path || file?.tempFilePath)
	if (type === 'image/jpeg' || extension === 'jpg' || extension === 'jpeg') return 'JPEG'
	if (type === 'image/png' || extension === 'png') return 'PNG'
	if (type === 'image/webp' || extension === 'webp') return 'WEBP'
	throw new Error('请选择 JPEG、PNG 或 WebP 图片。')
}

function base64ToArrayBuffer(base64) {
	if (typeof uni.base64ToArrayBuffer === 'function') return uni.base64ToArrayBuffer(base64)
	const binary = atob(base64)
	const bytes = new Uint8Array(binary.length)
	for (let index = 0; index < binary.length; index += 1) {
		bytes[index] = binary.charCodeAt(index)
	}
	return bytes.buffer
}

function appFileToArrayBuffer(filePath) {
	return new Promise((resolve, reject) => {
		if (typeof plus === 'undefined' || !plus.io) {
			reject(new Error('当前客户端不支持读取所选图片。'))
			return
		}
		plus.io.resolveLocalFileSystemURL(filePath, entry => {
			entry.file(file => {
				const reader = new plus.io.FileReader()
				reader.onloadend = event => {
					const dataUrl = String(event?.target?.result || '')
					const comma = dataUrl.indexOf(',')
					if (comma < 0) {
						reject(new Error('无法读取所选图片。'))
						return
					}
					resolve(base64ToArrayBuffer(dataUrl.slice(comma + 1)))
				}
				reader.onerror = () => reject(new Error('无法读取所选图片。'))
				reader.readAsDataURL(file)
			}, reject)
		}, reject)
	})
}

export async function readAvatarSelection(selection) {
	const file = selection?.tempFiles?.[0] || null
	const previewUrl = selection?.tempFilePaths?.[0] || file?.path || file?.tempFilePath
	if (!previewUrl) throw new Error('没有读取到所选图片。')
	const format = avatarFormat(file || { path: previewUrl })
	const sizeBytes = Number(file?.size || 0)
	if (!Number.isSafeInteger(sizeBytes) || sizeBytes <= 0 || sizeBytes > MAX_AVATAR_BYTES) {
		throw new Error('头像必须大于 0 且不超过 5 MB。')
	}
	let bytes
	if (file && typeof file.arrayBuffer === 'function') {
		bytes = await file.arrayBuffer()
	} else if (typeof uni.getFileSystemManager === 'function') {
		bytes = await new Promise((resolve, reject) => {
			uni.getFileSystemManager().readFile({
				filePath: previewUrl,
				success: result => resolve(result.data),
				fail: reject
			})
		})
	} else {
		bytes = await appFileToArrayBuffer(previewUrl)
	}
	if (!(bytes instanceof ArrayBuffer)) {
		throw new Error('所选图片未能读取为 ArrayBuffer。')
	}
	return { bytes, format, previewUrl, sizeBytes }
}

export function chooseAvatarImage() {
	return new Promise((resolve, reject) => {
		uni.chooseImage({
			count: 1,
			sourceType: ['album', 'camera'],
			success: resolve,
			fail: error => {
				if (String(error?.errMsg || '').includes('cancel')) resolve(null)
				else reject(error)
			}
		})
	})
}

export function putAvatarToOss(uploadUrl, uploadHeaders, bytes) {
	return new Promise((resolve, reject) => {
		uni.request({
			url: uploadUrl,
			method: 'PUT',
			header: { ...(uploadHeaders || {}) },
			data: bytes,
			success: response => {
				if (response.statusCode >= 200 && response.statusCode < 300) {
					resolve()
					return
				}
				reject(new Error(`OSS 上传失败（${response.statusCode}）。`))
			},
			fail: () => reject(new Error('OSS 上传失败，请稍后重试。'))
		})
	})
}

export { MAX_AVATAR_BYTES }
