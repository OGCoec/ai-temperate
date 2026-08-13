const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function source(relativePath) {
	return fs.readFileSync(path.resolve(__dirname, relativePath), 'utf8')
}

test('frontend defaults to direct MVC SSE and supports explicit async rollback', () => {
	const vite = source('../../vite.config.js')

	assert.match(
		vite,
		/process\.env\.AI_CONVERSATION_ASYNC_GENERATION_ENABLED === 'true'/
	)
})

test('page hiding and component switching do not issue explicit cancellation in async mode', () => {
	const panel = source('../../components/user/workspace/user-chat-panel.vue')

	assert.match(panel, /handlePageHide\(\)[\s\S]*if \(!asyncGenerationEnabled\(\)\)/)
	assert.match(panel, /releaseCurrentGenerationView\(\)/)
	assert.doesNotMatch(panel, /if \(asyncGenerationEnabled\(\)\) this\.stop\('PAGE_HIDDEN'\)/)
})

test('explicit stop persists cancellation even when the generation id arrives later', () => {
	const panel = source('../../components/user/workspace/user-chat-panel.vue')
	const calls = panel.match(/cancelGeneration\(/g) || []

	assert.equal(calls.length, 1)
	assert.match(panel, /cancelGenerationWithRetry\(generationPublicId\)/)
	assert.match(panel, /return await aiConversationApi\.cancelGeneration\(generationPublicId\)/)
	assert.match(panel, /cancelRequestedBeforeGenerationId = true/)
	assert.match(panel, /onGenerationId:[\s\S]*requestGenerationCancellation\(generationPublicId\)/)
	assert.match(panel, /saveAiConversationStoppedDraft\(/)
	assert.match(panel, /cancelDirectResponseWithRetry\(this\.activeIdempotencyKey\)/)
	assert.match(panel, /aiConversationApi\.cancelResponse\(idempotencyKey\)/)
	assert.match(panel, /this\.activeStream\?\.close\?\.\('USER_STOP'/)
})

test('cancel request failure keeps the global observer for authoritative terminal state', () => {
	const panel = source('../../components/user/workspace/user-chat-panel.vue')
	const start = panel.indexOf('async requestGenerationCancellation')
	const end = panel.indexOf('\n\t\t\tasync stop', start)
	const cancellationMethod = panel.slice(start, end)
	const stopStart = panel.indexOf('async stop')
	const stopEnd = panel.indexOf('\n\t\t\tscrollBottom', stopStart)
	const stopMethod = panel.slice(stopStart, stopEnd)

	assert.doesNotMatch(cancellationMethod, /detachGenerationObserver\(generationPublicId\)/)
	assert.match(cancellationMethod, /cancelGenerationWithRetry\(generationPublicId\)/)
	assert.doesNotMatch(stopMethod, /activeGenerationSubscription\?\.\(\)/)
})

test('global manager can retain many observers and notify a newly opened conversation view', () => {
	const manager = source('ai-conversation-generation-manager.js')

	assert.match(manager, /const observers = new Map\(\)/)
	assert.match(manager, /const listeners = new Map\(\)/)
	assert.match(manager, /export function subscribeGeneration/)
	assert.match(manager, /previous && previous !== observer/)
})

test('transport reconnects with GET and never turns an SSE error into a refund command', () => {
	const stream = source('ai-conversation-stream.js')

	assert.match(stream, /method: 'GET'/)
	assert.match(stream, /CLIENT_DETACHED/)
	assert.match(stream, /Date\.now\(\) \+ 25_000/)
	assert.match(stream, /reconnectGeneration/)
	assert.doesNotMatch(stream, /REFUND_FULL|REFUND_REQUESTED/)
	assert.match(stream, /handlers\.onGenerationId\?\.\(event\.data\.generationPublicId\)/)
})

test('persisted image events update one slot without becoming a terminal event', () => {
	const stream = source('ai-conversation-stream.js')
	const panel = source('../../components/user/workspace/user-chat-panel.vue')

	assert.match(stream, /event\.type === 'image-persisted'/)
	assert.match(stream, /mergePersistedImageOutput\(/)
	assert.match(panel, /event\.type === 'image-persisted'/)
	assert.match(panel, /beginImageUpgrade\(/)
	assert.match(panel, /activeToken\?\.persistedUrl === attachment\.persistedUrl/)
	assert.doesNotMatch(stream, /image-persisted'[\s\S]{0,160}terminalStatus\s*=/)
})

test('multi-image presentation records arrival order without prebuilding empty tiles', () => {
	const stream = source('ai-conversation-stream.js')
	const panel = source('../../components/user/workspace/user-chat-panel.vue')

	assert.match(stream, /recordImagePresentationOrder\(/)
	assert.match(stream, /imagePresentationOrder:\s*\[\]/)
	assert.match(stream, /previewImages:\s*\[\]/)
	assert.match(panel, /recordImagePresentationOrder\(/)
	assert.match(panel, /appendMissingImagePresentationOrder\(/)
	assert.match(panel, /beginVisibleImageUpgrades\(/)
	assert.doesNotMatch(panel, /createImageOutputSlots\(requestedImageCount\)/)
})

test('async terminal reconciles persisted input attachments before releasing local previews', () => {
	const panel = source('../../components/user/workspace/user-chat-panel.vue')
	const videoReadyStart = panel.indexOf("event.type === 'video_ready'")
	const videoReadyEnd = panel.indexOf("event.type === 'video_failed'", videoReadyStart)
	const videoReady = panel.slice(videoReadyStart, videoReadyEnd)
	const asyncCompletedStart = panel.indexOf(
		"event.type === 'completed' && event.data?.generationPublicId"
	)
	const asyncCompletedEnd = panel.indexOf(
		"} else if (event.type === 'completed')",
		asyncCompletedStart
	)
	const asyncCompleted = panel.slice(asyncCompletedStart, asyncCompletedEnd)
	const start = panel.indexOf('async reconcileCompletedInputAttachments')
	const end = panel.indexOf('\n\t\t\thandleModelActivity', start)
	const reconciliation = panel.slice(start, end)

	assert.match(videoReady, /reconcileCompletedInputAttachments\(/)
	assert.match(
		asyncCompleted,
		/void this\.reconcileCompletedInputAttachments\(\s*localId,\s*event\.data\?\.messagePublicId\s*\)/
	)
	assert.match(reconciliation, /aiConversationApi\.messages\(conversationPublicId\)/)
	assert.match(reconciliation, /contentAttachments: persistedMessage\.contentAttachments/)
	assert.match(reconciliation, /this\.localPreviewUrls\.delete\(localId\)/)
	assert.match(reconciliation, /this\.\$nextTick\(\(\) =>\s*this\.releasePreviewUrls\(previewSources\)\)/)
})
