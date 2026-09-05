import {
	assertAuthorizedSessionCurrent,
	isAuthorizedSessionTermination,
	prepareAuthorizedStreamingRequest,
	recoverAuthorizedStreamingSession
} from '../auth/http-client.js'
import { clientPlatform } from '../auth/config.js'
import {
	isAndroidEdgeChallenge,
	repeatedAndroidEdgeChallengeError
} from '../auth/android-edge-challenge-policy.js'
import { openAiConversationSseH5 } from './ai-conversation-sse-h5.js'
import { createAiConversationStreamDiagnostics } from './ai-conversation-stream-diagnostics.js'
import { reportAiConversationStreamDiagnostics } from './ai-conversation-stream-diagnostics-reporter.js'
import { createAiConversationLifecycleDiagnostics } from './ai-conversation-lifecycle-diagnostics.js'
import {
	imagePreviewAttachment,
	failImageOutputAttachment,
	mergeCompletedImageOutputs,
	mergeImagePreviewOutput,
	mergePersistedImageOutput,
	persistedImageOutputAttachment,
	persistedImageAttachments
} from './ai-conversation-image-generation.js'
import {
	appendMissingImagePresentationOrder,
	recordImagePresentationOrder
} from './ai-conversation-image-gallery.js'
import {
	initialVideoUploadProgress,
	mergeMediaUploadProgress
} from './ai-conversation-media-upload-progress.js'
import {
	asyncGenerationEnabled,
	bindGenerationObserver,
	getGeneration,
	markGenerationTerminal,
	registerGeneration,
	updateGeneration
} from './ai-conversation-generation-manager.js'
import { mergeAiConversationSources } from './ai-conversation-source-presentation.js'
// #ifdef APP-PLUS
import { openAiConversationSseApp } from './ai-conversation-sse-app.js'
// #endif

function responsePath(conversationPublicId) {
	return conversationPublicId
		? `/api/ai/conversations/${encodeURIComponent(conversationPublicId)}/responses`
		: '/api/ai/conversations/responses'
}

function wait(milliseconds) {
	return new Promise(resolve => setTimeout(resolve, milliseconds))
}

async function recoverGenerationEdgeChallenge(error, recoveryState, sessionGeneration) {
	if (isAuthorizedSessionTermination(error)) throw error
	assertAuthorizedSessionCurrent(sessionGeneration)
	if (!isAndroidEdgeChallenge(error)) return false
	if (recoveryState.edgeChallengeRetried) {
		throw repeatedAndroidEdgeChallengeError(error)
	}
	const recovered = await recoverAuthorizedStreamingSession(error, { sessionGeneration })
	if (!recovered) return false
	recoveryState.edgeChallengeRetried = true
	return true
}

async function openOnce(command, handlers, lifecycleDiagnostics, sessionGeneration, allowCsrfRecovery) {
	const prepared = await prepareAuthorizedStreamingRequest(
		responsePath(command.conversationPublicId),
		{
			sessionGeneration,
			allowCsrfRecovery,
			method: 'POST',
			headers: {
				'Content-Type': 'application/json',
				Accept: 'text/event-stream, application/json',
				'Idempotency-Key': command.idempotencyKey,
				...(lifecycleDiagnostics.clientRequestId === 'unavailable'
					? {}
					: { 'X-AI-Client-Request-Id': lifecycleDiagnostics.clientRequestId })
			}
		}
	)
	lifecycleDiagnostics.record?.('CLIENT_FETCH_SENT')
	const request = { ...prepared, body: command.body }
	if (clientPlatform() === 'ANDROID') {
		// #ifdef APP-PLUS
		return openAiConversationSseApp(request, handlers)
		// #endif
	}
	return openAiConversationSseH5(request, handlers)
}

async function openGenerationOnce(generationPublicId, handlers, lifecycleDiagnostics, sessionGeneration) {
	const prepared = await prepareAuthorizedStreamingRequest(
		`/api/ai/conversations/generations/${encodeURIComponent(generationPublicId)}/events`,
		{ method: 'GET', headers: { Accept: 'text/event-stream' }, sessionGeneration }
	)
	const request = { ...prepared }
	if (clientPlatform() === 'ANDROID') {
		// #ifdef APP-PLUS
		return openAiConversationSseApp(request, handlers)
		// #endif
	}
	return openAiConversationSseH5(request, handlers)
}

/**
 * 打开一次 POST SSE；只有 accepted 尚未到达时才允许用同一个幂等键恢复认证并重试一次。
 */
export async function openAiConversationStream(command, handlers = {}) {
	const sessionGeneration = assertAuthorizedSessionCurrent()
	let accepted = false
	let active = null
	let closed = false
	let closeReason = 'USER_STOP'
	let closeDetails = {}
	const diagnostics = handlers.diagnostics
		|| createAiConversationStreamDiagnostics({
			enabled: clientPlatform() === 'ANDROID' ? true : undefined,
			onSummary: reportAiConversationStreamDiagnostics
		})
	const lifecycleDiagnostics = handlers.lifecycleDiagnostics
		|| createAiConversationLifecycleDiagnostics()
	lifecycleDiagnostics.record?.('CLIENT_STREAM_CREATED')
	let firstDeltaRecorded = false
	let publicHandle = null
	let generationPublicId = command.generationPublicId || ''
	let researchSources = []
	const wrapped = {
		diagnostics,
		lifecycleDiagnostics,
		onGenerationId(value) {
			assertAuthorizedSessionCurrent(sessionGeneration)
			diagnostics.bindGenerationPublicId?.(value)
			if (!asyncGenerationEnabled() || !value) return
			generationPublicId = value
			registerGeneration({
				generationPublicId: value,
				conversationPublicId: command.conversationPublicId,
				idempotencyKey: command.idempotencyKey,
				localId: command.localId,
				inputText: command.inputText,
				requestedImageCount: Number(command.requestedImageCount || 0),
				requestedImageAspect: String(command.body?.image?.aspect || 'SQUARE'),
				imagePresentationOrder: [],
				previewImages: [],
				researchSources,
				status: 'QUEUED'
			})
			if (publicHandle) bindGenerationObserver(value, publicHandle)
			handlers.onGenerationId?.(value)
		},
		onEvent(event) {
			assertAuthorizedSessionCurrent(sessionGeneration)
			let terminalStatus = null
			if (event.type === 'accepted') {
				accepted = true
				diagnostics.bindUsagePublicId?.(event.data?.usagePublicId)
				diagnostics.bindGenerationPublicId?.(event.data?.generationPublicId)
				lifecycleDiagnostics.bindUsagePublicId?.(
					event.data?.usagePublicId)
				lifecycleDiagnostics.record?.('CLIENT_SSE_ACCEPTED', {
					hasReportedUsage: false
				})
				if (asyncGenerationEnabled() && event.data?.generationPublicId) {
					generationPublicId = event.data.generationPublicId
					registerGeneration({
						generationPublicId: event.data.generationPublicId,
						conversationPublicId: event.data.conversationPublicId,
						usagePublicId: event.data.usagePublicId,
						idempotencyKey: command.idempotencyKey,
						localId: command.localId,
						inputText: command.inputText,
						requestedImageCount: Number(command.requestedImageCount || 0),
						requestedImageAspect: String(command.body?.image?.aspect || 'SQUARE'),
						imagePresentationOrder: [],
						previewImages: [],
						researchSources,
						status: 'RUNNING'
					})
					if (publicHandle) {
						bindGenerationObserver(event.data.generationPublicId, publicHandle)
					}
					handlers.onGenerationId?.(event.data.generationPublicId)
				}
			}
			if (event.type === 'snapshot' && generationPublicId) {
				updateGeneration(generationPublicId, {
					revision: Number(event.data?.revision || 0),
					responseText: String(event.data?.text || '')
				})
			}
			if (asyncGenerationEnabled() && event.type === 'delta'
				&& event.data?.text && generationPublicId) {
				const current = getGeneration(generationPublicId)
				updateGeneration(generationPublicId, {
					revision: Math.max(Number(current?.revision || 0), Number(event.data?.revision || 0)),
					responseText: `${current?.responseText || ''}${event.data.text}`
				})
			}
			if (event.type === 'image-preview' && generationPublicId) {
				const previewImage = imagePreviewAttachment(event.data)
				if (previewImage) {
					const current = getGeneration(generationPublicId)
					updateGeneration(generationPublicId, {
						imagePresentationOrder: recordImagePresentationOrder(
							current?.imagePresentationOrder, previewImage),
						previewImages: mergeImagePreviewOutput(
							current?.previewImages || [], previewImage)
					})
				}
			}
			if (event.type === 'image-persisted' && generationPublicId) {
				const persistedImage = persistedImageOutputAttachment(event.data)
				if (persistedImage) {
					const current = getGeneration(generationPublicId)
					updateGeneration(generationPublicId, {
						imagePresentationOrder: recordImagePresentationOrder(
							current?.imagePresentationOrder, persistedImage),
						previewImages: mergePersistedImageOutput(
							current?.previewImages || [], persistedImage)
					})
				}
			}
			if (event.type === 'image-output-status' && generationPublicId) {
				const current = getGeneration(generationPublicId)
				const failedImages = failImageOutputAttachment(
					current?.previewImages || [], event.data)
				updateGeneration(generationPublicId, {
					previewImages: failedImages,
					failedImageOutputs: failedImages.filter(item =>
						item?.status === 'FAILED')
				})
			}
			if (event.type === 'media_upload_progress' && generationPublicId) {
				const current = getGeneration(generationPublicId)
				updateGeneration(generationPublicId, {
					mediaUploadProgressByKey: mergeMediaUploadProgress(
						current?.mediaUploadProgressByKey, event.data)
				})
			}
			if (event.type === 'source') {
				researchSources = mergeAiConversationSources(
					researchSources, [event.data])
				if (generationPublicId) {
					const current = getGeneration(generationPublicId)
					const merged = mergeAiConversationSources(
						current?.researchSources, researchSources)
					if (merged.length !== (current?.researchSources || []).length) {
						updateGeneration(generationPublicId, {
							researchSources: merged
						})
					}
				}
			}
			if (event.type === 'video_generation_progress' && generationPublicId) {
				updateGeneration(generationPublicId, {
					videoProgress: Math.max(0, Math.min(100,
						Number(event.data?.progress || 0)))
				})
			}
			if (event.type === 'video_transfer_started' && generationPublicId) {
				const current = getGeneration(generationPublicId)
				updateGeneration(generationPublicId, {
					videoTransferring: true,
					mediaUploadProgressByKey: mergeMediaUploadProgress(
						current?.mediaUploadProgressByKey, initialVideoUploadProgress())
				})
			}
			if ((event.type === 'video_ready' || event.type === 'video_failed')
				&& generationPublicId) {
				updateGeneration(generationPublicId, {
					messagePublicId: event.data?.messagePublicId || '',
					responseAttachments: event.type === 'video_ready'
						&& Array.isArray(event.data?.attachments)
						? event.data.attachments : [],
					videoMetadata: event.type === 'video_ready' ? {
						durationMillis: Number(event.data?.durationMillis || 0),
						width: Number(event.data?.width || 0),
						height: Number(event.data?.height || 0),
						byteSize: Number(event.data?.byteSize || 0),
						videoCodec: String(event.data?.videoCodec || '')
					} : null,
					videoTransferring: false,
					videoError: event.type === 'video_failed'
						? String(event.data?.failureStage
							|| event.data?.terminalReason || 'VIDEO_FAILED') : '',
					videoErrorCode: event.type === 'video_failed'
						? String(event.data?.errorCode
							|| event.data?.terminalReason || 'AI_VIDEO_FAILED') : ''
				})
				terminalStatus = event.data?.status || (event.type === 'video_ready'
					? 'SETTLED' : 'RECONCILE_REQUIRED')
			}
			if (asyncGenerationEnabled() && event.type === 'completed'
				&& generationPublicId) {
				const current = getGeneration(generationPublicId)
				const terminalAttachmentEvidenceComplete =
					Array.isArray(event.data?.attachments)
				const persisted = persistedImageAttachments(event.data)
				const requestedImageCount = Number(
					event.data?.requestedImageCount
						|| current?.requestedImageCount || 0)
				const displayAttachments = mergeCompletedImageOutputs(
					current?.previewImages,
					persisted,
					terminalAttachmentEvidenceComplete
						? requestedImageCount : 0)
				const imagePresentationOrder = appendMissingImagePresentationOrder(
					current?.imagePresentationOrder, displayAttachments)
				updateGeneration(generationPublicId, {
					terminalType: event.data.terminalType,
					terminalReason: event.data.terminalReason,
					responseAttachments: mergeCompletedImageOutputs(
						current?.failedImageOutputs,
						persisted,
						terminalAttachmentEvidenceComplete
							? requestedImageCount : 0),
					previewImages: displayAttachments,
					imagePresentationOrder,
					requestedImageCount,
					messagePublicId: event.data?.messagePublicId || '',
					terminalAttachmentEvidenceComplete,
					successfulImageCount: persisted.length,
					warnings: event.data.terminalReason === 'IMAGE_OSS_PERSISTENCE_DROPPED'
						? ['ATTACHMENT_STORAGE_PARTIAL'] : []
				})
				terminalStatus = event.data.status || 'COMPLETED'
			}
			if (event.type === 'delta' && event.data?.type === 'TEXT'
				&& String(event.data?.text || '').length > 0) {
				const textCharacters = String(event.data?.text || '').length
				lifecycleDiagnostics.observeVisibleOutput?.(textCharacters)
				if (!firstDeltaRecorded) {
					firstDeltaRecorded = true
					lifecycleDiagnostics.record?.('CLIENT_FIRST_DELTA')
				}
			}
			if (event.type === 'completed') {
				// accepted 只是预扣记录；只有 completed 才能证明上游最终 Usage 已随终态到达。
				lifecycleDiagnostics.reportedUsageObserved?.()
			}
			diagnostics.record?.('BROWSER_SSE_PARSED', {
				eventType: event.type,
				sequence: event.data?.sequence,
				textCharacters: event.type === 'delta'
					? String(event.data?.text || '').length
					: 0
			})
			try {
				handlers.onEvent?.(event)
			} finally {
				if (terminalStatus) markGenerationTerminal(
					generationPublicId, terminalStatus)
			}
		}
	}

	async function connect(retried) {
		active = await openOnce(command, wrapped, lifecycleDiagnostics, sessionGeneration, !retried && !accepted)
		// 停止动作可能早于认证准备或原生传输句柄创建；句柄一旦到达必须立即关闭，不能让请求在后台继续计费。
		if (closed) {
			active.close?.(closeReason, closeDetails)
			return
		}
		try {
			await active.completed
		} catch (error) {
			if (!closed && !accepted && !retried
				&& await recoverAuthorizedStreamingSession(error, { sessionGeneration })) {
				return connect(true)
			}
			if (isAuthorizedSessionTermination(error)) throw error
			assertAuthorizedSessionCurrent(sessionGeneration)
			if (!closed && !accepted && retried && isAndroidEdgeChallenge(error)) {
				throw repeatedAndroidEdgeChallengeError(error)
			}
			if (!closed && asyncGenerationEnabled() && generationPublicId) {
				return reconnectGeneration(error)
			}
			if (!closed) throw error
		}
	}

	async function reconnectGeneration(initialFailure) {
		if (isAuthorizedSessionTermination(initialFailure)) throw initialFailure
		assertAuthorizedSessionCurrent(sessionGeneration)
		const deadline = Date.now() + 25_000
		let lastFailure = initialFailure
		let delay = 250
		const recoveryState = { edgeChallengeRetried: false }
		if (await recoverGenerationEdgeChallenge(initialFailure, recoveryState, sessionGeneration)) {
			delay = 0
		}
		while (!closed && Date.now() < deadline) {
			assertAuthorizedSessionCurrent(sessionGeneration)
			updateGeneration(generationPublicId, { observerAttached: false })
			await wait(delay)
			if (closed) return
			assertAuthorizedSessionCurrent(sessionGeneration)
			try {
				active = await openGenerationOnce(
					generationPublicId, wrapped, lifecycleDiagnostics, sessionGeneration)
				if (closed) { active.close?.(closeReason, closeDetails); return }
				assertAuthorizedSessionCurrent(sessionGeneration)
				bindGenerationObserver(generationPublicId, publicHandle)
				await active.completed
				return
			} catch (failure) {
				if (await recoverGenerationEdgeChallenge(failure, recoveryState, sessionGeneration)) {
					delay = 0
					continue
				}
				lastFailure = failure
				delay = Math.min(delay * 2, 2_000)
			}
		}
		if (!closed) throw lastFailure
	}

	publicHandle = Object.freeze({
		completed: connect(false),
		close(reason = 'USER_STOP', details = {}) {
			closed = true
			closeReason = reason
			closeDetails = details
			lifecycleDiagnostics.stopRequested?.(reason, details)
			active?.close?.(reason, details)
		},
		finishPresentation(outcome, details = {}) {
			lifecycleDiagnostics.finish?.(outcome, details)
		},
		lifecycleDiagnostics
	})
	return publicHandle
}

/**
 * 在刷新、路由返回或 SSE 传输恢复时重新观察既有 Generation；关闭句柄只表示 DETACHED。
 */
export async function openAiConversationGenerationStream(generationPublicId, handlers = {}) {
	const sessionGeneration = assertAuthorizedSessionCurrent()
	const diagnostics = handlers.diagnostics
		|| createAiConversationStreamDiagnostics({
			enabled: clientPlatform() === 'ANDROID' ? true : undefined,
			onSummary: reportAiConversationStreamDiagnostics
		})
	diagnostics.bindGenerationPublicId?.(generationPublicId)
	const lifecycleDiagnostics = handlers.lifecycleDiagnostics
		|| createAiConversationLifecycleDiagnostics()
	const wrapped = {
		...handlers,
		diagnostics,
		lifecycleDiagnostics,
		onEvent(event) {
			assertAuthorizedSessionCurrent(sessionGeneration)
			let terminalStatus = null
			if (event.type === 'source') {
				const current = getGeneration(generationPublicId)
				const researchSources = mergeAiConversationSources(
					current?.researchSources, [event.data])
				if (researchSources.length
						!== (current?.researchSources || []).length) {
					updateGeneration(generationPublicId, { researchSources })
				}
			}
			if (event.type === 'snapshot') {
				updateGeneration(generationPublicId, {
					revision: Number(event.data?.revision || 0),
					responseText: String(event.data?.text || ''),
					observerAttached: true
				})
			}
			if (event.type === 'delta' && event.data?.text) {
				const current = getGeneration(generationPublicId)
				updateGeneration(generationPublicId, {
					revision: Math.max(Number(current?.revision || 0), Number(event.data?.revision || 0)),
					responseText: `${current?.responseText || ''}${event.data.text}`
				})
			}
			if (event.type === 'image-preview') {
				const previewImage = imagePreviewAttachment(event.data)
				if (previewImage) {
					const current = getGeneration(generationPublicId)
					updateGeneration(generationPublicId, {
						imagePresentationOrder: recordImagePresentationOrder(
							current?.imagePresentationOrder, previewImage),
						previewImages: mergeImagePreviewOutput(
							current?.previewImages || [], previewImage)
					})
				}
			}
			if (event.type === 'image-persisted') {
				const persistedImage = persistedImageOutputAttachment(event.data)
				if (persistedImage) {
					const current = getGeneration(generationPublicId)
					updateGeneration(generationPublicId, {
						imagePresentationOrder: recordImagePresentationOrder(
							current?.imagePresentationOrder, persistedImage),
						previewImages: mergePersistedImageOutput(
							current?.previewImages || [], persistedImage)
					})
				}
			}
			if (event.type === 'image-output-status') {
				const current = getGeneration(generationPublicId)
				const failedImages = failImageOutputAttachment(
					current?.previewImages || [], event.data)
				updateGeneration(generationPublicId, {
					previewImages: failedImages,
					failedImageOutputs: failedImages.filter(item =>
						item?.status === 'FAILED')
				})
			}
			if (event.type === 'media_upload_progress') {
				const current = getGeneration(generationPublicId)
				updateGeneration(generationPublicId, {
					mediaUploadProgressByKey: mergeMediaUploadProgress(
						current?.mediaUploadProgressByKey, event.data)
				})
			}
			if (event.type === 'video_generation_progress') {
				updateGeneration(generationPublicId, {
					videoProgress: Math.max(0, Math.min(100,
						Number(event.data?.progress || 0)))
				})
			}
			if (event.type === 'video_transfer_started') {
				const current = getGeneration(generationPublicId)
				updateGeneration(generationPublicId, {
					videoTransferring: true,
					mediaUploadProgressByKey: mergeMediaUploadProgress(
						current?.mediaUploadProgressByKey, initialVideoUploadProgress())
				})
			}
			if (event.type === 'video_ready' || event.type === 'video_failed') {
				updateGeneration(generationPublicId, {
					messagePublicId: event.data?.messagePublicId || '',
					responseAttachments: event.type === 'video_ready'
						&& Array.isArray(event.data?.attachments)
						? event.data.attachments : [],
					videoMetadata: event.type === 'video_ready' ? {
						durationMillis: Number(event.data?.durationMillis || 0),
						width: Number(event.data?.width || 0),
						height: Number(event.data?.height || 0),
						byteSize: Number(event.data?.byteSize || 0),
						videoCodec: String(event.data?.videoCodec || '')
					} : null,
					videoTransferring: false,
					videoError: event.type === 'video_failed'
						? String(event.data?.failureStage
							|| event.data?.terminalReason || 'VIDEO_FAILED') : '',
					videoErrorCode: event.type === 'video_failed'
						? String(event.data?.errorCode
							|| event.data?.terminalReason || 'AI_VIDEO_FAILED') : ''
				})
				terminalStatus = event.data?.status || (event.type === 'video_ready'
					? 'SETTLED' : 'RECONCILE_REQUIRED')
			}
			if (event.type === 'completed') {
				const current = getGeneration(generationPublicId)
				const terminalAttachmentEvidenceComplete =
					Array.isArray(event.data?.attachments)
				const persisted = persistedImageAttachments(event.data)
				const requestedImageCount = Number(
					event.data?.requestedImageCount
						|| current?.requestedImageCount || 0)
				const displayAttachments = mergeCompletedImageOutputs(
					current?.previewImages,
					persisted,
					terminalAttachmentEvidenceComplete
						? requestedImageCount : 0)
				const imagePresentationOrder = appendMissingImagePresentationOrder(
					current?.imagePresentationOrder, displayAttachments)
				updateGeneration(generationPublicId, {
					terminalType: event.data?.terminalType,
					terminalReason: event.data?.terminalReason,
					responseAttachments: mergeCompletedImageOutputs(
						current?.failedImageOutputs,
						persisted,
						terminalAttachmentEvidenceComplete
							? requestedImageCount : 0),
					previewImages: displayAttachments,
					imagePresentationOrder,
					requestedImageCount,
					messagePublicId: event.data?.messagePublicId || '',
					terminalAttachmentEvidenceComplete,
					successfulImageCount: persisted.length,
					warnings: event.data?.terminalReason === 'IMAGE_OSS_PERSISTENCE_DROPPED'
						? ['ATTACHMENT_STORAGE_PARTIAL'] : []
				})
				terminalStatus = event.data?.status || 'COMPLETED'
			}
			diagnostics.record?.('BROWSER_SSE_PARSED', {
				eventType: event.type,
				sequence: event.data?.sequence,
				textCharacters: event.type === 'delta'
					? String(event.data?.text || '').length
					: 0
			})
			try {
				handlers.onEvent?.(event)
			} finally {
				if (terminalStatus) markGenerationTerminal(
					generationPublicId, terminalStatus)
			}
		}
	}
	let active = await openGenerationOnce(generationPublicId, wrapped, lifecycleDiagnostics, sessionGeneration)
	let closed = false
	let handle = null
	const completed = (async () => {
		let outcome = 'TRANSPORT_ERROR'
		const recoveryState = { edgeChallengeRetried: false }
		try {
			await active.completed
			outcome = 'COMPLETE'
			return
		} catch (initialFailure) {
			let lastFailure = initialFailure
			let delay = 250
			if (await recoverGenerationEdgeChallenge(initialFailure, recoveryState, sessionGeneration)) {
				delay = 0
			}
			const deadline = Date.now() + 25_000
			while (!closed && Date.now() < deadline) {
				assertAuthorizedSessionCurrent(sessionGeneration)
				updateGeneration(generationPublicId, { observerAttached: false })
				await wait(delay)
				if (closed) return
				assertAuthorizedSessionCurrent(sessionGeneration)
				try {
					active = await openGenerationOnce(
						generationPublicId, wrapped, lifecycleDiagnostics, sessionGeneration)
					if (closed) { active.close?.('CLIENT_DETACHED'); return }
					assertAuthorizedSessionCurrent(sessionGeneration)
					bindGenerationObserver(generationPublicId, handle)
					await active.completed
					outcome = 'COMPLETE'
					return
				} catch (failure) {
					if (await recoverGenerationEdgeChallenge(failure, recoveryState, sessionGeneration)) {
						delay = 0
						continue
					}
					lastFailure = failure
					delay = Math.min(delay * 2, 2_000)
				}
			}
			if (!closed) throw lastFailure
		} finally {
			diagnostics.finish?.(closed ? 'CLIENT_DETACHED' : outcome)
		}
	})()
	handle = Object.freeze({
		completed,
		close() {
			closed = true
			active.close?.('CLIENT_DETACHED')
		}
	})
	bindGenerationObserver(generationPublicId, handle)
	return handle
}
