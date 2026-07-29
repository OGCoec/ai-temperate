const EXPORT_DIRECTORY_NAME = 'mail-inspection-exports'
const EXPORT_MIME_TYPE = 'text/plain;charset=utf-8'

function safeExportError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}

function dateStamp(value) {
	const date = value instanceof Date ? value : new Date(value)
	if (Number.isNaN(date.getTime())) {
		throw safeExportError('MAIL_INSPECTION_EXPORT_DATE_INVALID', '凭证导出时间无效。')
	}
	const number = input => String(input).padStart(2, '0')
	return [
		date.getUTCFullYear(),
		number(date.getUTCMonth() + 1),
		number(date.getUTCDate()),
		'-',
		number(date.getUTCHours()),
		number(date.getUTCMinutes()),
		number(date.getUTCSeconds())
	].join('')
}

function safeInspectionType(value) {
	const normalized = String(value || '').toLowerCase().replace(/_/g, '-')
	return /^[a-z0-9-]+$/.test(normalized) ? normalized : 'unknown'
}

function safeJobId(value) {
	const normalized = String(value || '')
	return /^[A-Za-z0-9_-]{22}$/.test(normalized) ? normalized : 'no-job'
}

function exportPlatform(options = {}) {
	if (options.platform) return String(options.platform).toUpperCase()
	if (typeof plus !== 'undefined' && plus?.os?.name === 'Android') return 'ANDROID'
	return 'H5'
}

function requireExportFile(exportFile) {
	if (!exportFile
		|| typeof exportFile.filename !== 'string'
		|| typeof exportFile.content !== 'string'
		|| !exportFile.filename.endsWith('.txt')) {
		throw safeExportError('MAIL_INSPECTION_EXPORT_INVALID', '凭证导出内容无效。')
	}
	return exportFile
}

export function createMailInspectionCredentialExport(options = {}) {
	const credentialLines = options.credentialLines
	if (!Array.isArray(credentialLines)
		|| !credentialLines.length
		|| credentialLines.some(line => typeof line !== 'string' || !line.length)) {
		throw safeExportError(
			'MAIL_INSPECTION_EXPORT_CREDENTIALS_MISSING',
			'当前会话没有可导出的未注册凭证。')
	}
	const timestamp = dateStamp(options.now || new Date())
	return Object.freeze({
		filename: [
			'mail-inspection',
			safeInspectionType(options.inspectionType),
			'unregistered',
			safeJobId(options.jobId),
			timestamp
		].join('-') + '.txt',
		content: credentialLines.join('\n'),
		mimeType: EXPORT_MIME_TYPE
	})
}

function downloadInBrowser(exportFile, options) {
	const BlobCtor = options.BlobCtor || Blob
	const urlApi = options.urlApi || URL
	const documentRef = options.documentRef || document
	const objectUrl = urlApi.createObjectURL(new BlobCtor(
		[exportFile.content],
		{ type: exportFile.mimeType || EXPORT_MIME_TYPE }))
	const anchor = documentRef.createElement('a')
	try {
		anchor.href = objectUrl
		anchor.download = exportFile.filename
		anchor.style.display = 'none'
		documentRef.body.appendChild(anchor)
		anchor.click()
	} finally {
		anchor.remove()
		// 下载动作已经交给浏览器后立即撤销临时 URL，避免敏感 Blob 长时间驻留。
		urlApi.revokeObjectURL(objectUrl)
	}
	return {
		platform: 'H5',
		filename: exportFile.filename,
		path: ''
	}
}

function privateFileSystem(plusRef) {
	return new Promise((resolve, reject) => {
		plusRef.io.requestFileSystem(plusRef.io.PRIVATE_DOC, resolve, reject)
	})
}

function privateExportDirectory(plusRef, create) {
	return privateFileSystem(plusRef).then(fileSystem => new Promise((resolve, reject) => {
		fileSystem.root.getDirectory(EXPORT_DIRECTORY_NAME, { create }, resolve, reject)
	}))
}

function writeAndroidPrivateFile(plusRef, exportFile) {
	return privateExportDirectory(plusRef, true)
		.then(directory => new Promise((resolve, reject) => {
			directory.getFile(
				exportFile.filename,
				{ create: true, exclusive: false },
				resolve,
				reject)
		}))
		.then(fileEntry => new Promise((resolve, reject) => {
			fileEntry.createWriter(writer => {
				writer.onwrite = () => resolve({
					platform: 'ANDROID',
					filename: exportFile.filename,
					path: fileEntry.toLocalURL()
				})
				writer.onerror = () => reject(safeExportError(
					'MAIL_INSPECTION_EXPORT_WRITE_FAILED',
					'未注册凭证写入应用私有目录失败。'))
				writer.write(exportFile.content)
			}, reject)
		}))
}

export async function exportMailInspectionCredentialFile(exportFile, options = {}) {
	const safeFile = requireExportFile(exportFile)
	if (exportPlatform(options) !== 'ANDROID') {
		try {
			return downloadInBrowser(safeFile, options)
		} catch (_) {
			throw safeExportError(
				'MAIL_INSPECTION_EXPORT_DOWNLOAD_FAILED',
				'浏览器未能下载未注册凭证文件。')
		}
	}

	const plusRef = options.plusRef || (typeof plus !== 'undefined' ? plus : null)
	if (!plusRef?.io) {
		throw safeExportError(
			'MAIL_INSPECTION_EXPORT_ANDROID_UNAVAILABLE',
			'Android 私有文件目录当前不可用。')
	}
	try {
		return await writeAndroidPrivateFile(plusRef, safeFile)
	} catch (error) {
		if (error?.code?.startsWith('MAIL_INSPECTION_EXPORT_')) throw error
		throw safeExportError(
			'MAIL_INSPECTION_EXPORT_WRITE_FAILED',
			'未注册凭证写入应用私有目录失败。')
	}
}

export async function openMailInspectionCredentialExport(path, options = {}) {
	const plusRef = options.plusRef || (typeof plus !== 'undefined' ? plus : null)
	if (!plusRef?.runtime?.openFile || !String(path || '').startsWith('_doc/')) {
		throw safeExportError(
			'MAIL_INSPECTION_EXPORT_OPEN_UNAVAILABLE',
			'Android 私有凭证文件当前无法打开。')
	}
	return new Promise((resolve, reject) => {
		plusRef.runtime.openFile(
			String(path),
			{},
			() => resolve(true),
			() => reject(safeExportError(
				'MAIL_INSPECTION_EXPORT_OPEN_FAILED',
				'Android 私有凭证文件打开失败。')))
	})
}

export async function deleteMailInspectionCredentialExport(path, options = {}) {
	const plusRef = options.plusRef || (typeof plus !== 'undefined' ? plus : null)
	if (!plusRef?.io?.resolveLocalFileSystemURL || !String(path || '').startsWith('_doc/')) {
		throw safeExportError(
			'MAIL_INSPECTION_EXPORT_DELETE_UNAVAILABLE',
			'Android 私有凭证文件当前无法删除。')
	}
	return new Promise((resolve, reject) => {
		plusRef.io.resolveLocalFileSystemURL(
			String(path),
			entry => entry.remove(
				() => resolve(true),
				() => reject(safeExportError(
					'MAIL_INSPECTION_EXPORT_DELETE_FAILED',
					'Android 私有凭证文件删除失败。'))),
			() => reject(safeExportError(
				'MAIL_INSPECTION_EXPORT_DELETE_FAILED',
				'Android 私有凭证文件删除失败。')))
	})
}

export async function clearMailInspectionCredentialExports(options = {}) {
	if (exportPlatform(options) !== 'ANDROID') return false
	const plusRef = options.plusRef || (typeof plus !== 'undefined' ? plus : null)
	if (!plusRef?.io) return false
	try {
		const directory = await privateExportDirectory(plusRef, false)
		await new Promise((resolve, reject) => directory.removeRecursively(resolve, reject))
		return true
	} catch (_) {
		// 目录不存在和系统已清理都视为完成；会话清理不能因文件 API 失败而中断。
		return false
	}
}
