export const ANDROID_GENERATED_IMAGE_SOURCE_STATUS = Object.freeze({
	PREPARING_PREVIEW: 'PREPARING_PREVIEW',
	PREVIEW_READY: 'PREVIEW_READY',
	WAITING_REMOTE: 'WAITING_REMOTE',
	DOWNLOADING_FINAL: 'DOWNLOADING_FINAL',
	FINAL_READY: 'FINAL_READY',
	ERROR: 'ERROR'
})

const PREVIEW_MAXIMUM_BYTES = 384000
const FINAL_MAXIMUM_BYTES = 100 * 1024 * 1024
const MAXIMUM_IMAGE_OUTPUTS = 10
const HTTPS_PATTERN = /^https:\/\/[^\s]+$/i
const DATA_IMAGE_PATTERN = /^data:image\/(?:png|jpe?g|webp);base64,([\s\S]+)$/i
const GENERATED_IMAGE_FILE_PATTERN = /^generated-(10|[1-9])\.[^./\\]+$/i
const SUPPORTED_IMAGE_CONTENT_TYPES = new Set([
	'image/jpeg',
	'image/png',
	'image/webp'
])
let diagnosticSequence = 0

function normalizedOwnerKey(value) {
	return String(value || '').trim()
}

function normalizedOutputIndex(value) {
	if (value == null || value === '') return null
	const outputIndex = Number(value)
	return Number.isInteger(outputIndex)
		&& outputIndex >= 0
		&& outputIndex < MAXIMUM_IMAGE_OUTPUTS
		? outputIndex : null
}

function slotKey(ownerKey, outputIndex) {
	return `${ownerKey}:${outputIndex}`
}

function generatedImageFileOutputIndex(attachment) {
	const match = GENERATED_IMAGE_FILE_PATTERN.exec(String(attachment?.fileName || '').trim())
	return match ? Number(match[1]) - 1 : null
}

function supportedGeneratedImage(attachment) {
	return attachment && typeof attachment === 'object'
		&& SUPPORTED_IMAGE_CONTENT_TYPES.has(
			String(attachment.contentType || '').trim().toLowerCase())
}

export function androidGeneratedImageOwnerKey(message) {
	const localId = normalizedOwnerKey(message?.localId)
	if (localId) return `local:${localId}`
	const messagePublicId = normalizedOwnerKey(message?.messagePublicId)
	return messagePublicId ? `history:${messagePublicId}` : ''
}

export function normalizeAndroidGeneratedImageAttachments(attachments) {
	const candidates = (Array.isArray(attachments) ? attachments : [])
		.filter(supportedGeneratedImage)
		.slice(0, MAXIMUM_IMAGE_OUTPUTS)
	const assignments = Array(candidates.length).fill(null)
	const usedOutputIndexes = new Set()

	// 鏄惧紡妲戒綅鏄疄鏃朵簨浠剁殑鏉冨▉韬唤锛屽繀椤诲厛浜庢枃浠跺悕鎺ㄥ鍊煎畬鎴愬崰浣嶃€?
	candidates.forEach((attachment, index) => {
		const outputIndex = normalizedOutputIndex(attachment?.outputIndex)
		if (outputIndex == null || outputIndex >= MAXIMUM_IMAGE_OUTPUTS
			|| usedOutputIndexes.has(outputIndex)) return
		assignments[index] = outputIndex
		usedOutputIndexes.add(outputIndex)
	})

	candidates.forEach((attachment, index) => {
		if (assignments[index] != null) return
		const outputIndex = generatedImageFileOutputIndex(attachment)
		if (outputIndex == null || usedOutputIndexes.has(outputIndex)) return
		assignments[index] = outputIndex
		usedOutputIndexes.add(outputIndex)
	})

	candidates.forEach((_, index) => {
		if (assignments[index] != null) return
		for (let outputIndex = 0; outputIndex < MAXIMUM_IMAGE_OUTPUTS; outputIndex++) {
			if (usedOutputIndexes.has(outputIndex)) continue
			assignments[index] = outputIndex
			usedOutputIndexes.add(outputIndex)
			break
		}
	})

	return Object.freeze(candidates.map((attachment, index) => Object.freeze({
		...attachment,
		outputIndex: assignments[index],
		imageSlot: true
	})))
}

function remoteUrl(attachment) {
	const persistedUrl = String(attachment?.persistedUrl || '').trim()
	if (HTTPS_PATTERN.test(persistedUrl)) return persistedUrl
	const url = String(attachment?.url || '').trim()
	return HTTPS_PATTERN.test(url) ? url : ''
}

function previewPayload(attachment) {
	const match = DATA_IMAGE_PATTERN.exec(String(attachment?.url || '').trim())
	return match?.[1] || ''
}

function previewIdentity(attachment) {
	const url = String(attachment?.url || '')
	return [
		String(attachment?.imageId || attachment?.attachmentId || ''),
		String(attachment?.phase || ''),
		String(attachment?.partialImageIndex ?? ''),
		url.length,
		url.slice(0, 36),
		url.slice(-36)
	].join('|')
}

function controlledLocalFilePath(value) {
	const source = String(value || '').trim()
	if (!source || source.includes('..') || /[\u0000-\u001f\u007f]/.test(source)) return ''
	return source.startsWith('/') || source.startsWith('_doc/') ? source : ''
}

function controlledManagedImage(image) {
	const filePath = controlledLocalFilePath(image?.filePath)
	const displayUri = String(image?.displayUri || '').trim()
	if (!filePath.startsWith('/') || displayUri !== `file://${filePath}`
		|| !displayUri.startsWith('file:///') || displayUri.includes('..')
		|| /[\u0000-\u001f\u007f]/.test(displayUri)) return null
	return Object.freeze({ filePath, displayUri })
}

function sourceKind(value) {
	const source = String(value || '').trim()
	if (source.startsWith('file:///')) return 'FILE_URI'
	if (source.startsWith('_doc/')) return 'DOC_PATH'
	if (source.startsWith('/')) return 'RAW_ABSOLUTE'
	return 'NONE'
}

function nextDiagnosticRunId(outputIndex) {
	diagnosticSequence += 1
	return `img-${Date.now().toString(36)}-${diagnosticSequence}-${outputIndex}`
}

function safeFailureDetails(failure) {
	return {
		failureCode: String(failure?.code || 'UNKNOWN').slice(0, 96),
		failureStage: String(failure?.stage || 'UNKNOWN').slice(0, 64),
		exceptionType: String(failure?.exceptionType || 'UNKNOWN').slice(0, 96),
		statusCode: Number.isFinite(Number(failure?.statusCode))
			? Number(failure.statusCode) : 0
	}
}

function createSlot(ownerKey, outputIndex) {
	return {
		ownerKey,
		outputIndex,
		status: ANDROID_GENERATED_IMAGE_SOURCE_STATUS.WAITING_REMOTE,
		displaySrc: '',
		previewPath: '',
		finalPath: '',
		persistedUrl: '',
		downloadedUrl: '',
		previewIdentity: '',
		previewKind: 'FULL',
		requiresUpgrade: false,
		revision: 0,
		diagnosticRunId: nextDiagnosticRunId(outputIndex),
		downloadAttempt: 0,
		operation: null,
		operationKind: '',
		upgradeFailed: false
	}
}

/**
 * 管理 Android 生成图片的页面内存源。共享附件保持 Base64/HTTPS，所有本地路径和异步竞态只在本控制器内收敛。
 */
export function createAndroidGeneratedImageSourceController(options = {}) {
	const materializeBase64Image = options.materializeBase64Image
	const fetchHttpsImage = options.fetchHttpsImage
	const removeManagedImage = options.removeManagedImage
	const onChange = typeof options.onChange === 'function' ? options.onChange : () => {}
	const diagnosticsEnabled = options.diagnosticsEnabled === true
	const onDiagnostic = typeof options.onDiagnostic === 'function'
		? options.onDiagnostic
		: (diagnostic, warning) => {
			const line = Object.entries(diagnostic)
				.map(([key, value]) => {
					const safeValue = typeof value === 'number' || typeof value === 'boolean'
						? value
						: String(value).replace(/[\u0000-\u0020\u007f]/g, '_').slice(0, 128)
					return `${key}=${String(safeValue)}`
				})
				.join(' ')
			if (warning) console.warn(`[ait-android-image] ${line}`)
			else console.log(`[ait-android-image] ${line}`)
		}
	if (typeof materializeBase64Image !== 'function'
		|| typeof fetchHttpsImage !== 'function'
		|| typeof removeManagedImage !== 'function') {
		throw new TypeError('Android image cache operations are required.')
	}

	const slots = new Map()

	function emitDiagnostic(slot, phase, fields = {}, warning = false) {
		if (!diagnosticsEnabled || !slot) return
		const diagnostic = Object.freeze({
			event: 'image_android_source',
			phase,
			diagnosticRunId: slot.diagnosticRunId,
			outputIndex: slot.outputIndex,
			ownerKind: slot.ownerKey.startsWith('history:') ? 'HISTORY' : 'LOCAL',
			revision: slot.revision,
			attempt: slot.downloadAttempt,
			status: slot.status,
			hasPreview: Boolean(slot.previewPath),
			hasFinal: Boolean(slot.finalPath),
			requiresUpgrade: slot.requiresUpgrade === true,
			pathKind: sourceKind(slot.displaySrc),
			...fields
		})
		try { onDiagnostic(diagnostic, warning) } catch (_) {}
	}

	function requiredSlot(ownerKeyValue, outputIndexValue) {
		const ownerKey = normalizedOwnerKey(ownerKeyValue)
		const outputIndex = normalizedOutputIndex(outputIndexValue)
		if (!ownerKey || outputIndex == null) return null
		const key = slotKey(ownerKey, outputIndex)
		let slot = slots.get(key)
		if (!slot) {
			slot = createSlot(ownerKey, outputIndex)
			slots.set(key, slot)
			emitDiagnostic(slot, 'SLOT_CREATED')
		}
		return slot
	}

	function notify(slot) {
		onChange(Object.freeze({
			ownerKey: slot.ownerKey,
			outputIndex: slot.outputIndex,
			status: slot.status,
			revision: slot.revision,
			diagnosticRunId: slot.diagnosticRunId
		}))
	}

	function safeRemove(filePath) {
		const value = String(filePath || '').trim()
		if (!value) return
		try { removeManagedImage(value) } catch (_) {}
	}

	function closeOperation(slot, reason = 'REPLACED') {
		if (slot.operation) emitDiagnostic(slot, 'OPERATION_CANCELLED', { reason })
		try { slot.operation?.close?.() } catch (_) {}
		slot.operation = null
		slot.operationKind = ''
	}

	function startFinalDownload(slot, force = false) {
		const url = slot.persistedUrl
		if (!HTTPS_PATTERN.test(url)) {
			const statusBefore = slot.status
			slot.status = slot.displaySrc
				? ANDROID_GENERATED_IMAGE_SOURCE_STATUS.PREVIEW_READY
				: ANDROID_GENERATED_IMAGE_SOURCE_STATUS.WAITING_REMOTE
			emitDiagnostic(slot, 'FINAL_DOWNLOAD_SKIPPED', {
				reason: 'NO_HTTPS_URL', statusBefore, statusAfter: slot.status
			})
			notify(slot)
			return
		}
		if (!force && slot.upgradeFailed && slot.downloadedUrl === url) {
			emitDiagnostic(slot, 'FINAL_DOWNLOAD_SKIPPED', { reason: 'FAILED_NOT_RETRIED' })
			return
		}
		if (!force && slot.operationKind === 'FINAL' && slot.downloadedUrl === url) {
			emitDiagnostic(slot, 'FINAL_DOWNLOAD_SKIPPED', { reason: 'DUPLICATE_IN_FLIGHT' })
			return
		}
		if (!force
			&& slot.status === ANDROID_GENERATED_IMAGE_SOURCE_STATUS.FINAL_READY
			&& slot.downloadedUrl === url) {
			emitDiagnostic(slot, 'FINAL_DOWNLOAD_SKIPPED', { reason: 'ALREADY_READY' })
			return
		}

		closeOperation(slot, 'START_FINAL')
		const statusBefore = slot.status
		slot.revision += 1
		slot.downloadAttempt += 1
		const revision = slot.revision
		slot.downloadedUrl = url
		slot.upgradeFailed = false
		slot.status = ANDROID_GENERATED_IMAGE_SOURCE_STATUS.DOWNLOADING_FINAL
		slot.operationKind = 'FINAL'
		emitDiagnostic(slot, 'FINAL_DOWNLOAD_REQUESTED', {
			statusBefore,
			statusAfter: slot.status,
			force: force === true
		})
		notify(slot)
		emitDiagnostic(slot, 'FINAL_NATIVE_CALL')
		try {
			const operation = fetchHttpsImage({
				url,
				maximumBytes: FINAL_MAXIMUM_BYTES,
				diagnosticsEnabled,
				diagnosticRunId: slot.diagnosticRunId,
				outputIndex: slot.outputIndex,
				downloadAttempt: slot.downloadAttempt,
				onSuccess(image) {
					const current = slots.get(slotKey(slot.ownerKey, slot.outputIndex))
					const managed = controlledManagedImage(image)
					const cleanupPath = controlledLocalFilePath(image?.filePath)
					emitDiagnostic(slot, 'FINAL_SUCCESS_CALLBACK', {
						contentType: String(image?.contentType || '').slice(0, 64),
						sizeBytes: Number(image?.sizeBytes) || 0,
						returnedPathKind: sourceKind(image?.displayUri)
					})
					if (current !== slot || slot.revision !== revision
						|| slot.downloadedUrl !== url) {
						safeRemove(cleanupPath)
						emitDiagnostic(slot, 'STALE_RESULT_REMOVED', { operationKind: 'FINAL' })
						return
					}
					slot.operation = null
					slot.operationKind = ''
					if (!managed) {
						safeRemove(cleanupPath)
						const callbackStatusBefore = slot.status
						slot.upgradeFailed = true
						slot.status = slot.displaySrc
							? ANDROID_GENERATED_IMAGE_SOURCE_STATUS.PREVIEW_READY
							: ANDROID_GENERATED_IMAGE_SOURCE_STATUS.ERROR
						emitDiagnostic(slot, 'FINAL_PATH_REJECTED', {
							statusBefore: callbackStatusBefore,
							statusAfter: slot.status,
							returnedPathKind: sourceKind(image?.displayUri)
						}, true)
						notify(slot)
						return
					}
					const callbackStatusBefore = slot.status
					const oldPreview = slot.previewPath
					const oldFinal = slot.finalPath
					slot.finalPath = managed.filePath
					slot.previewPath = ''
					slot.displaySrc = managed.displayUri
					slot.status = ANDROID_GENERATED_IMAGE_SOURCE_STATUS.FINAL_READY
					slot.upgradeFailed = false
					if (oldPreview && oldPreview !== managed.filePath) safeRemove(oldPreview)
					if (oldFinal && oldFinal !== managed.filePath) safeRemove(oldFinal)
					emitDiagnostic(slot, 'FINAL_READY', {
						statusBefore: callbackStatusBefore,
						statusAfter: slot.status
					})
					notify(slot)
				},
				onError(failure) {
					const current = slots.get(slotKey(slot.ownerKey, slot.outputIndex))
					if (current !== slot || slot.revision !== revision
						|| slot.downloadedUrl !== url) return
					const callbackStatusBefore = slot.status
					slot.operation = null
					slot.operationKind = ''
					slot.upgradeFailed = true
					slot.status = slot.displaySrc
						? ANDROID_GENERATED_IMAGE_SOURCE_STATUS.PREVIEW_READY
						: ANDROID_GENERATED_IMAGE_SOURCE_STATUS.ERROR
					emitDiagnostic(slot, 'FINAL_ERROR_CALLBACK', {
						...safeFailureDetails(failure),
						statusBefore: callbackStatusBefore,
						statusAfter: slot.status
					}, true)
					notify(slot)
				}
			})
			emitDiagnostic(slot, 'FINAL_NATIVE_CALL_RETURNED')
			if (slot.revision === revision && slot.operationKind === 'FINAL') {
				slot.operation = operation || null
			}
		} catch (error) {
			const callbackStatusBefore = slot.status
			slot.operation = null
			slot.operationKind = ''
			slot.upgradeFailed = true
			slot.status = slot.displaySrc
				? ANDROID_GENERATED_IMAGE_SOURCE_STATUS.PREVIEW_READY
				: ANDROID_GENERATED_IMAGE_SOURCE_STATUS.ERROR
			emitDiagnostic(slot, 'FINAL_NATIVE_CALL_FAILED', {
				failureCode: 'AI_ANDROID_IMAGE_NATIVE_CALL_FAILED',
				failureStage: 'NATIVE_CALL',
				exceptionType: String(error?.name || 'UNKNOWN').slice(0, 96),
				statusCode: 0,
				statusBefore: callbackStatusBefore,
				statusAfter: slot.status
			}, true)
			notify(slot)
		}
	}

	function acceptPreview(ownerKey, attachment) {
		const slot = requiredSlot(ownerKey, attachment?.outputIndex)
		const base64 = previewPayload(attachment)
		if (!slot || !base64) {
			if (slot) emitDiagnostic(slot, 'PREVIEW_SKIPPED', { reason: 'NO_BASE64' })
			return
		}
		if (slot.status === ANDROID_GENERATED_IMAGE_SOURCE_STATUS.FINAL_READY) {
			emitDiagnostic(slot, 'PREVIEW_SKIPPED', { reason: 'FINAL_READY' })
			return
		}
		const identity = previewIdentity(attachment)
		if (slot.previewIdentity === identity
			&& (slot.operationKind === 'PREVIEW' || Boolean(slot.previewPath))) {
			emitDiagnostic(slot, 'PREVIEW_SKIPPED', { reason: 'DUPLICATE' })
			return
		}

		closeOperation(slot, 'START_PREVIEW')
		const statusBefore = slot.status
		slot.revision += 1
		const revision = slot.revision
		slot.previewIdentity = identity
		slot.previewKind = String(attachment?.previewKind || 'FULL').toUpperCase()
		slot.requiresUpgrade = attachment?.requiresUpgrade === true
		slot.upgradeFailed = false
		slot.status = ANDROID_GENERATED_IMAGE_SOURCE_STATUS.PREPARING_PREVIEW
		slot.operationKind = 'PREVIEW'
		emitDiagnostic(slot, 'PREVIEW_ACCEPTED', {
			statusBefore,
			statusAfter: slot.status,
			previewKind: slot.previewKind
		})
		notify(slot)
		emitDiagnostic(slot, 'PREVIEW_NATIVE_CALL')
		try {
			const operation = materializeBase64Image({
				base64,
				maximumBytes: PREVIEW_MAXIMUM_BYTES,
				diagnosticsEnabled,
				diagnosticRunId: slot.diagnosticRunId,
				outputIndex: slot.outputIndex,
				downloadAttempt: 0,
				onSuccess(image) {
					const current = slots.get(slotKey(slot.ownerKey, slot.outputIndex))
					const managed = controlledManagedImage(image)
					const cleanupPath = controlledLocalFilePath(image?.filePath)
					emitDiagnostic(slot, 'PREVIEW_SUCCESS_CALLBACK', {
						contentType: String(image?.contentType || '').slice(0, 64),
						sizeBytes: Number(image?.sizeBytes) || 0,
						returnedPathKind: sourceKind(image?.displayUri)
					})
					if (current !== slot || slot.revision !== revision) {
						safeRemove(cleanupPath)
						emitDiagnostic(slot, 'STALE_RESULT_REMOVED', { operationKind: 'PREVIEW' })
						return
					}
					slot.operation = null
					slot.operationKind = ''
					if (!managed) {
						safeRemove(cleanupPath)
						emitDiagnostic(slot, 'PREVIEW_PATH_REJECTED', {
							returnedPathKind: sourceKind(image?.displayUri)
						}, true)
						if (slot.persistedUrl) {
							startFinalDownload(slot)
							return
						}
						const callbackStatusBefore = slot.status
						slot.status = slot.displaySrc
							? ANDROID_GENERATED_IMAGE_SOURCE_STATUS.PREVIEW_READY
							: ANDROID_GENERATED_IMAGE_SOURCE_STATUS.WAITING_REMOTE
						emitDiagnostic(slot, 'PREVIEW_WAITING_REMOTE', {
							statusBefore: callbackStatusBefore,
							statusAfter: slot.status
						})
						notify(slot)
						return
					}
					const callbackStatusBefore = slot.status
					const oldPreview = slot.previewPath
					slot.previewPath = managed.filePath
					slot.displaySrc = managed.displayUri
					slot.status = ANDROID_GENERATED_IMAGE_SOURCE_STATUS.PREVIEW_READY
					if (oldPreview && oldPreview !== managed.filePath) safeRemove(oldPreview)
					emitDiagnostic(slot, 'PREVIEW_READY', {
						statusBefore: callbackStatusBefore,
						statusAfter: slot.status
					})
					notify(slot)
					if (slot.requiresUpgrade && slot.persistedUrl) startFinalDownload(slot)
				},
				onError(failure) {
					const current = slots.get(slotKey(slot.ownerKey, slot.outputIndex))
					if (current !== slot || slot.revision !== revision) return
					slot.operation = null
					slot.operationKind = ''
					emitDiagnostic(slot, 'PREVIEW_ERROR_CALLBACK', safeFailureDetails(failure), true)
					if (slot.persistedUrl) {
						startFinalDownload(slot)
						return
					}
					const callbackStatusBefore = slot.status
					if (slot.displaySrc) {
						slot.status = ANDROID_GENERATED_IMAGE_SOURCE_STATUS.PREVIEW_READY
						emitDiagnostic(slot, 'PREVIEW_READY', {
							statusBefore: callbackStatusBefore,
							statusAfter: slot.status,
							reason: 'FALLBACK_PREVIOUS'
						})
						notify(slot)
						return
					}
					slot.status = ANDROID_GENERATED_IMAGE_SOURCE_STATUS.WAITING_REMOTE
					emitDiagnostic(slot, 'PREVIEW_WAITING_REMOTE', {
						statusBefore: callbackStatusBefore,
						statusAfter: slot.status
					})
					notify(slot)
				}
			})
			emitDiagnostic(slot, 'PREVIEW_NATIVE_CALL_RETURNED')
			if (slot.revision === revision && slot.operationKind === 'PREVIEW') {
				slot.operation = operation || null
			}
		} catch (error) {
			slot.operation = null
			slot.operationKind = ''
			emitDiagnostic(slot, 'PREVIEW_NATIVE_CALL_FAILED', {
				failureCode: 'AI_ANDROID_IMAGE_NATIVE_CALL_FAILED',
				failureStage: 'NATIVE_CALL',
				exceptionType: String(error?.name || 'UNKNOWN').slice(0, 96),
				statusCode: 0
			}, true)
			if (slot.persistedUrl) startFinalDownload(slot)
			else {
				slot.status = slot.displaySrc
					? ANDROID_GENERATED_IMAGE_SOURCE_STATUS.PREVIEW_READY
					: ANDROID_GENERATED_IMAGE_SOURCE_STATUS.WAITING_REMOTE
				notify(slot)
			}
		}
	}

	function acceptPersisted(ownerKey, attachment) {
		const slot = requiredSlot(ownerKey, attachment?.outputIndex)
		const url = remoteUrl(attachment)
		if (!slot || !url) {
			if (slot) emitDiagnostic(slot, 'PERSISTED_REJECTED', { reason: 'NO_HTTPS_URL' }, true)
			return
		}
		slot.persistedUrl = url
		slot.previewKind = String(attachment?.previewKind || slot.previewKind || 'FULL').toUpperCase()
		slot.requiresUpgrade = attachment?.requiresUpgrade === true || slot.previewKind === 'THUMBNAIL'
		emitDiagnostic(slot, 'PERSISTED_ACCEPTED', { previewKind: slot.previewKind })

		if (slot.status === ANDROID_GENERATED_IMAGE_SOURCE_STATUS.FINAL_READY
			&& slot.downloadedUrl === url) {
			emitDiagnostic(slot, 'FINAL_DOWNLOAD_SKIPPED', { reason: 'ALREADY_READY' })
			return
		}
		if (slot.operationKind === 'PREVIEW') {
			// 先让已经开始的本地预览落盘；成功后再按分支决定是否下载高清，避免缩略图被远端任务抢占。
			emitDiagnostic(slot, 'FINAL_DOWNLOAD_SKIPPED', { reason: 'PREVIEW_IN_FLIGHT' })
			return
		}
		if (!slot.requiresUpgrade && slot.previewPath) {
			const statusBefore = slot.status
			slot.status = ANDROID_GENERATED_IMAGE_SOURCE_STATUS.PREVIEW_READY
			emitDiagnostic(slot, 'FINAL_DOWNLOAD_SKIPPED', {
				reason: 'FULL_PREVIEW_VISIBLE', statusBefore, statusAfter: slot.status
			})
			notify(slot)
			return
		}
		startFinalDownload(slot)
	}

	function sourceFor(ownerKey, outputIndex) {
		const key = slotKey(normalizedOwnerKey(ownerKey), normalizedOutputIndex(outputIndex))
		return String(slots.get(key)?.displaySrc || '')
	}

	function filePathFor(ownerKey, outputIndex) {
		const key = slotKey(normalizedOwnerKey(ownerKey), normalizedOutputIndex(outputIndex))
		const slot = slots.get(key)
		return controlledLocalFilePath(slot?.finalPath)
	}

	function statusFor(ownerKey, outputIndex) {
		const key = slotKey(normalizedOwnerKey(ownerKey), normalizedOutputIndex(outputIndex))
		return slots.get(key)?.status
			|| ANDROID_GENERATED_IMAGE_SOURCE_STATUS.WAITING_REMOTE
	}

	function diagnosticRunIdFor(ownerKey, outputIndex) {
		return requiredSlot(ownerKey, outputIndex)?.diagnosticRunId || ''
	}

	function retryFinal(ownerKey, outputIndex) {
		const key = slotKey(normalizedOwnerKey(ownerKey), normalizedOutputIndex(outputIndex))
		const slot = slots.get(key)
		if (!slot?.persistedUrl) return false
		emitDiagnostic(slot, 'USER_RETRY_REQUESTED')
		startFinalDownload(slot, true)
		return true
	}

	function releaseSlot(slot) {
		slot.revision += 1
		closeOperation(slot, 'RELEASE_SLOT')
		emitDiagnostic(slot, 'SLOT_RELEASED')
		const paths = new Set([slot.previewPath, slot.finalPath])
		paths.forEach(safeRemove)
		slot.previewPath = ''
		slot.finalPath = ''
		slot.displaySrc = ''
	}

	function releaseMessage(ownerKeyValue) {
		const ownerKey = normalizedOwnerKey(ownerKeyValue)
		if (!ownerKey) return
		for (const [key, slot] of slots.entries()) {
			if (slot.ownerKey !== ownerKey) continue
			releaseSlot(slot)
			slots.delete(key)
		}
		onChange(Object.freeze({ ownerKey, released: true }))
	}

	function releaseAll() {
		for (const slot of slots.values()) releaseSlot(slot)
		slots.clear()
		onChange(Object.freeze({ releasedAll: true }))
	}

	return Object.freeze({
		acceptPreview,
		acceptPersisted,
		sourceFor,
		filePathFor,
		statusFor,
		diagnosticRunIdFor,
		retryFinal,
		releaseMessage,
		releaseAll
	})
}
