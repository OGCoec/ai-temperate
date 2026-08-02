// #ifdef APP-PLUS
import { chooseDocuments } from '@/uni_modules/ait-document-picker'
// #endif

function h5Files(count) {
	return new Promise((resolve, reject) => {
		if (typeof uni.chooseFile !== 'function') {
			reject(Object.assign(
				new Error('当前客户端不支持系统文件选择器。'),
				{ code: 'AI_ATTACHMENT_PICKER_UNAVAILABLE' }
			))
			return
		}
		uni.chooseFile({
			count,
			success(result) {
				resolve((result.tempFiles || []).map(file => ({
					fileName: file.name || 'attachment.bin',
					contentType: file.type || 'application/octet-stream',
					sizeBytes: Number(file.size || 0),
					path: file.path || file.tempFilePath,
					raw: file.file || file
				})))
			},
			fail(failure) {
				const message = String(failure?.errMsg || '')
				if (message.toLowerCase().includes('cancel')) resolve([])
				else reject(Object.assign(
					new Error('无法读取所选附件。'),
					{ code: 'AI_ATTACHMENT_PICK_FAILED' }
				))
			}
		})
	})
}

function androidFiles(count) {
	// #ifdef APP-PLUS
	return new Promise((resolve, reject) => {
		chooseDocuments({
			count,
			onSuccess(files) {
				resolve(Array.from(files || []).map(file => ({
					fileName: file.fileName,
					contentType: file.contentType,
					sizeBytes: Number(file.sizeBytes),
					path: file.path,
					raw: null
				})))
			},
			onCancel() { resolve([]) },
			onError(failure) {
				reject(Object.assign(
					new Error(failure?.message || '无法读取所选附件。'),
					{ code: failure?.code || 'AI_ATTACHMENT_PICK_FAILED' }
				))
			}
		})
	})
	// #endif
	// #ifndef APP-PLUS
	return h5Files(count)
	// #endif
}

/**
 * H5 使用浏览器文件输入，Android 使用 ACTION_OPEN_DOCUMENT；两端都返回同一临时附件结构。
 */
export function chooseConversationFiles(count) {
	const bounded = Math.max(1, Math.min(8, Number(count) || 1))
	// #ifdef APP-PLUS
	return androidFiles(bounded)
	// #endif
	// #ifndef APP-PLUS
	return h5Files(bounded)
	// #endif
}
