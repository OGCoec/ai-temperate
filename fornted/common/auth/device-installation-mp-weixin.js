const RANDOM_BYTE_LENGTH = 16

function installationIdError(code, message, cause = null) {
	const error = new Error(message)
	error.code = code
	if (cause) error.cause = cause
	return error
}

function uuidV4(randomValues) {
	let bytes
	try {
		bytes = new Uint8Array(randomValues)
	} catch (cause) {
		throw installationIdError(
			'DEVICE_INSTALLATION_ID_INVALID',
			'微信安全随机数返回了无效数据。',
			cause)
	}
	if (bytes.byteLength !== RANDOM_BYTE_LENGTH) {
		throw installationIdError(
			'DEVICE_INSTALLATION_ID_INVALID',
			'微信安全随机数长度无效。')
	}

	// UUIDv4 的版本位与 RFC 4122 variant 必须在随机字节生成后显式固定。
	bytes = new Uint8Array(bytes)
	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	const value = Array.from(
		bytes,
		byte => byte.toString(16).padStart(2, '0')
	).join('')
	return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`
}

/**
 * 使用微信运行时提供的密码学安全随机数生成安装级 UUID；失败时禁止降级为弱随机数。
 */
export function createMpWeixinInstallationId(wxApi) {
	return new Promise((resolve, reject) => {
		if (typeof wxApi?.getRandomValues !== 'function') {
			reject(installationIdError(
				'DEVICE_INSTALLATION_ID_UNAVAILABLE',
				'当前微信运行环境不支持安全安装标识。'))
			return
		}

		try {
			wxApi.getRandomValues({
				length: RANDOM_BYTE_LENGTH,
				success(result) {
					try {
						resolve(uuidV4(result?.randomValues))
					} catch (error) {
						reject(error)
					}
				},
				fail(cause) {
					reject(installationIdError(
						'DEVICE_INSTALLATION_ID_UNAVAILABLE',
						'微信安全安装标识生成失败。',
						cause))
				}
			})
		} catch (cause) {
			reject(installationIdError(
				'DEVICE_INSTALLATION_ID_UNAVAILABLE',
				'微信安全安装标识生成失败。',
				cause))
		}
	})
}
