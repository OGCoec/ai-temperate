const MAX_FILE_BYTES = 256 * 1024

function fileError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}

export function validateIp2LocationKeyFile(file) {
	const name = String(file?.name || '')
	const size = Number(file?.size)
	if (!name.toLowerCase().endsWith('.txt')) {
		throw fileError('FILE_TYPE_INVALID', '请选择 UTF-8 编码的 .txt 文件。')
	}
	if (!Number.isFinite(size) || size < 0 || size > MAX_FILE_BYTES) {
		throw fileError('FILE_TOO_LARGE', 'TXT 文件不能超过 256 KB。')
	}
}

export function isIp2LocationKeyFilePickerAvailable(runtime = typeof uni === 'undefined' ? null : uni) {
	return Boolean(runtime && typeof runtime.chooseFile === 'function')
}

function chooseFile(runtime) {
	return new Promise((resolve, reject) => {
		runtime.chooseFile({
			count: 1,
			type: 'all',
			extension: ['.txt'],
			success: resolve,
			fail(cause) {
				const error = fileError('FILE_PICKER_CANCELLED', '没有选择文件。')
				error.cause = cause
				reject(error)
			}
		})
	})
}

function readWithFileReader(file) {
	return new Promise((resolve, reject) => {
		if (typeof FileReader === 'undefined') {
			reject(fileError('FILE_READ_UNAVAILABLE', '当前运行环境无法读取所选文件。'))
			return
		}
		const reader = new FileReader()
		reader.onload = () => resolve(String(reader.result || ''))
		reader.onerror = () => reject(fileError('FILE_READ_FAILED', 'TXT 文件读取失败。'))
		reader.readAsText(file, 'UTF-8')
	})
}

function readWithFileSystem(runtime, filePath) {
	return new Promise((resolve, reject) => {
		const manager = typeof runtime.getFileSystemManager === 'function'
			? runtime.getFileSystemManager()
			: null
		if (!manager || typeof manager.readFile !== 'function') {
			reject(fileError('FILE_READ_UNAVAILABLE', 'Android 当前版本请使用多行粘贴。'))
			return
		}
		manager.readFile({
			filePath,
			encoding: 'utf8',
			success: result => resolve(String(result?.data || '')),
			fail: () => reject(fileError('FILE_READ_FAILED', 'TXT 文件读取失败。'))
		})
	})
}

async function readText(runtime, file) {
	if (typeof file?.text === 'function') return String(await file.text())
	if (typeof Blob !== 'undefined' && file instanceof Blob) return readWithFileReader(file)
	const filePath = file?.path || file?.tempFilePath
	if (filePath) return readWithFileSystem(runtime, filePath)
	throw fileError('FILE_READ_UNAVAILABLE', '当前运行环境无法读取所选文件。')
}

export async function chooseIp2LocationKeyTextFile(runtime = typeof uni === 'undefined' ? null : uni) {
	if (!isIp2LocationKeyFilePickerAvailable(runtime)) {
		throw fileError('FILE_PICKER_UNAVAILABLE', 'Android 当前版本请使用多行粘贴。')
	}
	const selection = await chooseFile(runtime)
	const file = selection?.tempFiles?.[0]
	if (!file) throw fileError('FILE_PICKER_CANCELLED', '没有选择文件。')
	const filePath = String(file.path || file.tempFilePath || '')
	const name = String(file.name || filePath.split(/[\\/]/u).pop() || '')
	validateIp2LocationKeyFile({ name, size: file.size })
	const text = await readText(runtime, file)
	return { name, size: Number(file.size), text }
}
