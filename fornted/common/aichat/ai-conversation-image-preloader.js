function requiredUrl(value) {
	const normalized = String(value || '').trim()
	if (!normalized) throw new Error('Image preload URL is required')
	return normalized
}

function preloadH5(url, imageFactory) {
	return new Promise((resolve, reject) => {
		let image
		try {
			image = imageFactory()
		} catch (error) {
			reject(error)
			return
		}
		if (!image) {
			reject(new Error('Browser Image is unavailable'))
			return
		}
		image.decoding = 'async'
		image.onload = () => resolve({
			displayUrl: url,
			width: Number(image.naturalWidth || image.width || 0),
			height: Number(image.naturalHeight || image.height || 0)
		})
		image.onerror = () => reject(new Error('Image preload failed'))
		image.src = url
	})
}

function preloadAndroid(url, getImageInfo) {
	return new Promise((resolve, reject) => {
		try {
			getImageInfo({
				src: url,
				success: result => resolve({
					displayUrl: String(result?.path || result?.tempFilePath || url),
					width: Number(result?.width || 0),
					height: Number(result?.height || 0)
				}),
				fail: error => reject(error instanceof Error
					? error
					: new Error('Image preload failed'))
			})
		} catch (error) {
			reject(error)
		}
	})
}

export function preloadConversationImage(value, options = {}) {
	const url = requiredUrl(value)
	const platform = String(options.platform || '').trim().toUpperCase()
	if (platform === 'ANDROID') {
		const getImageInfo = options.getImageInfo
			|| (typeof globalThis.uni?.getImageInfo === 'function'
				? payload => globalThis.uni.getImageInfo(payload)
				: null)
		if (typeof getImageInfo !== 'function') {
			return Promise.reject(new Error('Android image preloader is unavailable'))
		}
		return preloadAndroid(url, getImageInfo)
	}
	const imageFactory = options.imageFactory || (() => new globalThis.Image())
	return preloadH5(url, imageFactory)
}
