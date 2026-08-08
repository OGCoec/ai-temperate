<template>
	<view class="chat-main" role="main">
			<view class="chat-header">
				<button class="icon-button mobile-only" type="button" aria-label="打开会话列表" @click="$emit('open-conversation-drawer')">
					<uni-icons type="bars" size="22" color="#dce5e0" aria-hidden="true" />
				</button>
				<view class="chat-header-copy">
					<text class="chat-header-title">{{ activeConversationTitle }}</text>
					<text class="chat-header-subtitle">{{ selectedModel?.modelName || '请选择模型' }}</text>
				</view>
				<view class="chat-header-balance mobile-only" aria-hidden="true"></view>
			</view>

			<view class="message-stage">
				<user-conversation-turn-rail
					:turns="visibleTurnItems"
					:active-turn-key="activeTurnKey"
					:has-hidden-before="hasHiddenTurnsBefore"
					:has-hidden-after="hasHiddenTurnsAfter"
					:has-more-before="hasMoreMessages"
					:positions-known="!hasMoreMessages"
					:loading-before="messagesLoading"
					:load-error="olderMessagesError"
					@select-turn="selectConversationTurn"
					@request-older="showEarlierTurnWindow"
					@retry-older="loadOlderMessages({ preserveAnchor: true })"
				/>
				<scroll-view
					ref="messageScroll"
					class="message-scroll"
					scroll-y
					:scroll-into-view="turnNavigationDesktop ? '' : scrollTarget"
					@scroll="handleMessageScroll"
				>
					<view class="message-shell">
					<view v-if="messagesLoading && !messages.length" class="chat-empty" role="status">
						<text>正在读取历史消息…</text>
					</view>
					<view v-else-if="!messages.length" class="chat-empty">
						<view class="chat-empty-mark">AI</view>
						<text class="chat-empty-title">有什么可以帮你？</text>
						<text class="chat-empty-copy">输入文字或添加附件。会话会在第一条完整回答成功后出现在最近列表。</text>
					</view>
					<button v-if="hasMoreMessages && !turnNavigationDesktop" class="history-more" type="button" :disabled="messagesLoading" @click="loadOlderMessages()">
						{{ messagesLoading ? '加载中…' : '加载更早消息' }}
					</button>
					<button
						v-if="turnNavigationDesktop && olderMessagesError"
						class="history-more is-error"
						type="button"
						@click="loadOlderMessages({ preserveAnchor: true })"
					>
						更早消息加载失败，点击重试
					</button>
					<view
						v-for="(message, renderedIndex) in renderedMessages"
						:id="messageElementId(message, renderedMessageIndex(renderedIndex))"
						:key="messageElementId(message, renderedMessageIndex(renderedIndex))"
						class="message-turn"
					>
						<view class="message-block user-message">
							<text v-if="message.contentText" class="message-text">{{ message.contentText }}</text>
							<view v-if="message.contentAttachments?.length" class="attachment-grid">
								<view
									v-for="attachment in message.contentAttachments"
									:key="attachment.attachmentId"
									class="attachment-card"
									:class="{ 'is-video': previewVideo(attachment) }"
									:style="previewVideo(attachment) ? generatedVideoCardStyle(attachment) : null"
								>
									<image v-if="previewImage(attachment)" class="attachment-image" :src="attachment.url" mode="aspectFill" />
									<video
										v-else-if="previewVideo(attachment)"
										class="attachment-video"
										:src="attachment.url"
										controls
										@loadedmetadata="handleGeneratedVideoMetadata(attachment, $event)"
									/>
									<view v-else class="attachment-file" @click="openAttachment(attachment)">
										<uni-icons type="paperclip" size="20" color="#37d39a" />
										<text>{{ attachment.fileName }}</text>
									</view>
								</view>
							</view>
						</view>
						<view class="message-block assistant-message">
							<view class="assistant-label"><text>AI</text><text v-if="message.stopped" class="stopped-label">已停止</text></view>
							<view v-if="modelActivityText(message)" class="model-activity" role="status">
								<view class="model-activity-dot"></view>
								<text>{{ modelActivityText(message) }}</text>
							</view>
							<button
								v-if="researchDetailsAvailable(message)"
								class="research-toggle"
								type="button"
								:aria-expanded="String(Boolean(message.researchExpanded))"
								@click="toggleResearch(message)"
							>
								<text>查看研究过程 · {{ researchSources(message).length }} 个来源</text>
								<uni-icons :type="message.researchExpanded ? 'up' : 'down'" size="14" color="#8fdcbe" />
							</button>
							<view v-if="message.researchExpanded && researchDetailsAvailable(message)" class="research-panel">
								<view v-for="item in researchTimelineItems(message)" :key="researchTimelineKey(item)" class="research-row">
									<text class="research-time">{{ researchTime(item.type === 'source' ? item.source.occurredAt : item.activity.occurredAt) }}</text>
									<view class="research-activity-content">
										<template v-if="item.type === 'source'">
											<text>已检索 ·</text>
											<user-source-chip :source="item.source" variant="activity" />
										</template>
										<template v-else>
											<text>{{ item.label }}</text>
											<user-source-chip
												v-if="item.sourcePresentation"
												:source="item.sourcePresentation.source"
												:domain="item.sourcePresentation.domain"
												:disabled="!item.sourcePresentation.clickable"
												variant="activity"
											/>
										</template>
									</view>
								</view>
								<view v-if="message.research.reasoningSummaries.length" class="research-summary">
									<text class="research-section-label">推理摘要</text>
									<user-markdown-message
										:text="researchSummaryMarkdown(message)"
										:message-key="`${message.localId || message.messagePublicId || ''}:research-summary`"
										:sources="researchSources(message)"
										compact
									/>
								</view>
								<view v-if="researchSources(message).length" class="research-sources">
									<text class="research-section-label">已检索来源</text>
									<user-source-chip
										v-for="source in researchSources(message)"
										:key="`${source.role}-${source.url}`"
										:source="source"
										variant="card"
									/>
								</view>
							</view>
							<user-markdown-message
								v-if="message.responseText"
								:text="message.responseText"
								:streaming="Boolean(message.streaming)"
								:message-key="message.localId || message.messagePublicId || ''"
								:sources="researchSources(message)"
							/>
							<text v-else-if="message.streaming && !modelActivityText(message)" class="typing-indicator">正在生成…</text>
							<text v-if="message.saving" class="saving-indicator">正在保存生成内容…</text>
							<view v-if="message.responseAttachments?.length" class="attachment-grid">
								<view
									v-for="attachment in message.responseAttachments"
									:key="attachment.attachmentId"
									class="attachment-card"
									:class="{ 'is-video': previewVideo(attachment) }"
								>
									<view
										class="attachment-media-frame"
										:class="{ 'is-video': previewVideo(attachment) }"
										:style="previewVideo(attachment) ? generatedVideoCardStyle(attachment, message.videoMetadata) : null"
									>
									<view
										v-if="attachment.imageSlot && !attachment.url"
										class="image-output-slot"
										:class="`is-${String(attachment.status || 'QUEUED').toLowerCase()}`"
										role="status"
									>
										<uni-icons :type="attachment.status === 'FAILED' ? 'info' : 'image'" size="24" :color="attachment.status === 'FAILED' ? '#ff9b94' : '#8fdcbe'" aria-hidden="true" />
										<text>图片 {{ Number(attachment.outputIndex) + 1 }}</text>
										<text>{{ imageOutputStatusLabel(attachment) }}</text>
									</view>
									<image
										v-else-if="previewImage(attachment)"
										class="attachment-image generated-response-image"
										:src="attachment.url"
										:style="generatedResponseImageStyle(attachment)"
										@load="handleGeneratedResponseImageLoad(attachment, $event)"
										mode="widthFix"
									/>
									<video
										v-else-if="previewVideo(attachment)"
										class="attachment-video"
										:src="attachment.url"
										controls
										@loadedmetadata="handleGeneratedVideoMetadata(attachment, $event)"
									/>
									<button v-else class="attachment-file" type="button" :disabled="attachment.state !== 'AVAILABLE'" @click="openAttachment(attachment)">
										<uni-icons type="download" size="20" color="#37d39a" />
										<text>{{ attachment.state === 'AVAILABLE' ? attachment.fileName : '生成内容保存失败' }}</text>
									</button>
									<text v-if="attachment.volatilePreview && attachment.url" class="image-preview-state">
										{{ attachment.phase === 'FINAL' ? '最终图片正在保存到 OSS…' : '生成中的完整预览，仅最终图片会保存' }}
									</text>
									</view>
									<user-media-upload-progress
										v-if="mediaUploadProgressForAttachment(message, attachment)"
										:progress="mediaUploadProgressForAttachment(message, attachment)"
										@dismiss="dismissMediaUploadProgress(message, mediaUploadProgressForAttachment(message, attachment))"
									/>
								</view>
							</view>
							<view v-if="videoUploadProgress(message) && !hasRenderedVideo(message)" class="attachment-grid media-upload-pending-grid">
								<view class="attachment-card is-video media-upload-pending-card">
									<view class="attachment-media-frame is-video media-upload-video-placeholder" role="status">
										<uni-icons type="videocam" size="28" color="#8fdcbe" aria-hidden="true" />
										<text>视频正在保存到 OSS</text>
									</view>
									<user-media-upload-progress
										:progress="videoUploadProgress(message)"
										@dismiss="dismissMediaUploadProgress(message, videoUploadProgress(message))"
									/>
								</view>
							</view>
							<text v-if="message.imageOutputSummary" class="image-output-summary" role="status">{{ message.imageOutputSummary }}</text>
							<text
								v-if="message.warnings?.includes('ATTACHMENT_STORAGE_PARTIAL')"
								class="message-warning"
								role="status"
							>
								部分附件未能保存，模型用量仍已按实际结果结算。
							</text>
							<text v-if="message.error" class="message-error" role="alert">{{ message.error }}</text>
						</view>
					</view>
					<view id="message-bottom" class="message-bottom"></view>
					</view>
				</scroll-view>
			</view>

			<view class="composer-wrap">
				<user-chat-attachment-list
					:attachments="pendingAttachments"
					:model="selectedModel"
					:image-editing="imageGenerationAvailable && !videoGenerationAvailable && pendingAttachments.length > 0"
					:media-operation="videoGenerationAvailable"
					:video-mode="selectedVideoMode"
					:generating="generating"
					@remove="removePending"
					@retry="retryAttachment"
				/>
				<view class="composer">
					<button class="composer-icon" type="button" aria-label="添加附件" :disabled="generating || voiceInteractionActive || attachmentPickerBusy || pendingAttachments.length >= 8" @click="chooseAttachments">
						<uni-icons type="plusempty" size="24" color="#dce5e0" aria-hidden="true" />
					</button>
					<textarea v-model="draft" class="composer-input" auto-height :maxlength="65536" placeholder="输入消息" aria-label="聊天消息" :disabled="generating || voiceInteractionActive" @confirm="send" />
					<button
						class="voice-button"
						:class="{ 'is-recording': voiceRecording, 'is-finalizing': voiceFinalizing }"
						type="button"
						:aria-label="voiceButtonLabel"
						:aria-pressed="String(voiceRecording)"
						:disabled="voiceButtonDisabled"
						@click="toggleVoiceInput"
					>
						<uni-icons :type="voiceRecording ? 'micoff-filled' : 'mic-filled'" size="21" :color="voiceRecording ? '#fff4f2' : '#dce5e0'" aria-hidden="true" />
					</button>
					<button v-if="generating" class="send-button stop-button" type="button" aria-label="停止生成" @click="stop">
						<view class="stop-square"></view>
					</button>
					<button v-else class="send-button" type="button" aria-label="发送消息" :disabled="!canSend" @click="send">
						<uni-icons type="arrow-up" size="22" color="#07110d" aria-hidden="true" />
					</button>
				</view>
				<view v-if="voiceInteractionActive || voicePartialText" class="voice-status" :class="{ 'is-finalizing': voiceFinalizing, 'is-queued': voiceQueued }" :aria-busy="String(voiceInteractionActive)">
					<view class="voice-status-heading">
						<view class="voice-status-dot" aria-hidden="true"></view>
						<text>{{ voiceStatusLabel }}</text>
						<text v-if="voiceRecording" class="voice-duration">{{ voiceDurationLabel }}</text>
						<button
							v-if="voiceQueued"
							class="voice-queue-cancel"
							type="button"
							aria-label="取消语音识别排队"
							@click="cancelVoiceQueue"
						>取消排队</button>
					</view>
					<text v-if="voicePartialText" class="voice-preview">{{ voicePartialText }}</text>
				</view>
				<view class="composer-meta">
					<view class="composer-controls">
						<picker :range="models" range-key="modelName" :value="selectedModelIndex" :disabled="generating || !models.length" @change="selectModel">
							<view class="model-picker"><text>{{ selectedModel?.modelName || '选择模型' }}</text><uni-icons type="down" size="14" color="#9ba6a0" /></view>
						</picker>
						<view
							v-if="contextUsage"
							class="context-usage"
							:class="`is-${contextUsageTone}`"
							role="status"
						>
							<view class="context-usage-copy">
								<text>{{ contextUsageLabel }}</text>
								<text v-if="contextCompactionActive" class="context-usage-status">正在压缩上下文</text>
								<text v-else-if="contextUsage.compactionStatus === 'FAILED'" class="context-usage-status">压缩失败</text>
							</view>
							<view
								class="context-usage-track"
								role="progressbar"
								aria-label="上下文用量"
								aria-valuemin="0"
								aria-valuemax="100"
								:aria-valuenow="String(contextUsageProgress)"
							>
								<view class="context-usage-fill" :style="{ width: `${contextUsageProgress}%` }"></view>
							</view>
						</view>
						<button
							v-if="multipleImageOutputsAvailable"
							ref="imageOutputCountTrigger"
							class="image-count-picker"
							type="button"
							:disabled="generating"
							aria-haspopup="dialog"
							@click="openImageOutputCountDialog"
						>
							<text>数量 · {{ selectedImageOutputCount }}</text>
						</button>
						<picker
							v-if="videoGenerationAvailable"
							:range="videoModeOptions"
							range-key="label"
							:value="selectedVideoModeIndex"
							:disabled="generating"
							@change="selectVideoMode"
						>
							<view class="video-option-picker"><text>模式 · {{ selectedVideoModeLabel }}</text><uni-icons type="down" size="14" color="#9bc8ec" /></view>
						</picker>
						<picker
							v-if="videoGenerationAvailable && videoDurationOptions.length"
							:range="videoDurationOptions"
							range-key="label"
							:value="selectedVideoDurationIndex"
							:disabled="generating"
							@change="selectVideoDuration"
						>
							<view class="video-option-picker"><text>时长 · {{ selectedVideoDuration }} 秒</text><uni-icons type="down" size="14" color="#9bc8ec" /></view>
						</picker>
						<picker
							v-if="videoGenerationAvailable && videoResolutionOptions.length"
							:range="videoResolutionOptions"
							range-key="label"
							:value="selectedVideoResolutionIndex"
							:disabled="generating"
							@change="selectVideoResolution"
						>
							<view class="video-option-picker"><text>清晰度 · {{ selectedVideoResolutionLabel }}</text><uni-icons type="down" size="14" color="#9bc8ec" /></view>
						</picker>
						<picker
							v-if="videoGenerationAvailable && videoAspectOptions.length"
							:range="videoAspectOptions"
							range-key="label"
							:value="selectedVideoAspectIndex"
							:disabled="generating"
							@change="selectVideoAspect"
						>
							<view class="video-option-picker"><text>画幅 · {{ selectedVideoAspectLabel }}</text><uni-icons type="down" size="14" color="#9bc8ec" /></view>
						</picker>
						<picker
							v-if="!videoGenerationAvailable"
							:range="reasoningEffortOptions"
							range-key="label"
							:value="selectedReasoningEffortIndex"
							:disabled="generating || !selectedModel"
							@change="selectReasoningEffort"
						>
							<view class="reasoning-effort-picker">
								<text>{{ profileControlLabel }} · {{ selectedReasoningEffortLabel }}</text>
								<uni-icons type="down" size="14" color="#9ba6a0" />
							</view>
						</picker>
						<picker
							v-if="imageGenerationAvailable"
							:range="imageAspectOptions"
							range-key="label"
							:value="selectedImageAspectIndex"
							:disabled="generating"
							@change="selectImageAspect"
						>
							<view class="image-aspect-picker">
								<text>画幅 · {{ selectedImageAspectLabel }}</text>
								<uni-icons type="down" size="14" color="#9ba6a0" />
							</view>
						</picker>
						<picker
							v-if="webSearchAvailable"
							:range="webSearchOptions"
							range-key="label"
							:value="selectedWebSearchModeIndex"
							:disabled="generating"
							@change="selectWebSearchMode"
						>
							<view
								class="web-search-toggle"
								:class="{ 'is-active': webSearchActive }"
							>
								<text>{{ selectedWebSearchModeLabel }}</text>
								<uni-icons type="down" size="14" color="#9bc8ec" />
							</view>
						</picker>
					</view>
					<text class="composer-note">模型可能会出错，请核查重要信息。</text>
				</view>
				<text v-if="pendingAttachments.length && !canSend" class="composer-blocker" role="status">{{ sendBlockedReason }}</text>
				<text v-if="composerError" class="composer-error" role="alert">{{ composerError }}</text>
				<text class="visually-hidden" role="status" aria-live="polite">{{ voiceAnnouncement }}</text>
			</view>
			<user-image-output-count-dialog
				ref="imageOutputCountDialog"
				@confirm="selectImageOutputCount"
				@close="restoreImageOutputCountFocus"
			/>
		</view>
</template>

<script>
	import { markRaw } from 'vue'
	import { aiModelApi } from '@/common/aimodel/ai-model-api.js'
	import { clientPlatform } from '@/common/auth/config.js'
	import {
		aiConversationApi,
		normalizeAiConversationContextUsage
	} from '@/common/aichat/ai-conversation-api.js'
	import { openAiConversationContextStream } from '@/common/aichat/ai-conversation-context-stream.js'
	import {
		formatAiConversationContextPercent,
		formatAiConversationContextTokens,
		recalculateAiConversationContextUsage
	} from '@/common/aichat/ai-conversation-context-usage.js'
	import { aiConversationErrorMessage } from '@/common/aichat/ai-conversation-error-presentation.js'
	import { chooseConversationFiles } from '@/common/aichat/ai-conversation-file-picker.js'
	import {
		openAiConversationGenerationStream,
		openAiConversationStream
	} from '@/common/aichat/ai-conversation-stream.js'
	import {
		asyncGenerationEnabled,
		getGeneration,
		listActiveGenerations,
		listPendingGenerationRequests,
		registerPendingGeneration,
		registerGeneration,
		subscribeGeneration,
		updateGeneration
	} from '@/common/aichat/ai-conversation-generation-manager.js'
	import { createAiConversationTextDrain } from '@/common/aichat/ai-conversation-text-drain.js'
	import { createAiMarkdownRenderState } from '@/common/aichat/ai-markdown-render-state.js'
	import { prewarmAiCodeHighlighter } from '@/common/aichat/ai-code-highlighter.js'
	import {
		findAiConversationStoppedDraft,
		removeAiConversationStoppedDraft,
		saveAiConversationStoppedDraft
	} from '@/common/aichat/ai-conversation-stopped-draft.js'
	import { createAiConversationStreamDiagnostics } from '@/common/aichat/ai-conversation-stream-diagnostics.js'
	import { reportAiConversationStreamDiagnostics } from '@/common/aichat/ai-conversation-stream-diagnostics-reporter.js'
	import { createAiConversationLifecycleDiagnostics } from '@/common/aichat/ai-conversation-lifecycle-diagnostics.js'
	import {
		imageGenerationProfileLevels,
		imageGenerationRequest,
		imagePreviewAttachment,
		createImageOutputSlots,
		failImageOutputAttachment,
		mergeCompletedImageOutputs,
		mergeImagePreviewOutput,
		mergePersistedImageOutput,
		modelSupportsImageGeneration,
		modelSupportsMultipleImageOutputs,
		normalizeImageGenerationAspect,
		normalizeImageOutputCount,
		persistedImageAttachments,
		persistedImageOutputAttachment,
		supportedImageAspectOptions,
		upsertImageOutputAttachment
	} from '@/common/aichat/ai-conversation-image-generation.js'
	import {
		initialVideoUploadProgress,
		mediaUploadProgressKey,
		mergeMediaUploadProgress,
		removeMediaUploadProgress
	} from '@/common/aichat/ai-conversation-media-upload-progress.js'
	import {
		modelSupportsVideoGeneration,
		normalizeVideoDuration,
		normalizeVideoMode,
		supportedVideoAspectOptions,
		supportedVideoDurationOptions,
		supportedVideoModeOptions,
		supportedVideoResolutionOptions,
		videoGenerationRequest,
		videoSendGate
	} from '@/common/aichat/ai-conversation-video-generation.js'
	import { preloadConversationImage } from '@/common/aichat/ai-conversation-image-preloader.js'
	import {
		createAiConversationResearchSession,
		findAiConversationResearchSession
	} from '@/common/aichat/ai-conversation-research-session.js'
	import {
		formatAiReasoningSummaryMarkdown,
		presentAiResearchTimeline
	} from '@/common/aichat/ai-conversation-research-presentation.js'
	import { mergeAiConversationSources } from '@/common/aichat/ai-conversation-source-presentation.js'
	import {
		AI_CONVERSATION_WEB_SEARCH_MODES,
		AI_CONVERSATION_WEB_SEARCH_OPTIONS,
		aiConversationWebSearchEnabled,
		modelSupportsAiConversationWebSearch,
		normalizeAiConversationWebSearchMode
	} from '@/common/aichat/ai-conversation-web-search.js'
	import { aiConversationModelLevelOptions } from '@/common/aichat/ai-conversation-model-levels.js'
	import {
		centerTurnWindow,
		createInitialTurnWindow,
		createTurnNavigationItem,
		messageTurnElementId,
		messageTurnKey,
		resolveTurnScrollElement,
		restoreAnchoredScrollTop,
		shiftTurnWindow,
		windowAfterPrepend
	} from '@/common/aichat/ai-conversation-turn-navigation.js'
	import { uploadConversationFiles } from '@/common/aichat/ai-conversation-upload.js'
	import { createVoiceRecorder } from '@/common/voice/voice-recorder.js'
	import {
		createVoiceWebSocketSession,
		voiceErrorMessage
	} from '@/common/voice/voice-websocket-session.js'
	import { issueVoiceSessionTicket } from '@/common/voice/voice-ticket-api.js'
	import {
		ATTACHMENT_UPLOAD_STATES,
		attachmentCategory,
		createPendingAttachment,
		deriveSendGate,
		validateAttachmentSelection
	} from '@/common/aichat/ai-conversation-upload-state.js'
	import UserChatAttachmentList from './user-chat-attachment-list.vue'
	import UserConversationTurnRail from './user-conversation-turn-rail.vue'
	import UserImageOutputCountDialog from './user-image-output-count-dialog.vue'
	import UserMarkdownMessage from './user-markdown-message.vue'
	import UserMediaUploadProgress from './user-media-upload-progress.vue'
	import UserSourceChip from './user-source-chip.vue'
	import {
		appendLocalMessage,
		clearAiConversationHistoryStale,
		discardTransientMessages,
		markAiConversationHistoryStale,
		patchLocalMessage,
		patchMessage,
		readAiConversationStore,
		resetCurrentConversation,
		selectConversation,
		setAcceptedConversation,
		setConversationError,
		setConversationLoading,
		setConversationPage,
		setMessagePage,
		setMessagesLoading
	} from '@/common/aichat/ai-conversation-store.js'

	const RESEARCH_ACTIVITY_STATUS_LABELS = Object.freeze({
		STARTED: '已开始',
		IN_PROGRESS: '进行中',
		COMPLETED: '已完成',
		FAILED: '失败',
		UNAVAILABLE: '不可用'
	})

	const MODEL_STORAGE_KEY = 'ait.user.ai.selected-model.v1'
	const REASONING_EFFORT_STORAGE_KEY = 'ait.user.ai.reasoning-effort.v1'
	const IMAGE_ASPECT_STORAGE_KEY = 'ait.user.ai.image-aspect.v1'
	const IMAGE_OUTPUT_COUNT_STORAGE_KEY = 'ait.user.ai.image-output-count.v1'
	const GENERATED_RESPONSE_IMAGE_MAX_WIDTH_PX = 720
	const GENERATED_RESPONSE_IMAGE_MAX_HEIGHT_PX = 1080
	const GENERATED_RESPONSE_IMAGE_VIEWPORT_HEIGHT_RATIO = 0.7
	const GENERATED_VIDEO_MAX_WIDTH_PX = 720
	const GENERATED_VIDEO_MAX_HEIGHT_PX = 1080
	const GENERATED_VIDEO_VIEWPORT_HEIGHT_RATIO = 0.68
	const GENERATED_VIDEO_FALLBACK_SIZE = Object.freeze({ width: 1280, height: 720 })
	const CANCEL_RETRY_DELAYS = Object.freeze([0, 250, 750])
	const TURN_WINDOW_SIZE = 50
	const TURN_WINDOW_SHIFT = 25
	const TURN_WINDOW_EDGE_ENTER_PX = 96
	const TURN_WINDOW_EDGE_RELEASE_PX = 180
	const TURN_FOLLOW_LATEST_PX = 320
	const VOICE_ACTIVE_STATES = Object.freeze([
		'REQUESTING_PERMISSION',
		'ISSUING_TICKET',
		'CONNECTING',
		'QUEUED',
		'RECORDING',
		'FINALIZING'
	])

	function appendTranscriptToDraft(draft, transcript) {
		const existing = String(draft || '')
		const text = String(transcript || '').trim()
		if (!text) return existing
		if (!existing) return text
		if (/\s$/.test(existing) || /^[,.;:!?，。；：！？]/.test(text)) return existing + text
		const previous = existing[existing.length - 1]
		const first = text[0]
		const bothCjk = /[\u3400-\u9fff]/.test(previous) && /[\u3400-\u9fff]/.test(first)
		return `${existing}${bothCjk ? '' : ' '}${text}`
	}

	function positiveFiniteNumber(value) {
		const number = Number(value)
		return Number.isFinite(number) && number > 0 ? number : null
	}

	function generatedResponseImageMaximumHeight(viewportHeight) {
		const validViewportHeight = positiveFiniteNumber(viewportHeight)
		return validViewportHeight == null
			? GENERATED_RESPONSE_IMAGE_MAX_HEIGHT_PX
			: Math.min(
				GENERATED_RESPONSE_IMAGE_MAX_HEIGHT_PX,
				validViewportHeight * GENERATED_RESPONSE_IMAGE_VIEWPORT_HEIGHT_RATIO
			)
	}

	function generatedResponseImageDisplayWidth(naturalSize, viewportHeight) {
		const naturalWidth = positiveFiniteNumber(naturalSize?.width)
		const naturalHeight = positiveFiniteNumber(naturalSize?.height)
		if (naturalWidth == null || naturalHeight == null) return null

		const maximumHeight = generatedResponseImageMaximumHeight(viewportHeight)
		const scale = Math.min(
			1,
			GENERATED_RESPONSE_IMAGE_MAX_WIDTH_PX / naturalWidth,
			maximumHeight / naturalHeight
		)
		return Math.max(1, Math.floor(naturalWidth * scale))
	}

	function generatedVideoDisplaySize(naturalSize, viewportHeight) {
		const naturalWidth = positiveFiniteNumber(naturalSize?.width)
			?? GENERATED_VIDEO_FALLBACK_SIZE.width
		const naturalHeight = positiveFiniteNumber(naturalSize?.height)
			?? GENERATED_VIDEO_FALLBACK_SIZE.height
		const validViewportHeight = positiveFiniteNumber(viewportHeight)
		const maximumHeight = validViewportHeight == null
			? GENERATED_VIDEO_MAX_HEIGHT_PX
			: Math.min(
				GENERATED_VIDEO_MAX_HEIGHT_PX,
				validViewportHeight * GENERATED_VIDEO_VIEWPORT_HEIGHT_RATIO
			)
		const scale = Math.min(
			1,
			GENERATED_VIDEO_MAX_WIDTH_PX / naturalWidth,
			maximumHeight / naturalHeight
		)
		return Object.freeze({
			width: Math.max(1, Math.floor(naturalWidth * scale)),
			naturalWidth,
			naturalHeight
		})
	}

	function currentWindowHeight() {
		try {
			const windowHeight = positiveFiniteNumber(uni.getWindowInfo?.()?.windowHeight)
			if (windowHeight != null) return windowHeight
		} catch (_) {}
		try {
			return positiveFiniteNumber(uni.getSystemInfoSync?.()?.windowHeight)
		} catch (_) {
			return null
		}
	}

	function uuidV4() {
		if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
		const bytes = new Uint8Array(16)
		if (globalThis.crypto?.getRandomValues) globalThis.crypto.getRandomValues(bytes)
		else for (let index = 0; index < bytes.length; index++) bytes[index] = Math.floor(Math.random() * 256)
		bytes[6] = (bytes[6] & 0x0f) | 0x40
		bytes[8] = (bytes[8] & 0x3f) | 0x80
		const hex = [...bytes].map(value => value.toString(16).padStart(2, '0')).join('')
		return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
	}

	function retryableCancellationError(error) {
		return error?.code === 'NETWORK_ERROR'
			|| error?.retryable === true
			|| error?.statusCode === 429
			|| Number(error?.statusCode) >= 500
	}

	async function cancelGenerationWithRetry(generationPublicId) {
		let lastError = null
		for (const delay of CANCEL_RETRY_DELAYS) {
			if (delay > 0) {
				await new Promise(resolve => setTimeout(resolve, delay))
			}
			try {
				return await aiConversationApi.cancelGeneration(generationPublicId)
			} catch (error) {
				lastError = error
				if (!retryableCancellationError(error)) break
			}
		}
		throw lastError || new Error('Cancellation request failed.')
	}

	async function cancelDirectResponseWithRetry(idempotencyKey) {
		let lastError = null
		for (const delay of CANCEL_RETRY_DELAYS) {
			if (delay > 0) await new Promise(resolve => setTimeout(resolve, delay))
			try {
				return await aiConversationApi.cancelResponse(idempotencyKey)
			} catch (error) {
				lastError = error
				if (!retryableCancellationError(error)) break
			}
		}
		throw lastError || new Error('Cancellation request failed.')
	}

	export default {
		components: { UserChatAttachmentList, UserConversationTurnRail, UserImageOutputCountDialog, UserMarkdownMessage, UserMediaUploadProgress, UserSourceChip },
		data() {
			return {
				...readAiConversationStore(),
				draft: '',
				models: [],
				selectedModelPublicId: '',
				contextUsage: null,
				contextEventRevision: 0,
				contextObserver: null,
				contextObserverConversationPublicId: '',
				contextObserverModelPublicId: '',
				contextObserverEpoch: 0,
				selectedReasoningEffortLevel: 2,
				selectedImageAspect: 'SQUARE',
				selectedImageOutputCount: 1,
				selectedVideoMode: '',
				selectedVideoDuration: 5,
				selectedVideoResolution: 'P720',
				selectedVideoAspect: 'RATIO_16_9',
				selectedWebSearchMode: AI_CONVERSATION_WEB_SEARCH_MODES.OFF,
				pendingAttachments: [],
				attachmentPickerBusy: false,
				localPreviewUrls: new Map(),
				imageUpgradeTokens: markRaw(new Map()),
				generatedResponseImageNaturalSizes: {},
				generatedVideoNaturalSizes: {},
				generatedResponseImageViewportHeight: null,
				generatedResponseImageResizeListener: null,
				generating: false,
				activeStream: null,
				transportCancelRequested: false,
				activeGenerationPublicId: '',
				activeGenerationSubscription: null,
				cancelRequestedBeforeGenerationId: false,
				generationCancelDispatching: false,
				generationCancelSentFor: '',
				textDrain: null,
				markdownRenderState: null,
				streamDiagnostics: null,
				lifecycleDiagnostics: null,
				activeResearchSession: null,
				terminalPresentationPending: false,
				activeLocalId: '',
				activeIdempotencyKey: '',
				composerError: '',
				voiceState: 'IDLE',
				voicePartialText: '',
				voiceElapsedMs: 0,
				voiceMaximumDurationMs: 300000,
				voiceLimitReached: false,
				voiceQueuePosition: 0,
				voiceQueueCapacity: 5,
				voiceAnnouncement: '',
				voiceSession: null,
				voiceRecorder: null,
				voiceTimer: null,
				voiceStartedAt: 0,
				scrollTarget: '',
				historyResyncing: false,
				modelsLoading: false,
				turnNavigationDesktop: false,
				renderWindow: { start: 0, end: 0 },
				activeTurnKey: '',
				turnScrollTop: 0,
				turnViewportHeight: 0,
				turnScrollFrame: null,
				turnWindowMoving: false,
				turnWindowEdgeLock: '',
				turnNavigationReady: false,
				turnFollowLatest: true,
				olderMessagesError: ''
			}
		},
		mounted() {
			void prewarmAiCodeHighlighter().catch(() => {})
			this.refreshGeneratedResponseImageViewportHeight()
			this.refreshTurnNavigationViewport()
			if (typeof uni.onWindowResize === 'function') {
				this.generatedResponseImageResizeListener = event =>
					this.handleGeneratedResponseImageWindowResize(event)
				uni.onWindowResize(this.generatedResponseImageResizeListener)
			}
		},
		beforeUnmount() {
			void this.cancelVoiceInput('COMPONENT_UNMOUNT')
			this.clearCompletedImageUpgrades()
			this.closeContextObserver()
			this.releaseTurnNavigationFrame()
			if (this.generatedResponseImageResizeListener
				&& typeof uni.offWindowResize === 'function') {
				uni.offWindowResize(this.generatedResponseImageResizeListener)
			}
			this.generatedResponseImageResizeListener = null
			const current = this.messages.find(message =>
				message.localId === this.activeLocalId)
			if (asyncGenerationEnabled()) {
				this.activeGenerationSubscription?.()
			} else {
				this.activeStream?.close?.('COMPONENT_UNMOUNT', {
					hasVisibleOutput: Boolean(current?.responseText),
					emittedTextCharacters: String(current?.responseText || '').length
				})
			}
			this.textDrain?.close?.()
			this.markdownRenderState?.close?.()
			this.streamDiagnostics?.finish?.('UNMOUNT')
			this.lifecycleDiagnostics?.finish?.('UNMOUNT')
			this.activeResearchSession?.close?.()
			this.cancelPendingUploads()
			this.releasePreviewUrls(this.pendingAttachments.map(file => file.path))
			this.releaseAllLocalPreviews()
		},
		computed: {
			selectedModel() { return this.models.find(model => model.publicId === this.selectedModelPublicId) || null },
			selectedModelIndex() { return Math.max(0, this.models.findIndex(model => model.publicId === this.selectedModelPublicId)) },
			imageGenerationAvailable() {
				return modelSupportsImageGeneration(this.selectedModel)
			},
			videoGenerationAvailable() {
				return modelSupportsVideoGeneration(this.selectedModel)
			},
			videoModeOptions() {
				return supportedVideoModeOptions(this.selectedModel)
			},
			selectedVideoModeIndex() {
				return Math.max(0, this.videoModeOptions.findIndex(option =>
					option.value === this.selectedVideoMode))
			},
			selectedVideoModeLabel() {
				return this.videoModeOptions.find(option =>
					option.value === this.selectedVideoMode)?.label || '请选择'
			},
			videoDurationOptions() {
				return supportedVideoDurationOptions(
					this.selectedModel, this.selectedVideoMode)
			},
			selectedVideoDurationIndex() {
				return Math.max(0, this.videoDurationOptions.findIndex(option =>
					option.value === this.selectedVideoDuration))
			},
			videoResolutionOptions() {
				return supportedVideoResolutionOptions(
					this.selectedModel, this.selectedVideoMode)
			},
			selectedVideoResolutionIndex() {
				return Math.max(0, this.videoResolutionOptions.findIndex(option =>
					option.value === this.selectedVideoResolution))
			},
			selectedVideoResolutionLabel() {
				return this.videoResolutionOptions.find(option =>
					option.value === this.selectedVideoResolution)?.label || '请选择'
			},
			videoAspectOptions() {
				return supportedVideoAspectOptions(
					this.selectedModel, this.selectedVideoMode)
			},
			selectedVideoAspectIndex() {
				return Math.max(0, this.videoAspectOptions.findIndex(option =>
					option.value === this.selectedVideoAspect))
			},
			selectedVideoAspectLabel() {
				return this.videoAspectOptions.find(option =>
					option.value === this.selectedVideoAspect)?.label || '请选择'
			},
			multipleImageOutputsAvailable() {
				return modelSupportsMultipleImageOutputs(this.selectedModel)
			},
			imageAspectOptions() {
				return supportedImageAspectOptions(this.selectedModel)
			},
			selectedImageAspectIndex() {
				return Math.max(0, this.imageAspectOptions.findIndex(option =>
					option.value === this.selectedImageAspect))
			},
			selectedImageAspectLabel() {
				return this.imageAspectOptions.find(option =>
					option.value === this.selectedImageAspect)?.label || '正方形'
			},
			profileControlLabel() {
				return this.imageGenerationAvailable ? '画质' : '推理'
			},
			reasoningEffortOptions() {
				return aiConversationModelLevelOptions(
					this.selectedModel,
					this.imageGenerationAvailable)
			},
			selectedReasoningEffortIndex() {
				return Math.max(0, this.reasoningEffortOptions.findIndex(option =>
					option.value === this.selectedReasoningEffortLevel))
			},
			selectedReasoningEffortLabel() {
				return this.reasoningEffortOptions.find(option =>
					option.value === this.selectedReasoningEffortLevel)?.label || 'Medium'
			},
			webSearchAvailable() {
				return !this.videoGenerationAvailable
					&& modelSupportsAiConversationWebSearch(this.selectedModel)
			},
			webSearchOptions() {
				return AI_CONVERSATION_WEB_SEARCH_OPTIONS
			},
			selectedWebSearchModeIndex() {
				return Math.max(0, this.webSearchOptions.findIndex(option =>
					option.value === this.selectedWebSearchMode))
			},
			selectedWebSearchModeLabel() {
				return this.webSearchOptions.find(option =>
					option.value === this.selectedWebSearchMode)?.label || '联网 · 关闭'
			},
			webSearchActive() {
				return this.selectedWebSearchMode !== AI_CONVERSATION_WEB_SEARCH_MODES.OFF
			},
			sendGate() {
				if (this.videoGenerationAvailable) {
					const uploadGate = deriveSendGate({
						model: this.selectedModel,
						text: this.draft,
						attachments: this.pendingAttachments,
						generating: this.generating,
						mediaOperation: true
					})
					if (!uploadGate.allowed) return uploadGate
					return videoSendGate({
						model: this.selectedModel,
						mode: this.selectedVideoMode,
						text: this.draft,
						attachments: this.pendingAttachments
					})
				}
				if (this.imageGenerationAvailable && !String(this.draft || '').trim()) {
					return Object.freeze({ allowed: false, reason: '请输入图片生成提示词。' })
				}
				return deriveSendGate({
					model: this.selectedModel,
					text: this.draft,
					attachments: this.pendingAttachments,
					generating: this.generating,
					imageEditing: this.imageGenerationAvailable
						&& this.pendingAttachments.length > 0
				})
			},
			canSend() { return this.sendGate.allowed && !this.voiceInteractionActive },
			sendBlockedReason() {
				return this.voiceInteractionActive ? '请先结束当前语音输入。' : this.sendGate.reason
			},
			voiceInteractionActive() { return VOICE_ACTIVE_STATES.includes(this.voiceState) },
			voiceRecording() { return this.voiceState === 'RECORDING' },
			voiceQueued() { return this.voiceState === 'QUEUED' },
			voiceFinalizing() { return this.voiceState === 'FINALIZING' },
			voiceButtonDisabled() {
				return this.generating || !['IDLE', 'ERROR', 'RECORDING'].includes(this.voiceState)
			},
			voiceButtonLabel() {
				return this.voiceRecording ? '结束语音输入' : '开始语音输入'
			},
			voiceStatusLabel() {
				const labels = {
					REQUESTING_PERMISSION: '正在请求麦克风权限',
					ISSUING_TICKET: '正在准备安全语音连接',
					CONNECTING: '正在连接本地语音识别',
					QUEUED: `正在排队，第 ${this.voiceQueuePosition} / ${this.voiceQueueCapacity} 位`,
					RECORDING: '正在听写',
					FINALIZING: this.voiceLimitReached ? '已达到 5 分钟上限，正在生成最终文字' : '正在生成最终文字'
				}
				return labels[this.voiceState] || ''
			},
			voiceDurationLabel() {
				const seconds = Math.max(0, Math.floor(this.voiceElapsedMs / 1000))
				return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
			},
			contextCompactionActive() {
				return this.contextUsage?.compactionStatus === 'QUEUED'
					|| this.contextUsage?.compactionStatus === 'RUNNING'
			},
			contextUsagePercent() {
				return Math.max(0, Number(this.contextUsage?.usagePercent || 0))
			},
			contextUsageProgress() {
				return Math.min(100, this.contextUsagePercent)
			},
			contextUsageLabel() {
				if (!this.contextUsage) return ''
				return `${formatAiConversationContextTokens(this.contextUsage.estimatedContextTokens)} / ${formatAiConversationContextTokens(this.contextUsage.contextWindowTokens)} · ${formatAiConversationContextPercent(this.contextUsage.usagePercent)}%`
			},
			contextUsageTone() {
				if (this.contextUsage?.hardLimitExceeded
					|| this.contextUsage?.compactionStatus === 'FAILED') return 'danger'
				if (this.contextUsage?.thresholdReached
					|| this.contextCompactionActive) return 'warning'
				return 'healthy'
			},
			renderedMessages() {
				if (!this.turnNavigationDesktop) return this.messages
				return this.messages.slice(this.renderWindow.start, this.renderWindow.end)
			},
			visibleTurnItems() {
				if (!this.turnNavigationDesktop) return []
				return this.renderedMessages.map((message, index) =>
					createTurnNavigationItem(message, this.renderedMessageIndex(index)))
			},
			hasHiddenTurnsBefore() {
				return this.turnNavigationDesktop && this.renderWindow.start > 0
			},
			hasHiddenTurnsAfter() {
				return this.turnNavigationDesktop && this.renderWindow.end < this.messages.length
			},
			activeConversationTitle() {
				if (!this.currentConversationPublicId) return '新聊天'
				return this.conversations.find(item => item.conversationPublicId === this.currentConversationPublicId)?.title || '未命名对话'
			}
		},
		methods: {
			async toggleVoiceInput() {
				if (this.voiceRecording) {
					await this.finalizeVoiceInput(false)
					return
				}
				if (this.voiceState === 'IDLE' || this.voiceState === 'ERROR') {
					await this.startVoiceInput()
				}
			},
			async startVoiceInput() {
				if (this.generating || this.voiceInteractionActive) return
				this.composerError = ''
				this.voiceAnnouncement = ''
				this.voicePartialText = ''
				this.voiceLimitReached = false
				this.voiceQueuePosition = 0
				this.voiceQueueCapacity = 5
				this.voiceElapsedMs = 0
				const recorder = markRaw(createVoiceRecorder())
				this.voiceRecorder = recorder
				try {
					this.voiceState = 'REQUESTING_PERMISSION'
					await recorder.requestPermission()
					if (this.voiceRecorder !== recorder) {
						try { await recorder.destroy() } catch (_) {}
						return
					}
					this.voiceState = 'ISSUING_TICKET'
					const ticket = await issueVoiceSessionTicket()
					if (this.voiceRecorder !== recorder) {
						try { await recorder.destroy() } catch (_) {}
						return
					}
					this.voiceMaximumDurationMs = ticket.maxDurationMs
					this.voiceState = 'CONNECTING'
					let session = null
					session = markRaw(createVoiceWebSocketSession({
						language: 'auto',
						onEvent: event => {
							if (this.voiceSession === session) void this.handleVoiceEvent(event)
						},
						onError: error => {
							if (this.voiceSession === session) void this.handleVoiceFailure(error)
						}
					}))
					this.voiceSession = session
					await session.connect(ticket)
					if (this.voiceSession !== session || this.voiceRecorder !== recorder) {
						try { await recorder.destroy() } catch (_) {}
						try { await session.stop() } catch (_) {}
						return
					}
					this.voiceState = 'RECORDING'
					this.voiceQueuePosition = 0
					await recorder.start(frame => {
						if (this.voiceSession !== session
							|| this.voiceLimitReached
							|| !['RECORDING', 'FINALIZING'].includes(this.voiceState)) return
						void session.sendAudio(frame).catch(error => this.handleVoiceFailure(error))
					}, error => { void this.handleVoiceFailure(error) })
					this.voiceStartedAt = Date.now()
					this.startVoiceTimer()
				} catch (error) {
					await this.handleVoiceFailure(error)
				}
			},
			startVoiceTimer() {
				clearInterval(this.voiceTimer)
				this.voiceTimer = setInterval(() => {
					if (!this.voiceRecording) return
					this.voiceElapsedMs = Math.min(
						Date.now() - this.voiceStartedAt,
						this.voiceMaximumDurationMs)
					if (this.voiceElapsedMs >= this.voiceMaximumDurationMs) {
						void this.finalizeVoiceInput(true)
					}
				}, 250)
			},
			async finalizeVoiceInput(limitReached) {
				if (!this.voiceRecording) return
				this.voiceState = 'FINALIZING'
				this.voiceLimitReached = limitReached === true
				clearInterval(this.voiceTimer)
				this.voiceTimer = null
				const recorder = this.voiceRecorder
				this.voiceRecorder = null
				try {
					await recorder?.stop?.()
					await this.voiceSession?.commit?.()
				} catch (error) {
					await this.handleVoiceFailure(error)
				}
			},
			async handleVoiceEvent(event) {
				if (event?.type === 'session.queued') {
					this.voiceQueuePosition = Number(event.position)
					this.voiceQueueCapacity = Number(event.queueCapacity)
					this.voiceState = 'QUEUED'
					this.voiceAnnouncement = this.voiceStatusLabel
					return
				}
				if (event?.type === 'transcript.partial') {
					this.voicePartialText = String(event.text || '')
					return
				}
				if (event?.type === 'input.limit_reached') {
					this.voiceLimitReached = true
					this.voiceState = 'FINALIZING'
					clearInterval(this.voiceTimer)
					this.voiceTimer = null
					const recorder = this.voiceRecorder
					this.voiceRecorder = null
					await recorder?.stop?.()
					return
				}
				if (event?.type === 'transcript.final') {
					await this.acceptVoiceTranscript(event.text)
				}
			},
			async acceptVoiceTranscript(text) {
				const transcript = String(text || '').trim()
				clearInterval(this.voiceTimer)
				this.voiceTimer = null
				this.voicePartialText = ''
				if (transcript) {
					this.draft = appendTranscriptToDraft(this.draft, transcript)
					this.voiceAnnouncement = this.voiceLimitReached
						? '已达到 5 分钟上限，最终文字已加入输入框。'
						: '语音识别完成，最终文字已加入输入框。'
				} else {
					this.voiceAnnouncement = '未识别到有效语音。'
					uni.showToast?.({ title: '未识别到有效语音', icon: 'none' })
				}
				await this.releaseVoiceResources(false)
				this.voiceState = 'IDLE'
			},
			async handleVoiceFailure(error) {
				if (this.voiceState === 'ERROR' && !this.voiceSession && !this.voiceRecorder) return
				if (!this.voiceInteractionActive && this.voiceState !== 'ERROR') return
				const message = voiceErrorMessage(error)
				this.composerError = message
				this.voiceAnnouncement = message
				this.voicePartialText = ''
				this.voiceQueuePosition = 0
				await this.releaseVoiceResources(true)
				this.voiceState = 'ERROR'
			},
			async cancelVoiceInput() {
				if (!this.voiceInteractionActive && !this.voiceSession && !this.voiceRecorder) return
				await this.releaseVoiceResources(true)
				this.voicePartialText = ''
				this.voiceQueuePosition = 0
				this.voiceState = 'IDLE'
			},
			async cancelVoiceQueue() {
				if (!this.voiceQueued) return
				await this.releaseVoiceResources(true)
				this.voiceQueuePosition = 0
				this.voicePartialText = ''
				this.voiceAnnouncement = '已取消语音识别排队。'
				this.voiceState = 'IDLE'
			},
			async releaseVoiceResources(sendStop) {
				clearInterval(this.voiceTimer)
				this.voiceTimer = null
				const recorder = this.voiceRecorder
				const session = this.voiceSession
				this.voiceRecorder = null
				this.voiceSession = null
				try { await recorder?.destroy?.() } catch (_) {}
				if (sendStop) {
					try { await session?.stop?.() } catch (_) {}
				}
			},
			onAuthenticatedPageReady() {
				this.applyStore(readAiConversationStore())
				if (!this.models.length && !this.modelsLoading) this.loadModels()
				else if (this.currentConversationPublicId) {
					void this.refreshContextUsage()
				}
				if (asyncGenerationEnabled()) this.restoreActiveGenerations()
				else this.restoreStoppedDraftForCurrentConversation()
				this.restoreResearchForCurrentConversation()
			},
			applyStore(value) {
				Object.assign(this, value)
				this.$emit('conversation-state-change', value)
			},
			syncStore() {
				this.applyStore(readAiConversationStore())
			},
			closeContextObserver(resetRevision = false) {
				this.contextObserverEpoch += 1
				this.contextObserver?.close?.()
				this.contextObserver = null
				this.contextObserverConversationPublicId = ''
				this.contextObserverModelPublicId = ''
				if (resetRevision) this.contextEventRevision = 0
			},
			resetContextUsage() {
				this.closeContextObserver(true)
				this.contextUsage = null
			},
			applyContextUsage(value) {
				let usage
				try {
					usage = normalizeAiConversationContextUsage(value)
				} catch (_) {
					return false
				}
				if (usage.conversationPublicId !== this.currentConversationPublicId
					|| usage.modelPublicId !== this.selectedModelPublicId) return false
				this.contextUsage = usage
				return true
			},
			recalculateContextUsageForModel(model) {
				if (!this.contextUsage || !model
					|| this.contextUsage.conversationPublicId
						!== this.currentConversationPublicId) return
				this.contextUsage = recalculateAiConversationContextUsage(
					this.contextUsage, model)
			},
			async openContextObserver() {
				const conversationPublicId = this.currentConversationPublicId
				const modelPublicId = this.selectedModelPublicId
				if (!conversationPublicId || !modelPublicId) return null
				if (this.contextObserver
					&& this.contextObserverConversationPublicId === conversationPublicId
					&& this.contextObserverModelPublicId === modelPublicId) {
					return this.contextObserver
				}
				const previousConversationPublicId =
					this.contextObserverConversationPublicId
				this.closeContextObserver(Boolean(previousConversationPublicId)
					&& previousConversationPublicId !== conversationPublicId)
				const epoch = ++this.contextObserverEpoch
				this.contextObserverConversationPublicId = conversationPublicId
				this.contextObserverModelPublicId = modelPublicId
				try {
					const observer = await openAiConversationContextStream({
						conversationPublicId,
						modelPublicId,
						afterRevision: this.contextEventRevision
					}, {
						onEvent: event => this.handleContextEvent(
							conversationPublicId, modelPublicId, event)
					})
					if (epoch !== this.contextObserverEpoch
						|| conversationPublicId !== this.currentConversationPublicId
						|| modelPublicId !== this.selectedModelPublicId) {
						observer.close()
						return null
					}
					this.contextObserver = observer
					void observer.completed.catch(() => {
						if (epoch === this.contextObserverEpoch) this.contextUsage = null
					}).finally(() => {
						if (epoch === this.contextObserverEpoch) {
							this.contextObserver = null
							this.contextObserverConversationPublicId = ''
							this.contextObserverModelPublicId = ''
						}
					})
					return observer
				} catch (_) {
					if (epoch === this.contextObserverEpoch) {
						this.contextUsage = null
						this.contextObserverConversationPublicId = ''
						this.contextObserverModelPublicId = ''
					}
					return null
				}
			},
			handleContextEvent(conversationPublicId, modelPublicId, event) {
				if (conversationPublicId !== this.currentConversationPublicId
					|| modelPublicId !== this.selectedModelPublicId) return
				const eventRevision = Number(event.data?.eventRevision || event.id || 0)
				if (Number.isSafeInteger(eventRevision)
					&& eventRevision > this.contextEventRevision) {
					this.contextEventRevision = eventRevision
				}
				if (event.data?.contextUsage
					&& this.applyContextUsage(event.data.contextUsage)
					&& event.data?.compactionStatus) {
					// 顶层状态读取晚于嵌套用量；紧邻的旧 completed/新 queued 竞态以较新的顶层状态为准。
					this.contextUsage = Object.freeze({
						...this.contextUsage,
						compactionStatus: event.data.compactionStatus,
						compactionOperationPublicId:
							event.data.compactionOperationPublicId || null
					})
				}
				if (event.type === 'timeout'
					|| ((event.type === 'compaction_completed'
						|| event.type === 'compaction_failed')
						&& !this.contextCompactionActive)
					|| this.contextUsage?.compactionStatus === 'COMPLETED'
					|| this.contextUsage?.compactionStatus === 'FAILED'
					|| (event.type === 'context_usage'
						&& !this.contextCompactionActive)) {
					this.closeContextObserver(false)
				}
			},
			async requestContextCompaction(
				conversationPublicId,
				modelPublicId
			) {
				const result = await aiConversationApi.requestCompaction(
					conversationPublicId, modelPublicId, uuidV4())
				if (conversationPublicId !== this.currentConversationPublicId
					|| modelPublicId !== this.selectedModelPublicId) return result
				if (this.applyContextUsage(result.usage) && result.operation) {
					this.contextUsage = Object.freeze({
						...this.contextUsage,
						compactionStatus: result.operation.status || 'QUEUED',
						compactionOperationPublicId:
							result.operation.operationPublicId
					})
				}
				if (!result.operation
					|| (result.operation.status !== 'QUEUED'
						&& result.operation.status !== 'RUNNING')) {
					this.closeContextObserver(false)
				}
				return result
			},
			async refreshContextUsage({ requestCompaction = false } = {}) {
				const conversationPublicId = this.currentConversationPublicId
				const modelPublicId = this.selectedModelPublicId
				if (!conversationPublicId || !modelPublicId) {
					this.resetContextUsage()
					return null
				}
				try {
					const usage = await aiConversationApi.contextUsage(
						conversationPublicId, modelPublicId)
					if (conversationPublicId !== this.currentConversationPublicId
						|| modelPublicId !== this.selectedModelPublicId) return null
					this.applyContextUsage(usage)
					if (requestCompaction && usage.thresholdReached) {
						await this.openContextObserver()
						return await this.requestContextCompaction(
							conversationPublicId, modelPublicId)
					}
					if (usage.compactionStatus === 'QUEUED'
						|| usage.compactionStatus === 'RUNNING') {
						await this.openContextObserver()
					} else {
						this.closeContextObserver(false)
					}
					return usage
				} catch (_) {
					if (conversationPublicId === this.currentConversationPublicId
						&& modelPublicId === this.selectedModelPublicId) {
						this.contextUsage = null
						this.closeContextObserver(false)
					}
					return null
				}
			},
			acceptTerminalContextUsage(data) {
				if (!data?.contextUsage || !this.applyContextUsage(
					data.contextUsage)) {
					void this.refreshContextUsage()
					return
				}
				if (data.compactionOperationPublicId
					|| this.contextCompactionActive) {
					void this.openContextObserver()
				} else {
					this.closeContextObserver(false)
				}
			},
			refreshTurnNavigationViewport(viewportWidth) {
				const width = Number(viewportWidth ?? (uni.getSystemInfoSync().windowWidth || 0))
				const enabled = clientPlatform() === 'H5' && width >= 768
				if (enabled === this.turnNavigationDesktop && (this.renderWindow.end || !this.messages.length)) return
				this.turnNavigationDesktop = enabled
				this.scrollTarget = ''
				if (enabled) {
					this.resetTurnNavigationWindow(true)
					return
				}
				this.releaseTurnNavigationFrame()
				this.renderWindow = { start: 0, end: this.messages.length }
				this.turnWindowEdgeLock = ''
				this.turnNavigationReady = true
			},
			resetTurnNavigationWindow(followLatest = true) {
				this.renderWindow = this.turnNavigationDesktop
					? createInitialTurnWindow(this.messages.length, TURN_WINDOW_SIZE)
					: { start: 0, end: this.messages.length }
				this.activeTurnKey = this.messages.length
					? messageTurnKey(this.messages[this.messages.length - 1], this.messages.length - 1)
					: ''
				this.turnNavigationReady = false
				this.turnWindowMoving = false
				this.turnWindowEdgeLock = ''
				this.turnFollowLatest = followLatest
				this.olderMessagesError = ''
				this.$nextTick(() => {
					if (followLatest && this.messages.length) this.setMessageScrollTarget('message-bottom')
					// 初始定位完成前不能把 scrollTop=0 误判成用户请求更早历史。
					// #ifdef H5
					requestAnimationFrame(() => requestAnimationFrame(() => {
						this.turnNavigationReady = true
					}))
					// #endif
					// #ifndef H5
					this.turnNavigationReady = true
					// #endif
				})
			},
			releaseTurnNavigationFrame() {
				// #ifdef H5
				if (this.turnScrollFrame != null) cancelAnimationFrame(this.turnScrollFrame)
				// #endif
				this.turnScrollFrame = null
				this.turnWindowEdgeLock = ''
			},
			renderedMessageIndex(renderedIndex) {
				return this.turnNavigationDesktop
					? this.renderWindow.start + renderedIndex
					: renderedIndex
			},
			messageElementId(message, index) {
				return messageTurnElementId(message, index)
			},
			setMessageScrollTarget(elementId) {
				if (this.turnNavigationDesktop) {
					this.$nextTick(() => this.scrollDesktopToElement(elementId))
					return
				}
				this.scrollTarget = ''
				this.$nextTick(() => { this.scrollTarget = elementId })
			},
			turnScrollElement() {
				// #ifdef H5
				return resolveTurnScrollElement(
					this.$refs.messageScroll,
					this.$el?.querySelector?.('.message-scroll'),
					element => getComputedStyle(element)
				)
				// #endif
				return null
			},
			setDesktopScrollTop(value) {
				// #ifdef H5
				const root = this.turnScrollElement()
				if (!root) return false
				root.scrollTop = Math.max(0, Number(value) || 0)
				this.turnScrollTop = root.scrollTop
				return true
				// #endif
				return false
			},
			scrollDesktopToElement(elementId) {
				// #ifdef H5
				const root = this.turnScrollElement()
				if (!root) return false
				if (elementId === 'message-bottom') {
					return this.setDesktopScrollTop(root.scrollHeight - root.clientHeight)
				}
				const element = Array.from(
					this.$el?.querySelectorAll?.('.message-turn') || []
				).find(candidate => candidate.id === elementId)
				if (!element) return false
				const rootRect = root.getBoundingClientRect()
				const targetRect = element.getBoundingClientRect()
				return this.setDesktopScrollTop(
					root.scrollTop + targetRect.top - rootRect.top)
				// #endif
				return false
			},
			captureTurnAnchor() {
				if (!this.turnNavigationDesktop) return null
				// #ifdef H5
				const root = this.turnScrollElement()
				if (!root) return null
				const rootRect = root.getBoundingClientRect()
				const visible = Array.from(this.$el?.querySelectorAll?.('.message-turn') || [])
					.map(element => ({ element, rect: element.getBoundingClientRect() }))
					.filter(item => item.rect.bottom > rootRect.top && item.rect.top < rootRect.bottom)
					.sort((left, right) => left.rect.top - right.rect.top)
				const first = visible[0]
				return first ? {
					elementId: first.element.id,
					offset: first.rect.top - rootRect.top,
					scrollTop: root.scrollTop
				} : null
				// #endif
				return null
			},
			restoreTurnAnchor(anchor) {
				return new Promise(resolve => {
					this.$nextTick(() => {
						// #ifdef H5
						const root = this.turnScrollElement()
						const element = Array.from(this.$el?.querySelectorAll?.('.message-turn') || [])
							.find(candidate => candidate.id === anchor?.elementId)
						if (root && element && anchor) {
							const offset = element.getBoundingClientRect().top - root.getBoundingClientRect().top
							this.setDesktopScrollTop(restoreAnchoredScrollTop(
								anchor.scrollTop,
								anchor.offset,
								offset
							))
						}
						requestAnimationFrame(resolve)
						// #endif
						// #ifndef H5
						resolve()
						// #endif
					})
				})
			},
			async moveTurnWindow(direction) {
				if (!this.turnNavigationDesktop || this.turnWindowMoving) return
				const nextWindow = shiftTurnWindow(
					this.renderWindow,
					direction,
					this.messages.length,
					TURN_WINDOW_SHIFT,
					TURN_WINDOW_SIZE
				)
				if (nextWindow.start === this.renderWindow.start) return
				const anchor = this.captureTurnAnchor()
				this.turnWindowMoving = true
				try {
					this.renderWindow = nextWindow
					await this.restoreTurnAnchor(anchor)
				} finally {
					this.turnWindowMoving = false
				}
			},
			showEarlierTurnWindow() {
				if (this.renderWindow.start > 0) {
					void this.moveTurnWindow('before')
					return
				}
				void this.loadOlderMessages({ preserveAnchor: true })
			},
			handleMessageScroll(event) {
				const detail = event?.detail || {}
				const root = this.turnScrollElement()
				this.turnScrollTop = Number(detail.scrollTop ?? root?.scrollTop ?? 0)
				this.turnViewportHeight = Number(root?.clientHeight || this.turnViewportHeight || 0)
				const scrollHeight = Number(detail.scrollHeight || root?.scrollHeight || 0)
				const distanceToBottom = Math.max(0, scrollHeight - this.turnScrollTop - this.turnViewportHeight)
				this.turnFollowLatest = !this.hasHiddenTurnsAfter
					&& distanceToBottom <= TURN_FOLLOW_LATEST_PX
				if (!this.turnNavigationDesktop || this.turnScrollFrame != null) return
				// #ifdef H5
				this.turnScrollFrame = requestAnimationFrame(() => {
					this.turnScrollFrame = null
					this.updateActiveTurnFromScroll()
					if (!this.turnNavigationReady || this.turnWindowMoving || this.messagesLoading) return
					if (this.turnWindowEdgeLock) {
						const released = this.turnWindowEdgeLock === 'before'
							? this.turnScrollTop >= TURN_WINDOW_EDGE_RELEASE_PX
							: distanceToBottom >= TURN_WINDOW_EDGE_RELEASE_PX
						if (!released) return
						this.turnWindowEdgeLock = ''
					}
					if (this.turnScrollTop <= TURN_WINDOW_EDGE_ENTER_PX
						&& (this.renderWindow.start > 0 || this.hasMoreMessages)) {
						this.turnWindowEdgeLock = 'before'
						if (this.renderWindow.start > 0) void this.moveTurnWindow('before')
						else if (this.hasMoreMessages) void this.loadOlderMessages({ preserveAnchor: true })
					} else if (distanceToBottom <= TURN_WINDOW_EDGE_ENTER_PX && this.hasHiddenTurnsAfter) {
						this.turnWindowEdgeLock = 'after'
						void this.moveTurnWindow('after')
					}
				})
				// #endif
			},
			updateActiveTurnFromScroll() {
				// #ifdef H5
				const root = this.turnScrollElement()
				if (!root) return
				const rootRect = root.getBoundingClientRect()
				const readingLine = rootRect.top + rootRect.height * .35
				const turnsByElementId = new Map(this.visibleTurnItems.map(turn => [turn.elementId, turn]))
				let closest = null
				for (const element of Array.from(this.$el?.querySelectorAll?.('.message-turn') || [])) {
					const rect = element.getBoundingClientRect()
					const distance = readingLine < rect.top
						? rect.top - readingLine
						: readingLine > rect.bottom ? readingLine - rect.bottom : 0
					if (!closest || distance < closest.distance) closest = { element, distance }
				}
				const turn = closest ? turnsByElementId.get(closest.element.id) : null
				if (turn && turn.key !== this.activeTurnKey) this.activeTurnKey = turn.key
				// #endif
			},
			selectConversationTurn(turnKey) {
				const targetIndex = this.messages.findIndex((message, index) =>
					messageTurnKey(message, index) === turnKey)
				if (targetIndex < 0) return
				this.activeTurnKey = turnKey
				const outsideWindow = targetIndex < this.renderWindow.start || targetIndex >= this.renderWindow.end
				if (this.turnNavigationDesktop && outsideWindow) {
					this.renderWindow = centerTurnWindow(targetIndex, this.messages.length, TURN_WINDOW_SIZE)
				}
				this.turnWindowEdgeLock = ''
				const elementId = messageTurnElementId(this.messages[targetIndex], targetIndex)
				if (this.turnNavigationDesktop) {
					this.turnWindowMoving = true
					this.$nextTick(() => {
						this.scrollDesktopToElement(elementId)
						// 点击定位后的两个绘制帧内禁止边缘检测把目标立即换出窗口。
						// #ifdef H5
						requestAnimationFrame(() => requestAnimationFrame(() => {
							this.turnWindowMoving = false
						}))
						// #endif
						// #ifndef H5
						this.turnWindowMoving = false
						// #endif
					})
					return
				}
				this.setMessageScrollTarget(elementId)
			},
			async loadModels() {
				if (this.modelsLoading || this.models.length) return
				this.modelsLoading = true
				try {
					const page = await aiModelApi.list({ pageNum: 1, pageSize: 50 })
					this.models = [...page.models]
					const remembered = String(uni.getStorageSync(MODEL_STORAGE_KEY) || '')
					this.selectedModelPublicId = this.models.some(model => model.publicId === remembered)
						? remembered
						: this.models[0]?.publicId || ''
					const rememberedEffort =
						Number(uni.getStorageSync(REASONING_EFFORT_STORAGE_KEY))
					const level = this.normalizeReasoningEffortForModel(
						this.selectedModel,
						rememberedEffort)
					this.selectedImageAspect = normalizeImageGenerationAspect(
						this.selectedModel,
						uni.getStorageSync(IMAGE_ASPECT_STORAGE_KEY))
					this.selectedImageOutputCount = this.multipleImageOutputsAvailable
						? normalizeImageOutputCount(
							uni.getStorageSync(IMAGE_OUTPUT_COUNT_STORAGE_KEY))
						: 1
					this.normalizeVideoSelections(this.selectedModel)
					uni.setStorageSync(REASONING_EFFORT_STORAGE_KEY, level)
					this.selectedWebSearchMode = normalizeAiConversationWebSearchMode(
						this.selectedWebSearchMode, this.selectedModel)
					if (this.currentConversationPublicId) {
						await this.refreshContextUsage()
					}
				} catch (error) {
					this.composerError = error.message || '模型列表加载失败。'
				} finally {
					this.modelsLoading = false
				}
			},
			async refreshConversations() {
				this.applyStore(setConversationLoading(true))
				try {
					this.applyStore(setConversationPage(
						await aiConversationApi.listConversations(),
						false
					))
					return true
				} catch (error) {
					this.applyStore(setConversationError(error.message || '会话列表加载失败。'))
					return false
				}
			},
			newChat() {
				this.clearCompletedImageUpgrades()
				if (this.generating) {
					if (asyncGenerationEnabled()) this.releaseCurrentGenerationView()
					else this.stop()
				}
				this.cancelPendingUploads()
				this.releasePreviewUrls(this.pendingAttachments.map(file => file.path))
				this.releaseAllLocalPreviews()
				this.activeResearchSession?.close?.()
				this.activeResearchSession = null
				this.resetContextUsage()
				this.applyStore(resetCurrentConversation())
				this.resetTurnNavigationWindow(false)
				this.draft = ''
				this.pendingAttachments = []
				this.composerError = ''
			},
			async openConversation(publicId) {
				if (publicId === this.currentConversationPublicId) return
				this.clearCompletedImageUpgrades()
				if (this.generating && asyncGenerationEnabled()) this.releaseCurrentGenerationView()
				else if (this.generating) return
				this.activeResearchSession?.close?.()
				this.activeResearchSession = null
				this.resetContextUsage()
				this.releaseAllLocalPreviews()
				this.applyStore(selectConversation(publicId))
				this.resetTurnNavigationWindow(false)
				await this.reloadCurrentMessages()
				await this.refreshContextUsage()
				if (!asyncGenerationEnabled()) this.restoreStoppedDraftForCurrentConversation()
				if (asyncGenerationEnabled()) await this.resumeGenerationForConversation(publicId)
			},
			async reloadCurrentMessages() {
				const publicId = this.currentConversationPublicId
				if (!publicId) return true
				this.applyStore(setMessagesLoading(true))
				try {
					const page = await aiConversationApi.messages(publicId)
					if (publicId === this.currentConversationPublicId) {
						this.applyStore(setMessagePage(page, false))
						this.resetTurnNavigationWindow(true)
						this.restoreResearchForCurrentConversation()
					}
					return true
				} catch (error) {
					this.composerError = error.message || '历史消息加载失败。'
					this.applyStore(setMessagesLoading(false))
					return false
				}
			},
			async resyncStaleHistory() {
				if (!this.historyStale || this.historyResyncing) return
				this.historyResyncing = true
				try {
					const conversationsReady = await this.refreshConversations()
					const messagesReady = await this.reloadCurrentMessages()
					if (conversationsReady && messagesReady) {
						this.applyStore(clearAiConversationHistoryStale())
					}
				} finally {
					this.historyResyncing = false
				}
			},
			async loadOlderMessages({ preserveAnchor = false } = {}) {
				if (!this.currentConversationPublicId || !this.nextBefore || this.messagesLoading) return
				const conversationPublicId = this.currentConversationPublicId
				const previousWindow = { ...this.renderWindow }
				const anchor = preserveAnchor ? this.captureTurnAnchor() : null
				this.turnWindowMoving = preserveAnchor && this.turnNavigationDesktop
				this.olderMessagesError = ''
				this.applyStore(setMessagesLoading(true))
				try {
					const page = await aiConversationApi.messages(conversationPublicId, {
						before: this.nextBefore,
						pageSize: TURN_WINDOW_SIZE
					})
					if (conversationPublicId !== this.currentConversationPublicId) return
					this.applyStore(setMessagePage(page, true))
					if (this.turnNavigationDesktop) {
						this.renderWindow = windowAfterPrepend(
							previousWindow,
							page.messages.length,
							this.messages.length,
							TURN_WINDOW_SHIFT,
							TURN_WINDOW_SIZE
						)
						await this.restoreTurnAnchor(anchor)
					}
				} catch (error) {
					const message = error.message || '更早消息加载失败。'
					this.composerError = message
					this.olderMessagesError = message
					this.applyStore(setMessagesLoading(false))
				} finally {
					this.turnWindowMoving = false
				}
			},
			async selectModel(event) {
				const model = this.models[Number(event.detail.value)]
				if (!model) return
				// 模型事件一发生就关闭旧模型流，但保留会话级 revision 给新模型连接续接。
				this.closeContextObserver(false)
				this.selectedModelPublicId = model.publicId
				this.recalculateContextUsageForModel(model)
				uni.setStorageSync(MODEL_STORAGE_KEY, model.publicId)
				const level = this.normalizeReasoningEffortForModel(model)
				uni.setStorageSync(REASONING_EFFORT_STORAGE_KEY, level)
				this.selectedImageAspect = normalizeImageGenerationAspect(
					model, this.selectedImageAspect)
				uni.setStorageSync(IMAGE_ASPECT_STORAGE_KEY, this.selectedImageAspect)
				if (!modelSupportsMultipleImageOutputs(model)) {
					this.selectedImageOutputCount = 1
					this.$refs.imageOutputCountDialog?.close?.()
				} else {
					this.selectedImageOutputCount = normalizeImageOutputCount(
						uni.getStorageSync(IMAGE_OUTPUT_COUNT_STORAGE_KEY),
						this.selectedImageOutputCount)
				}
				this.selectedWebSearchMode = normalizeAiConversationWebSearchMode(
					this.selectedWebSearchMode, model)
				this.normalizeVideoSelections(model)
				if (this.currentConversationPublicId) {
					await this.refreshContextUsage({ requestCompaction: true })
				}
			},
			normalizeReasoningEffortForModel(
				model,
				candidate = this.selectedReasoningEffortLevel) {
				const supported = modelSupportsImageGeneration(model)
					? imageGenerationProfileLevels(model)
					: model?.supportedReasoningEffortLevels || []
				const normalizedCandidate = Number(candidate)
				const fallback = supported.includes(model?.defaultReasoningEffortLevel)
					? model.defaultReasoningEffortLevel
					: supported.includes(2) ? 2 : supported[0] || 2
				const level = supported.includes(normalizedCandidate)
					? normalizedCandidate
					: fallback
				this.selectedReasoningEffortLevel = level
				return level
			},
			normalizeVideoSelections(model) {
				this.selectedVideoMode = normalizeVideoMode(
					model, this.selectedVideoMode)
				this.selectedVideoDuration = normalizeVideoDuration(
					model, this.selectedVideoMode, this.selectedVideoDuration)
				const resolutions = supportedVideoResolutionOptions(
					model, this.selectedVideoMode)
				if (!resolutions.some(option =>
					option.value === this.selectedVideoResolution)) {
					this.selectedVideoResolution = resolutions[0]?.value || ''
				}
				const aspects = supportedVideoAspectOptions(
					model, this.selectedVideoMode)
				if (!aspects.some(option => option.value === this.selectedVideoAspect)) {
					this.selectedVideoAspect = aspects[0]?.value || ''
				}
			},
			selectVideoMode(event) {
				const option = this.videoModeOptions[Number(event.detail.value)]
				if (!option) return
				this.selectedVideoMode = option.value
				this.normalizeVideoSelections(this.selectedModel)
			},
			selectVideoDuration(event) {
				const option = this.videoDurationOptions[Number(event.detail.value)]
				if (option) this.selectedVideoDuration = option.value
			},
			selectVideoResolution(event) {
				const option = this.videoResolutionOptions[Number(event.detail.value)]
				if (option) this.selectedVideoResolution = option.value
			},
			selectVideoAspect(event) {
				const option = this.videoAspectOptions[Number(event.detail.value)]
				if (option) this.selectedVideoAspect = option.value
			},
			selectImageAspect(event) {
				const option = this.imageAspectOptions[Number(event.detail.value)]
				if (!option) return
				this.selectedImageAspect = option.value
				uni.setStorageSync(IMAGE_ASPECT_STORAGE_KEY, option.value)
			},
			openImageOutputCountDialog() {
				if (this.generating || !this.multipleImageOutputsAvailable) return
				this.$refs.imageOutputCountDialog?.open?.(
					this.selectedImageOutputCount)
			},
			selectImageOutputCount(value) {
				if (!this.multipleImageOutputsAvailable) {
					this.selectedImageOutputCount = 1
					return
				}
				this.selectedImageOutputCount = normalizeImageOutputCount(value)
				uni.setStorageSync(
					IMAGE_OUTPUT_COUNT_STORAGE_KEY,
					this.selectedImageOutputCount)
			},
			restoreImageOutputCountFocus() {
				this.$nextTick(() => {
					const trigger = this.$refs.imageOutputCountTrigger
					trigger?.focus?.()
					trigger?.$el?.focus?.()
				})
			},
			selectReasoningEffort(event) {
				const option =
					this.reasoningEffortOptions[Number(event.detail.value)]
				if (!option) return
				const level = option.value
				this.selectedReasoningEffortLevel = level
				uni.setStorageSync(REASONING_EFFORT_STORAGE_KEY, level)
			},
			selectWebSearchMode(event) {
				if (this.generating || !this.webSearchAvailable) return
				const option = this.webSearchOptions[Number(event.detail.value)]
				if (option) this.selectedWebSearchMode = option.value
			},
			async chooseAttachments() {
				if (this.attachmentPickerBusy || this.voiceInteractionActive) return
				this.attachmentPickerBusy = true
				this.composerError = ''
				try {
					const selected = await chooseConversationFiles(
						8 - this.pendingAttachments.length)
					if (!selected.length) return
					validateAttachmentSelection(this.pendingAttachments, selected)
					const files = selected.map(file =>
						createPendingAttachment(file, uuidV4()))
					this.pendingAttachments = [
						...this.pendingAttachments,
						...files
					]
					this.startAttachmentBatch(files)
				} catch (error) {
					this.composerError = error.message || '无法读取所选附件。'
				} finally {
					this.attachmentPickerBusy = false
				}
			},
			startAttachmentBatch(files) {
				let batch
				try {
					batch = uploadConversationFiles(files, {
						onState: (index, state, detail = {}) => {
							this.updatePendingAttachment(files[index].localId, file => {
								file.state = state
								file.retrying = detail.retrying === true
								file.error = ''
								if (state === ATTACHMENT_UPLOAD_STATES.PREPARING || file.retrying) file.progress = 0
							})
						},
						onProgress: (index, progress) => {
							this.updatePendingAttachment(files[index].localId, file => {
								file.progress = Math.max(0, Math.min(100, Number(progress) || 0))
							})
						},
						onUploaded: (index, uploaded) => {
							this.updatePendingAttachment(files[index].localId, file => {
								file.state = ATTACHMENT_UPLOAD_STATES.UPLOADED
								file.progress = 100
								file.retrying = false
								file.uploaded = uploaded
								file.uploadTask = null
								file.error = ''
							})
						},
						onFailed: (index, error) => {
							this.updatePendingAttachment(files[index].localId, file => {
								file.state = ATTACHMENT_UPLOAD_STATES.FAILED
								file.retrying = false
								file.uploaded = null
								file.uploadTask = null
								file.error = error?.message || '附件上传失败。'
							})
						}
					})
					files.forEach((file, index) => {
						file.uploadTask = batch.tasks[index]
					})
				} catch (error) {
					files.forEach(file => {
						this.updatePendingAttachment(file.localId, current => {
							current.state = ATTACHMENT_UPLOAD_STATES.FAILED
							current.error = error?.message || '附件上传失败。'
						})
					})
				}
			},
			updatePendingAttachment(localId, update) {
				const file = this.pendingAttachments.find(item => item.localId === localId)
				if (file) update(file)
			},
			removePending(localId) {
				const index = this.pendingAttachments.findIndex(file => file.localId === localId)
				if (index < 0) return
				const [removed] = this.pendingAttachments.splice(index, 1)
				removed.uploadTask?.cancel?.()
				this.releasePreviewUrls([removed.path])
			},
			retryAttachment(localId) {
				const file = this.pendingAttachments.find(item => item.localId === localId)
				if (!file || file.state !== ATTACHMENT_UPLOAD_STATES.FAILED || this.generating) return
				file.state = ATTACHMENT_UPLOAD_STATES.PREPARING
				file.progress = 0
				file.error = ''
				file.uploaded = null
				this.startAttachmentBatch([file])
			},
			cancelPendingUploads() {
				this.pendingAttachments.forEach(file => file.uploadTask?.cancel?.())
			},
			releasePreviewUrls(urls) {
				// #ifdef H5
				Array.from(urls || []).forEach(url => {
					if (String(url || '').startsWith('blob:')) {
						globalThis.URL?.revokeObjectURL?.(url)
					}
				})
				// #endif
			},
			releaseAllLocalPreviews() {
				this.localPreviewUrls.forEach(urls => this.releasePreviewUrls(urls))
				this.localPreviewUrls.clear()
			},
			async send() {
				if (!this.canSend || this.generating) return
				this.composerError = ''
				const selectedAttachments = [...this.pendingAttachments]
				const attachmentRefs = selectedAttachments.map(file => file.uploaded)
				const text = this.draft.trim()
				const localId = uuidV4()
				const requestedImageCount = this.imageGenerationAvailable
					&& !this.videoGenerationAvailable
					? this.multipleImageOutputsAvailable
						? normalizeImageOutputCount(this.selectedImageOutputCount)
						: 1
					: 0
				if (selectedAttachments.length) {
					this.localPreviewUrls.set(
						localId,
						selectedAttachments.map(file => file.path).filter(Boolean))
				}
				this.activeLocalId = localId
				this.applyStore(appendLocalMessage({
					localId,
					contentText: text,
					contentAttachments: selectedAttachments.map(file => ({
						attachmentId: file.uploaded.attachmentId,
						fileName: file.fileName,
						contentType: file.contentType,
						sizeBytes: String(file.sizeBytes),
						category: attachmentCategory(file),
						url: this.videoGenerationAvailable
							&& attachmentCategory(file) === 'VIDEO' ? '' : file.path,
						state: 'AVAILABLE'
					})),
					responseText: '',
					responseAttachments: requestedImageCount
						? createImageOutputSlots(requestedImageCount)
						: [],
					requestedImageCount,
					imageOutputSummary: '',
					streaming: true, saving: false,
					stopped: false, error: '', modelActivity: null,
					research: null, researchExpanded: false
				}))
				this.draft = ''
				this.pendingAttachments = []
				this.generating = true
				this.textDrain?.close?.()
				this.markdownRenderState?.close?.()
				this.terminalPresentationPending = false
				this.cancelRequestedBeforeGenerationId = false
				this.transportCancelRequested = false
				this.generationCancelDispatching = false
				this.generationCancelSentFor = ''
				// 字节边界诊断只观测 H5；生命周期诊断则在各平台统一服从独立构建开关。
				this.streamDiagnostics = createAiConversationStreamDiagnostics({
					enabled: clientPlatform() === 'H5' ? undefined : false,
					onSummary: reportAiConversationStreamDiagnostics
				})
				this.lifecycleDiagnostics = createAiConversationLifecycleDiagnostics({
					uuid: uuidV4
				})
				this.textDrain = createAiConversationTextDrain({
					onText: chunk => {
						const current = this.messages.find(message => message.localId === localId)
						this.applyStore(patchLocalMessage(localId, {
							responseText: `${current?.responseText || ''}${chunk}`
						}))
						// 等 Vue 完成本轮 DOM 提交后再记录，避免把状态写入时间误当成用户实际看到文本的时间。
						this.$nextTick(() => {
							this.streamDiagnostics?.record?.('FRONTEND_RENDERED', {
								textCharacters: String(chunk || '').length
							})
						})
						this.scrollBottom()
					}
				})
				this.markdownRenderState = createAiMarkdownRenderState({
					onSnapshot: text => {
						this.applyStore(patchLocalMessage(localId, { responseText: text }))
						this.scrollBottom()
					},
					onDelta: chunk => this.textDrain?.push?.(chunk)
				})
				// 用户从旧轮次直接发送新问题时，先把可见窗口切回最新轮次；
				// 后续流式增量仍由 turnFollowLatest 决定是否继续自动跟随。
				this.scrollBottom({ force: true })
				// 图片和视频生成端点都不接受联网工具，发送边界必须覆盖浏览器遗留的搜索状态。
				const webSearchMode = this.imageGenerationAvailable
					|| this.videoGenerationAvailable
					? AI_CONVERSATION_WEB_SEARCH_MODES.OFF
					: normalizeAiConversationWebSearchMode(
						this.selectedWebSearchMode, this.selectedModel)
				const command = {
					conversationPublicId: this.currentConversationPublicId,
					idempotencyKey: uuidV4(),
					localId,
					inputText: text,
					requestedImageCount,
					body: {
						modelPublicId: this.selectedModelPublicId,
						reasoningEffortLevel: this.selectedReasoningEffortLevel,
						webSearchMode,
						input: { text, attachments: attachmentRefs },
						...(this.imageGenerationAvailable
							&& !this.videoGenerationAvailable
							? { image: imageGenerationRequest(
								this.selectedModel,
								this.selectedImageAspect,
								requestedImageCount) }
							: {}),
						...(this.videoGenerationAvailable
							? { video: videoGenerationRequest({
								model: this.selectedModel,
								mode: this.selectedVideoMode,
								durationSeconds: this.selectedVideoDuration,
								resolution: this.selectedVideoResolution,
								aspectRatio: this.selectedVideoAspect,
								attachments: attachmentRefs
							}) }
							: {})
					}
				}
				this.activeIdempotencyKey = command.idempotencyKey
				this.activeResearchSession?.close?.()
				this.activeResearchSession = webSearchMode === AI_CONVERSATION_WEB_SEARCH_MODES.OFF
					? null
					: createAiConversationResearchSession({
						conversationPublicId: this.currentConversationPublicId,
						localId,
						idempotencyKey: command.idempotencyKey,
						webSearchMode
					})
				if (this.activeResearchSession) this.patchResearch(localId)
				if (asyncGenerationEnabled()) registerPendingGeneration({
					idempotencyKey: command.idempotencyKey,
					conversationPublicId: command.conversationPublicId,
					localId: command.localId,
					inputText: command.inputText,
					requestedImageCount,
					previewImages: requestedImageCount
						? createImageOutputSlots(requestedImageCount)
						: []
				})
				try {
					this.activeStream = await openAiConversationStream(command, {
						diagnostics: this.streamDiagnostics,
						lifecycleDiagnostics: this.lifecycleDiagnostics,
						onGenerationId: generationPublicId => {
							this.activeGenerationPublicId = generationPublicId
							if (this.cancelRequestedBeforeGenerationId) {
								this.requestGenerationCancellation(generationPublicId)
							}
						},
						onEvent: event => this.onStreamEvent(localId, event)
					})
					if (this.transportCancelRequested) {
						this.activeStream?.close?.('USER_STOP', {
							hasVisibleOutput: Boolean(this.messages.find(message =>
								message.localId === localId)?.responseText),
							emittedTextCharacters: String(this.messages.find(message =>
								message.localId === localId)?.responseText || '').length
						})
					}
					await this.activeStream.completed
				} catch (error) {
					if (this.generating && this.activeLocalId === localId) {
						this.activeResearchSession?.markTerminal?.('TRANSPORT_ERROR')
						this.patchResearch(localId)
						const message = aiConversationErrorMessage(error)
						this.finishTextPresentation(() => {
							const current = this.messages.find(item => item.localId === localId)
							this.applyStore(patchLocalMessage(localId, {
								responseAttachments: (current?.responseAttachments || [])
									.filter(attachment => !attachment.volatilePreview),
								streaming: false,
								saving: false,
								error: message
							}))
							this.composerError = message
							this.streamDiagnostics?.finish?.('TRANSPORT_ERROR')
							this.$nextTick(() =>
								this.lifecycleDiagnostics?.finish?.('TRANSPORT_ERROR'))
						})
					}
				} finally {
					if (this.activeLocalId === localId) {
						if (!this.terminalPresentationPending) this.generating = false
						this.activeStream = null
					}
				}
			},
			mediaUploadProgressForAttachment(message, attachment) {
				const mediaType = this.previewVideo(attachment) ? 'VIDEO' : 'IMAGE'
				const key = mediaUploadProgressKey({
					mediaType,
					outputIndex: mediaType === 'VIDEO' ? 0 : Number(attachment?.outputIndex || 0)
				})
				return key ? message?.mediaUploadProgressByKey?.[key] || null : null
			},
			videoUploadProgress(message) {
				return message?.mediaUploadProgressByKey?.['video:0'] || null
			},
			hasRenderedVideo(message) {
				return (message?.responseAttachments || []).some(attachment => this.previewVideo(attachment))
			},
			applyMediaUploadProgress(localId, progress) {
				const current = this.messages.find(message => message.localId === localId)
				if (!current) return
				this.applyStore(patchLocalMessage(localId, {
					mediaUploadProgressByKey: mergeMediaUploadProgress(
						current.mediaUploadProgressByKey, progress)
				}))
			},
			dismissMediaUploadProgress(message, progress) {
				if (progress?.state !== 'COMPLETED' || !message?.localId) return
				const key = mediaUploadProgressKey(progress)
				if (!key) return
				this.applyStore(patchLocalMessage(message.localId, {
					mediaUploadProgressByKey: removeMediaUploadProgress(
						message.mediaUploadProgressByKey, key)
				}))
				const task = getGeneration(this.activeGenerationPublicId)
				if (task?.mediaUploadProgressByKey?.[key]) {
					updateGeneration(this.activeGenerationPublicId, {
						mediaUploadProgressByKey: removeMediaUploadProgress(
							task.mediaUploadProgressByKey, key)
					})
				}
			},
			onStreamEvent(localId, event) {
				// 后台任务由全局 Manager 持续收集；旧会话事件不能覆盖用户当前打开会话的局部状态。
				if (asyncGenerationEnabled() && localId !== this.activeLocalId) return
				if (event.type === 'media_upload_progress') {
					this.applyMediaUploadProgress(localId, event.data)
					return
				}
				if (event.type === 'accepted') {
					this.applyStore(setAcceptedConversation(event.data.conversationPublicId))
					this.activeResearchSession?.bindConversation?.(
						event.data.conversationPublicId)
					this.patchResearch(localId)
					if (!asyncGenerationEnabled()
						&& this.transportCancelRequested
						&& this.activeIdempotencyKey) {
						const current = this.messages.find(message =>
							message.localId === localId)
						saveAiConversationStoppedDraft({
							conversationPublicId: event.data.conversationPublicId,
							localId,
							idempotencyKey: this.activeIdempotencyKey,
							inputText: current?.contentText || '',
							responseText: current?.responseText || '',
							stoppedAt: new Date().toISOString()
						})
					}
					if (event.data.generationPublicId) {
						this.activeGenerationPublicId = event.data.generationPublicId
						this.bindCurrentGenerationView(event.data.generationPublicId, localId)
					}
				} else if (event.type === 'activity') {
					this.handleModelActivity(localId, event.data)
				} else if (event.type === 'video_generation_progress') {
					const progress = Math.max(0, Math.min(100,
						Number(event.data?.progress || 0)))
					this.applyStore(patchLocalMessage(localId, {
						streaming: true,
						saving: false,
						modelActivity: { phase: 'VIDEO_GENERATION', progress }
					}))
				} else if (event.type === 'video_transfer_started') {
					this.applyMediaUploadProgress(localId, initialVideoUploadProgress())
					this.applyStore(patchLocalMessage(localId, {
						streaming: false,
						saving: true,
						modelActivity: { phase: 'VIDEO_TRANSFER' }
					}))
				} else if (event.type === 'video_ready') {
					this.acceptTerminalContextUsage(event.data)
					this.activeGenerationSubscription?.()
					this.activeGenerationSubscription = null
					this.finishTextPresentation(() => {
						this.applyStore(patchLocalMessage(localId, {
							messagePublicId: event.data?.messagePublicId || '',
							responseAttachments: Array.isArray(event.data?.attachments)
								? event.data.attachments : [],
							videoMetadata: {
								durationMillis: Number(event.data?.durationMillis || 0),
								width: Number(event.data?.width || 0),
								height: Number(event.data?.height || 0),
								byteSize: Number(event.data?.byteSize || 0),
								videoCodec: String(event.data?.videoCodec || '')
							},
							streaming: false,
							saving: false,
							modelActivity: null,
							error: ''
						}))
						this.releasePreviewUrls(this.localPreviewUrls.get(localId))
						this.localPreviewUrls.delete(localId)
						this.$emit('conversation-completed')
						this.streamDiagnostics?.finish?.('COMPLETE')
						this.$nextTick(() =>
							this.lifecycleDiagnostics?.finish?.('COMPLETE'))
					})
				} else if (event.type === 'video_failed') {
					this.activeGenerationSubscription?.()
					this.activeGenerationSubscription = null
					this.finishTextPresentation(() => {
						const stage = String(event.data?.failureStage
							|| event.data?.terminalReason || 'VIDEO_FAILED')
						this.applyStore(patchLocalMessage(localId, {
							responseAttachments: [],
							streaming: false,
							saving: false,
							modelActivity: null,
							error: `视频生成未能交付（${stage}），费用已按供应商终态处理。`
						}))
						this.releasePreviewUrls(this.localPreviewUrls.get(localId))
						this.localPreviewUrls.delete(localId)
						this.streamDiagnostics?.finish?.('SSE_ERROR')
					})
				} else if (event.type === 'source') {
					if (this.activeResearchSession?.appendSource?.(event.data)) {
						this.patchResearch(localId)
					}
				} else if (event.type === 'reasoning_summary') {
					if (this.activeResearchSession?.appendReasoningSummary?.(event.data)) {
						this.patchResearch(localId)
					}
				} else if (event.type === 'image-preview') {
					const previewImage = imagePreviewAttachment(event.data)
					if (!previewImage) return
					const current = this.messages.find(message => message.localId === localId)
					const responseAttachments = mergeImagePreviewOutput(
						current?.responseAttachments || [], previewImage)
					this.applyStore(patchLocalMessage(localId, {
						responseAttachments,
						streaming: true,
						saving: responseAttachments.some(attachment =>
							attachment?.status === 'FINALIZING')
					}))
					this.scrollBottom()
				} else if (event.type === 'image-persisted') {
					const persistedImage = persistedImageOutputAttachment(event.data)
					if (!persistedImage) return
					const current = this.messages.find(message => message.localId === localId)
					const responseAttachments = mergePersistedImageOutput(
						current?.responseAttachments || [], persistedImage)
					this.applyStore(patchLocalMessage(localId, {
						responseAttachments,
						streaming: true,
						saving: responseAttachments.some(attachment =>
							attachment?.status === 'FINALIZING')
					}))
					this.beginImageUpgrade(
						localId,
						responseAttachments.find(attachment =>
							Number(attachment?.outputIndex) === persistedImage.outputIndex))
				} else if (event.type === 'image-output-status') {
					const current = this.messages.find(message => message.localId === localId)
					const responseAttachments = failImageOutputAttachment(
						current?.responseAttachments || [], event.data)
					this.applyStore(patchLocalMessage(localId, {
						responseAttachments,
						saving: responseAttachments.some(attachment =>
							attachment?.status === 'FINALIZING')
					}))
				} else if (event.type === 'snapshot') {
					this.markdownRenderState?.applySnapshot?.({
						revision: Number(event.data?.revision || 0),
						text: String(event.data?.text || '')
					})
					this.applyStore(patchLocalMessage(localId, { streaming: true }))
					if (this.activeGenerationPublicId) updateGeneration(
						this.activeGenerationPublicId, {
							revision: Number(event.data?.revision || 0),
							responseText: String(event.data?.text || '')
						})
				} else if (event.type === 'delta'
						&& event.data.type === 'FINALIZING') {
					this.applyStore(patchLocalMessage(localId, {
						streaming: false,
						saving: true
					}))
				} else if (event.type === 'delta' && !asyncGenerationEnabled()) {
					this.markdownRenderState?.applyDelta?.({
						sequence: event.data?.sequence,
						eventId: event.data?.eventId || event.data?.id,
						text: event.data?.text || ''
					})
				} else if (event.type === 'completed' && event.data?.generationPublicId) {
					this.acceptTerminalContextUsage(event.data)
					const terminalAttachmentEvidenceComplete =
						Array.isArray(event.data?.attachments)
					const persistedAttachments = persistedImageAttachments(event.data)
					const current = this.messages.find(message => message.localId === localId)
					const requestedImageCount = Number(
						event.data?.requestedImageCount
							|| current?.requestedImageCount || 0)
					const responseAttachments = mergeCompletedImageOutputs(
						current?.responseAttachments,
						persistedAttachments,
						terminalAttachmentEvidenceComplete
							? requestedImageCount : 0)
					const imageOutputSummary = requestedImageCount > 1
						? `请求 ${requestedImageCount} 张，成功 ${persistedAttachments.length} 张`
						: ''
					const warnings = event.data.terminalReason === 'IMAGE_OSS_PERSISTENCE_DROPPED'
						? ['ATTACHMENT_STORAGE_PARTIAL'] : []
					// 先把当前 data URL 快照留在页面消息中，再解除 Manager 订阅；终态清理 Base64 时不会把 UI 换回 OSS。
					this.applyStore(patchLocalMessage(localId, { responseAttachments }))
					this.activeGenerationSubscription?.()
					this.activeGenerationSubscription = null
					this.beginCompletedImageUpgrades(localId, responseAttachments)
					this.finishTextPresentation(() => {
						const failed = event.data.terminalType
							&& event.data.terminalType !== 'COMPLETED'
						this.applyStore(patchLocalMessage(localId, {
							messagePublicId: event.data?.messagePublicId || '',
							requestedImageCount,
							imageOutputSummary,
							streaming: false,
							saving: false,
							stopped: String(event.data.terminalType || '').includes('CANCELLED'),
							warnings,
							error: failed && !String(event.data.terminalType).includes('CANCELLED')
								? '模型响应未能完成，预扣额度已按终态处理。' : ''
						}))
						this.$emit('conversation-completed')
						if (!requestedImageCount) this.reloadCurrentMessages()
						this.streamDiagnostics?.finish?.('COMPLETE')
						this.$nextTick(() =>
							this.lifecycleDiagnostics?.finish?.('COMPLETE'))
					})
				} else if (event.type === 'completed') {
					this.acceptTerminalContextUsage(event.data)
					this.activeResearchSession?.bindMessage?.(
						event.data?.messagePublicId)
					this.activeResearchSession?.markTerminal?.('COMPLETED')
					this.patchResearch(localId)
					this.markdownRenderState?.complete?.({
						finalText: event.data?.text
					})
					removeAiConversationStoppedDraft(this.activeIdempotencyKey)
					this.finishTextPresentation(() => {
						this.applyStore(patchLocalMessage(localId, {
							messagePublicId: event.data.messagePublicId,
							contentAttachments: event.data.inputAttachments || [],
							responseAttachments: event.data.responseAttachments || [],
							streaming: false,
							saving: false,
							modelActivity: null,
							warnings: event.data.warnings || []
						}))
						this.releasePreviewUrls(this.localPreviewUrls.get(localId))
						this.localPreviewUrls.delete(localId)
						this.$emit('conversation-completed')
						this.streamDiagnostics?.finish?.('COMPLETE')
						this.$nextTick(() =>
							this.lifecycleDiagnostics?.finish?.('COMPLETE'))
					})
				} else if (event.type === 'error') {
					this.activeResearchSession?.markTerminal?.('FAILED')
					this.patchResearch(localId)
					this.finishTextPresentation(() => {
						const message = aiConversationErrorMessage(
							event.data,
							'模型响应未能完成'
						)
						const current = this.messages.find(item => item.localId === localId)
						this.applyStore(patchLocalMessage(localId, {
							responseAttachments: (current?.responseAttachments || [])
								.filter(attachment => !attachment.volatilePreview),
							streaming: false,
							modelActivity: null,
							error: message
						}))
						this.composerError = message
						this.streamDiagnostics?.finish?.('SSE_ERROR')
						this.$nextTick(() =>
							this.lifecycleDiagnostics?.finish?.('SSE_ERROR'))
					})
				}
			},
			handleModelActivity(localId, value) {
				const activity = {
					sequence: Number(value?.sequence || 0),
					eventId: String(value?.eventId || ''),
					activityId: String(value?.activityId || ''),
					phase: String(value?.phase || ''),
					status: String(value?.status || ''),
					query: value?.query == null ? null : String(value.query),
					occurredAt: String(value?.occurredAt || '')
				}
				const activityAccepted = this.activeResearchSession?.appendActivity
					? this.activeResearchSession.appendActivity(activity) : null
				if (activityAccepted === false) return
				const patch = { modelActivity: activity }
				if (activity.phase === 'FINALIZING') {
					patch.streaming = false
					patch.saving = true
				}
				this.applyStore(patchLocalMessage(localId, patch))
				if (activityAccepted === true) this.patchResearch(localId)
			},
			patchResearch(localId) {
				const research = this.activeResearchSession?.snapshot?.()
				if (!research) return
				this.applyStore(patchLocalMessage(localId, { research }))
			},
			modelActivityText(message) {
				const activity = message?.modelActivity
				if (!activity) return ''
				if (activity.phase === 'PROCESSING') return '正在准备回答'
				if (activity.phase === 'REASONING') return '正在推理和整理信息'
				if (activity.phase === 'WEB_SEARCH') {
					if (activity.status === 'STARTED') {
						return activity.query
							? `已开始搜索：${activity.query}` : '已开始联网搜索'
					}
					if (activity.status === 'IN_PROGRESS') {
						return activity.query
							? `正在搜索：${activity.query}` : '正在执行联网搜索'
					}
					if (activity.status === 'COMPLETED') {
						return '已完成联网检索，正在整理来源'
					}
					if (activity.status === 'FAILED') return '联网搜索失败'
					if (activity.status === 'UNAVAILABLE') return '联网搜索不可用'
					return '联网搜索状态已更新'
				}
				if (activity.phase === 'GENERATING') return '正在生成回答'
				if (activity.phase === 'VIDEO_GENERATION') {
					return `正在生成视频 · ${Math.max(0, Math.min(100,
						Number(activity.progress || 0)))}%`
				}
				if (activity.phase === 'VIDEO_TRANSFER') return '正在安全保存视频到 OSS'
				if (activity.phase === 'FINALIZING') return '正在保存生成内容'
				return ''
			},
			researchDetailsAvailable(message) {
				if (!aiConversationWebSearchEnabled() || !message?.research) return false
				return Boolean(message.research.activities?.length
					|| message.research.sources?.length
					|| message.research.reasoningSummaries?.length)
			},
			toggleResearch(message) {
				const messageKey = message?.localId || message?.messagePublicId
				if (!messageKey) return
				this.applyStore(patchMessage(messageKey, {
					researchExpanded: !message.researchExpanded
				}))
			},
			researchSummaryMarkdown(message) {
				return formatAiReasoningSummaryMarkdown(
					message?.research?.reasoningSummaries)
			},
			researchSources(message) {
				return mergeAiConversationSources(message?.research?.sources)
			},
			researchTimelineItems(message) {
				const sources = this.researchSources(message)
				return presentAiResearchTimeline(
					message?.research?.activities, sources).map(item =>
						item.type === 'source' ? item : {
							...item,
							label: this.researchActivityText(
								item.activity, Boolean(item.sourcePresentation))
						})
			},
			researchTimelineKey(item) {
				if (item?.type === 'source') {
					return `source-${item.source?.sourceId || item.source?.url || item.sequence}`
				}
				return item?.activity?.eventId || `activity-${item?.sequence}`
			},
			researchActivityText(activity, hasSourceTarget = false) {
				const status = RESEARCH_ACTIVITY_STATUS_LABELS[activity.status]
					|| '状态更新'
				if (activity.phase === 'WEB_SEARCH') {
					return `${status} · ${hasSourceTarget ? '搜索' : '联网搜索'}`
				}
				if (activity.phase === 'REASONING') return `${status} · 整理和推理`
				if (activity.phase === 'GENERATING') return `${status} · 生成回答`
				if (activity.phase === 'FINALIZING') return `${status} · 保存回答`
				return `${status} · 准备回答`
			},
			researchTime(value) {
				const time = new Date(value)
				return Number.isNaN(time.getTime()) ? '' : time.toLocaleTimeString([], {
					hour: '2-digit', minute: '2-digit', second: '2-digit'
				})
			},
			restoreResearchForCurrentConversation() {
				if (!aiConversationWebSearchEnabled()
					|| !this.currentConversationPublicId
					|| !this.messages.length) return
				for (const message of this.messages) {
					if (!message.messagePublicId) continue
					const research = findAiConversationResearchSession({
						messagePublicId: message.messagePublicId
					})
					if (!research
						|| research.conversationPublicId
							!== this.currentConversationPublicId) continue
					this.applyStore(patchMessage(
						message.localId || message.messagePublicId,
						{ research, researchExpanded: false }))
				}
			},
			finishTextPresentation(callback) {
				const drain = this.textDrain
				this.terminalPresentationPending = true
				const finish = () => {
					if (this.textDrain !== drain) return
					try { callback?.() } finally {
						this.terminalPresentationPending = false
						this.generating = false
						this.textDrain = null
						this.markdownRenderState?.close?.()
						this.markdownRenderState = null
					}
				}
				if (drain) drain.finish(finish)
				else finish()
			},
			async requestGenerationCancellation(generationPublicId) {
				if (!generationPublicId
					|| this.generationCancelDispatching
					|| this.generationCancelSentFor === generationPublicId) return
				this.generationCancelDispatching = true
				try {
					await cancelGenerationWithRetry(generationPublicId)
					this.generationCancelSentFor = generationPublicId
					this.cancelRequestedBeforeGenerationId = false
					updateGeneration(generationPublicId, { status: 'CANCEL_REQUESTED' })
				} catch (_) {
					this.composerError = '取消请求暂未确认，任务状态仍在后台核对。'
					// 明确取消请求不可达时才主动 DETACHED，让三十秒失联机制成为有限兜底；普通页面切换不走此路径。
				} finally {
					this.generationCancelDispatching = false
				}
			},
			async stop() {
				if (!this.generating) return
				// 最终 completed 已经到达时只剩最多五百毫秒视觉排空，不能再把已落库消息降级成本地 Stop 草稿。
				if (this.terminalPresentationPending) return
				if (this.currentConversationPublicId) {
					this.applyStore(markAiConversationHistoryStale())
				}
				const cancelledLocalId = this.activeLocalId
				const current = this.messages.find(message =>
					message.localId === cancelledLocalId)
				// 先确认按需上下文 SSE 已经完成握手，再发送 Stop，避免快速保存与压缩终态落在订阅窗口之外。
				if (this.currentConversationPublicId
					&& this.selectedModelPublicId) {
					await this.openContextObserver()
				}
				if (!asyncGenerationEnabled()) {
					// 先冻结视觉层和当前可见文本，再等待取消确认，避免重试期间继续向 UI 追加内容。
					this.activeResearchSession?.markTerminal?.('USER_STOP')
					this.patchResearch(cancelledLocalId)
					this.textDrain?.close?.()
					this.textDrain = null
					this.transportCancelRequested = true
					this.terminalPresentationPending = false
					saveAiConversationStoppedDraft({
						conversationPublicId: this.currentConversationPublicId,
						localId: cancelledLocalId,
						idempotencyKey: this.activeIdempotencyKey,
						inputText: current?.contentText || '',
						responseText: current?.responseText || '',
						stoppedAt: new Date().toISOString()
					})
					this.applyStore(patchLocalMessage(cancelledLocalId, {
						streaming: false,
						saving: true,
						stopped: true,
						modelActivity: null
					}))
					try {
						if (this.activeIdempotencyKey) {
							await cancelDirectResponseWithRetry(this.activeIdempotencyKey)
						}
					} catch (_) {
						this.composerError = '取消请求暂未确认，连接已关闭，后端将按断线兜底处理。'
					} finally {
						this.activeStream?.close?.('USER_STOP', {
							hasVisibleOutput: Boolean(current?.responseText),
							emittedTextCharacters: String(current?.responseText || '').length
						})
						this.activeStream = null
					}
					this.streamDiagnostics?.finish?.('CANCEL')
					this.applyStore(patchLocalMessage(cancelledLocalId, {
						streaming: false,
						saving: false,
						stopped: true
					}))
					this.generating = false
					this.$nextTick(() =>
						this.lifecycleDiagnostics?.finish?.('CANCEL'))
					return
				}
				if (asyncGenerationEnabled()) {
					// Stop 是明确业务取消；响应头尚未返回时保留意图，拿到 Generation ID 后立即补发一次。
					this.cancelRequestedBeforeGenerationId = true
					this.applyStore(patchLocalMessage(cancelledLocalId, {
						saving: true,
						error: '取消处理中…'
					}))
					await this.requestGenerationCancellation(this.activeGenerationPublicId)
				}
				// 新链路保留全局 Observer 接收唯一终态，只解除当前组件订阅并停止本地文本渲染。
				if (asyncGenerationEnabled()) {
					// 保留当前 Generation 的全局 Observer，用于接收后台唯一终态确认。
					// Stop 只暂停当前页面交互，不能把 Observer 断开误当成模型取消。
					this.activeStream = null
				}
				this.textDrain?.close?.()
				this.textDrain = null
				this.streamDiagnostics?.finish?.('CANCEL')
				this.terminalPresentationPending = false
				this.applyStore(patchLocalMessage(cancelledLocalId, {
					streaming: false,
					saving: asyncGenerationEnabled(),
					stopped: !asyncGenerationEnabled()
				}))
				this.generating = false
				this.$nextTick(() =>
					this.lifecycleDiagnostics?.finish?.('CANCEL'))
			},
			scrollBottom({ force = false } = {}) {
				if (this.turnNavigationDesktop && !force && !this.turnFollowLatest) return
				if (this.turnNavigationDesktop) {
					this.renderWindow = createInitialTurnWindow(this.messages.length, TURN_WINDOW_SIZE)
				}
				this.setMessageScrollTarget('message-bottom')
			},
			generatedResponseImageKey(attachment) {
				return String(attachment?.attachmentId || '')
			},
			clearCompletedImageUpgrades() {
				this.imageUpgradeTokens.clear()
			},
			beginCompletedImageUpgrades(localId, attachments) {
				for (const attachment of attachments || []) {
					this.beginImageUpgrade(localId, attachment)
				}
			},
			beginImageUpgrade(localId, attachment) {
				if (attachment?.requiresUpgrade !== true || !attachment?.persistedUrl
					|| attachment?.upgradeFailed === true) return
				const outputIndex = Number(attachment.outputIndex)
				const key = `${localId}:${outputIndex}`
				const activeToken = this.imageUpgradeTokens.get(key)
				if (activeToken?.persistedUrl === attachment.persistedUrl) return
				// 同一槽位出现更新 URL 时立即换 Token；旧预加载即使晚到，也不能覆盖较新的正式附件。
				const token = Object.freeze({ persistedUrl: attachment.persistedUrl })
				this.imageUpgradeTokens.set(key, token)
				preloadConversationImage(attachment.persistedUrl, {
					platform: clientPlatform()
				}).then(result => {
					if (this.imageUpgradeTokens.get(key) !== token) return
					this.imageUpgradeTokens.delete(key)
					this.completeImageUpgrade(
						localId,
						outputIndex,
						attachment.persistedUrl,
						result)
				}).catch(() => {
					if (this.imageUpgradeTokens.get(key) !== token) return
					this.imageUpgradeTokens.delete(key)
					this.failImageUpgrade(
						localId,
						outputIndex,
						attachment.persistedUrl)
				})
			},
			completeImageUpgrade(localId, outputIndex, persistedUrl, result) {
				const message = this.messages.find(item => item.localId === localId)
				const current = (message?.responseAttachments || []).find(item =>
					Number(item?.outputIndex) === outputIndex)
				if (!current || current.persistedUrl !== persistedUrl
					|| current.requiresUpgrade !== true) return
				const upgraded = Object.freeze({
					...current,
					url: result.displayUrl,
					contentType: current.persistedContentType || current.contentType,
					width: Number(result.width || current.width || 0),
					height: Number(result.height || current.height || 0),
					phase: 'FINAL',
					status: 'COMPLETED',
					previewKind: 'FULL',
					requiresUpgrade: false,
					volatilePreview: false,
					upgradeFailed: false
				})
				this.applyStore(patchLocalMessage(localId, {
					responseAttachments: upsertImageOutputAttachment(
						message.responseAttachments, upgraded)
				}))
			},
			failImageUpgrade(localId, outputIndex, persistedUrl) {
				const message = this.messages.find(item => item.localId === localId)
				const current = (message?.responseAttachments || []).find(item =>
					Number(item?.outputIndex) === outputIndex)
				if (!current || current.persistedUrl !== persistedUrl
					|| current.requiresUpgrade !== true) return
				// 高清资源失败时保留已经可见的缩略图，不切成空白、错误图或未完成的远端地址。
				this.applyStore(patchLocalMessage(localId, {
					responseAttachments: upsertImageOutputAttachment(
						message.responseAttachments,
						Object.freeze({
							...current,
							status: 'COMPLETED',
							upgradeFailed: true
						}))
				}))
			},
			imageOutputStatusLabel(attachment) {
				return ({
					QUEUED: '等待开始',
					GENERATING: '正在生成',
					FINALIZING: '正在保存',
					UPGRADING: '正在加载高清图片',
					COMPLETED: '已完成',
					FAILED: '生成失败'
				})[String(attachment?.status || 'QUEUED').toUpperCase()] || '等待开始'
			},
			generatedResponseImageStyle(attachment) {
				const key = this.generatedResponseImageKey(attachment)
				const naturalSize = key
					? this.generatedResponseImageNaturalSizes[key]
					: null
				const displayWidth = generatedResponseImageDisplayWidth(
					naturalSize,
					this.generatedResponseImageViewportHeight
				)
				return displayWidth == null
					? { width: '100%' }
					: { width: `${displayWidth}px` }
			},
			generatedVideoCardStyle(attachment, terminalMetadata = null) {
				const key = this.generatedResponseImageKey(attachment)
				const observedSize = key
					? this.generatedVideoNaturalSizes[key]
					: null
				const terminalWidth = positiveFiniteNumber(terminalMetadata?.width)
				const terminalHeight = positiveFiniteNumber(terminalMetadata?.height)
				const naturalSize = observedSize || (
					terminalWidth != null && terminalHeight != null
						? { width: terminalWidth, height: terminalHeight }
						: GENERATED_VIDEO_FALLBACK_SIZE
				)
				const display = generatedVideoDisplaySize(
					naturalSize,
					this.generatedResponseImageViewportHeight
				)
				return {
					width: `${display.width}px`,
					aspectRatio: `${display.naturalWidth} / ${display.naturalHeight}`
				}
			},
			handleGeneratedVideoMetadata(attachment, event) {
				const key = this.generatedResponseImageKey(attachment)
				if (!key) return

				const naturalWidth = positiveFiniteNumber(event?.detail?.width)
					?? positiveFiniteNumber(event?.target?.videoWidth)
				const naturalHeight = positiveFiniteNumber(event?.detail?.height)
					?? positiveFiniteNumber(event?.target?.videoHeight)
				if (naturalWidth == null || naturalHeight == null) return

				this.generatedVideoNaturalSizes = {
					...this.generatedVideoNaturalSizes,
					[key]: Object.freeze({ width: naturalWidth, height: naturalHeight })
				}
			},
			handleGeneratedResponseImageLoad(attachment, event) {
				const key = this.generatedResponseImageKey(attachment)
				if (!key) return

				const naturalWidth = positiveFiniteNumber(event?.detail?.width)
				const naturalHeight = positiveFiniteNumber(event?.detail?.height)
				if (naturalWidth == null || naturalHeight == null) {
					const remainingSizes = { ...this.generatedResponseImageNaturalSizes }
					delete remainingSizes[key]
					this.generatedResponseImageNaturalSizes = remainingSizes
					return
				}

				this.generatedResponseImageNaturalSizes = {
					...this.generatedResponseImageNaturalSizes,
					[key]: Object.freeze({ width: naturalWidth, height: naturalHeight })
				}
			},
			handleGeneratedResponseImageWindowResize(event) {
				this.refreshGeneratedResponseImageViewportHeight(
					event?.size?.windowHeight
				)
				this.refreshTurnNavigationViewport(event?.size?.windowWidth)
			},
			refreshGeneratedResponseImageViewportHeight(viewportHeight) {
				this.generatedResponseImageViewportHeight =
					positiveFiniteNumber(viewportHeight) ?? currentWindowHeight()
			},
			previewImage(attachment) { return attachment.state === 'AVAILABLE' && attachment.contentType?.startsWith('image/') && attachment.contentType !== 'image/svg+xml' && attachment.url },
			previewVideo(attachment) { return attachment.state === 'AVAILABLE' && attachment.contentType?.startsWith('video/') && attachment.url },
			openAttachment(attachment) {
				if (attachment.state !== 'AVAILABLE' || !attachment.url) return
				// #ifdef H5
				window.open(attachment.url, '_blank', 'noopener,noreferrer')
				// #endif
				// #ifdef APP-PLUS
				plus.runtime.openURL(attachment.url)
				// #endif
			},
			handlePageShow() {
				this.syncStore()
				this.resyncStaleHistory()
			},
			handlePageHide() {
				// 页面切换只改变可见性，不代表用户取消生成；保留 SSE 和本地增量队列，返回页面后继续接收/展示。
				if (this.generating) return
				if (!asyncGenerationEnabled()) {
					this.applyStore(discardTransientMessages())
					this.releaseAllLocalPreviews()
				}
			},
			restoreStoppedDraftForCurrentConversation() {
				if (asyncGenerationEnabled()) return
				const stopped = findAiConversationStoppedDraft(
					this.currentConversationPublicId)
				if (!stopped || this.messages.some(message =>
					message.localId === stopped.localId)) return
				this.applyStore(appendLocalMessage({
					localId: stopped.localId,
					contentText: stopped.inputText,
					contentAttachments: [],
					responseText: stopped.responseText,
					responseAttachments: [],
					streaming: false,
					saving: false,
					stopped: true,
					error: ''
				}))
				this.scrollBottom({ force: true })
			},
			handlePageUnload() {
				this.clearCompletedImageUpgrades()
				if (this.activeStream && this.currentConversationPublicId) {
					this.applyStore(markAiConversationHistoryStale())
				}
				const current = this.messages.find(message =>
					message.localId === this.activeLocalId)
				if (!asyncGenerationEnabled() && this.activeStream) {
					this.activeResearchSession?.markTerminal?.('TRANSPORT_DISCONNECT')
					this.patchResearch(this.activeLocalId)
				}
				if (!asyncGenerationEnabled()) this.activeStream?.close?.(
					'PAGE_UNLOAD', {
					hasVisibleOutput: Boolean(current?.responseText),
					emittedTextCharacters: String(current?.responseText || '').length
				})
				this.lifecycleDiagnostics?.finish?.('CANCEL')
				this.textDrain?.close?.()
				this.textDrain = null
				this.cancelPendingUploads()
				this.releasePreviewUrls(this.pendingAttachments.map(file => file.path))
				this.releaseAllLocalPreviews()
				if (!asyncGenerationEnabled()) this.applyStore(discardTransientMessages())
			},
			releaseCurrentGenerationView() {
				this.activeGenerationSubscription?.()
				this.activeGenerationSubscription = null
				this.activeStream = null
				this.activeGenerationPublicId = ''
				this.activeLocalId = ''
				this.generating = false
				this.textDrain?.close?.()
				this.textDrain = null
			},
			async restoreActiveGenerations() {
				try {
					for (const pending of listPendingGenerationRequests()) {
						try {
							const recovered = await aiConversationApi.generationByIdempotency(
								pending.idempotencyKey)
							registerGeneration({ ...pending, ...recovered, observerAttached: false })
						} catch (_) {
							// 请求可能尚未提交或认证暂不可用，保留幂等键供下一次页面恢复继续查询。
						}
					}
					const serverTasks = await aiConversationApi.activeGenerations()
					serverTasks.forEach(task => registerGeneration({
						...task,
						observerAttached: false
					}))
					for (const task of listActiveGenerations()) {
						if (!task.observerAttached) {
							try {
								await openAiConversationGenerationStream(task.generationPublicId)
							} catch (_) {
								updateGeneration(task.generationPublicId, { observerAttached: false })
							}
						}
					}
					if (this.currentConversationPublicId) {
						await this.resumeGenerationForConversation(this.currentConversationPublicId)
					}
				} catch (_) {
					// 恢复失败只保留 sessionStorage 状态，不能把任务伪装成已经取消。
				}
			},
			async resumeGenerationForConversation(conversationPublicId) {
				const task = listActiveGenerations().find(item =>
					item.conversationPublicId === conversationPublicId)
				if (!task) return
				const localId = task.localId || `generation-${task.generationPublicId}`
				if (!this.messages.some(message => message.localId === localId)) {
					this.applyStore(appendLocalMessage({
						localId,
						contentText: task.inputText || '',
						contentAttachments: [],
						responseText: task.responseText || '',
						responseAttachments: [],
						streaming: true,
						saving: false,
						stopped: false,
						error: ''
					}))
					this.scrollBottom({ force: true })
				}
				this.activeLocalId = localId
				this.activeGenerationPublicId = task.generationPublicId
				this.generating = true
				this.bindCurrentGenerationView(task.generationPublicId, localId)
				if (!task.observerAttached) {
					this.activeStream = await openAiConversationGenerationStream(
						task.generationPublicId)
					this.activeStream.completed.catch(() => {
						updateGeneration(task.generationPublicId, { observerAttached: false })
					})
				}
			},
			bindCurrentGenerationView(generationPublicId, localId) {
				this.activeGenerationSubscription?.()
				this.activeGenerationSubscription = subscribeGeneration(
					generationPublicId, task => {
						if (!task || this.activeGenerationPublicId !== generationPublicId) return
						this.applyStore(patchLocalMessage(localId, {
							messagePublicId: task.messagePublicId || '',
							responseText: task.responseText || '',
							mediaUploadProgressByKey: task.mediaUploadProgressByKey || {},
							...(Array.isArray(task.previewImages) && task.previewImages.length
								? { responseAttachments: task.previewImages }
								: task.previewImage
									? { responseAttachments: [task.previewImage] }
								: Array.isArray(task.responseAttachments)
									? { responseAttachments: task.responseAttachments }
									: {}),
							streaming: !['SETTLED', 'REFUNDED', 'RECONCILE_REQUIRED', 'COMPLETED']
								.includes(task.status),
							saving: task.status === 'CANCEL_REQUESTED'
								|| task.videoTransferring === true
								|| (task.previewImages || []).some(attachment =>
									attachment?.status === 'FINALIZING'),
							modelActivity: task.videoTransferring
								? { phase: 'VIDEO_TRANSFER' }
								: task.videoProgress != null
									&& Number.isFinite(Number(task.videoProgress))
									? { phase: 'VIDEO_GENERATION', progress: Number(task.videoProgress) }
									: null,
							stopped: String(task.terminalType || '').includes('CANCELLED'),
							warnings: task.warnings || [],
							error: task.videoError
								? `视频生成未能交付（${task.videoError}），费用已按供应商终态处理。`
								: '',
							requestedImageCount: Number(task.requestedImageCount || 0),
							imageOutputSummary: Number(task.requestedImageCount || 0) > 1
								&& Array.isArray(task.responseAttachments)
								? `请求 ${task.requestedImageCount} 张，成功 ${Number(task.successfulImageCount ?? task.responseAttachments.filter(item => item?.state === 'AVAILABLE').length)} 张`
								: ''
						}))
						if (['SETTLED', 'REFUNDED', 'RECONCILE_REQUIRED', 'COMPLETED']
								.includes(task.status)) {
							this.generating = false
							if (task.terminalAttachmentEvidenceComplete === false) {
								updateGeneration(generationPublicId, {
									terminalAttachmentEvidenceComplete: null
								})
								void this.reloadCurrentMessages()
							}
						}
					})
			}
		}
	}
</script>

<style lang="scss">
	@import '@/common/ui/user-material.scss';
	.chat-header, .composer-meta, .composer-controls, .assistant-label { display: flex; align-items: center; }
	.icon-button, .history-more, .composer-icon, .voice-button, .send-button, .attachment-file, .research-toggle, .web-search-toggle, .image-count-picker { @include user-frosted-control; box-sizing: border-box; }
	.icon-button { width: 48px; height: 48px; margin: 0; padding: 0; border-radius: 14px; }
	.history-more { min-height: 44px; margin: 8px auto; padding: 0 16px; color: #dce5e0; }
	.chat-main { width: 100%; max-width: 100%; min-width: 0; min-height: 0; height: 100%; display: grid; grid-template-columns: minmax(0, 1fr); grid-template-rows: auto minmax(0, 1fr) auto; padding-bottom: calc(72px + env(safe-area-inset-bottom)); color: #f3f5f4; box-sizing: border-box; }
	.chat-header { max-width: 100%; min-width: 0; min-height: 68px; padding: max(8px, env(safe-area-inset-top)) 14px 8px; gap: 10px; border-bottom: 1px solid #29302c; background: rgba(11, 13, 12, .88); backdrop-filter: blur(16px); box-sizing: border-box; }
	.chat-header-copy { min-width: 0; flex: 1; display: flex; flex-direction: column; align-items: center; }
	.chat-header-balance { width: 48px; height: 48px; flex: 0 0 48px; }
	.chat-header-title { max-width: 100%; overflow: hidden; font-size: 15px; font-weight: 700; text-overflow: ellipsis; white-space: nowrap; }
	.chat-header-subtitle { margin-top: 2px; color: #8b9690; font-size: 11px; }
	.message-stage { min-width: 0; min-height: 0; position: relative; overflow: hidden; }
	.message-scroll { width: 100%; max-width: 100%; min-width: 0; min-height: 0; height: 100%; overflow-x: hidden; }
	.message-shell { width: min(100%, 780px); min-height: 100%; margin: 0 auto; padding: 28px 18px 22px; box-sizing: border-box; }
	.chat-empty { min-height: 48vh; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; }
	.chat-empty-mark { width: 58px; height: 58px; display: flex; align-items: center; justify-content: center; border: 1px solid rgba(55, 211, 154, .38); border-radius: 18px; background: rgba(55, 211, 154, .12); color: #72e1b8; font-weight: 800; }
	.chat-empty-title { margin-top: 18px; font-size: 25px; font-weight: 750; }
	.chat-empty-copy { max-width: 440px; margin-top: 9px; color: #8b9690; line-height: 1.6; }
	.message-turn { margin-bottom: 30px; }
	.message-block { max-width: 92%; padding: 14px 16px; border-radius: 16px; box-sizing: border-box; }
	.user-message { margin-left: auto; background: #1b211e; border: 1px solid #303a35; }
	.assistant-message { width: 100%; max-width: 100%; min-width: 0; margin-top: 12px; padding-left: 2px; background: transparent; }
	.assistant-label { gap: 8px; margin-bottom: 8px; color: #37d39a; font-size: 12px; font-weight: 800; letter-spacing: .8px; }
	.stopped-label { color: #f2a24d; font-weight: 600; letter-spacing: 0; }
	.model-activity { min-height: 28px; margin: 2px 0 8px; display: flex; align-items: center; gap: 8px; color: #9faaa4; font-size: 12px; }
	.model-activity-dot { width: 7px; height: 7px; flex: 0 0 7px; border-radius: 50%; background: #37d39a; box-shadow: 0 0 0 4px rgba(55, 211, 154, .1); }
	.research-toggle { min-height: 36px; margin: 0 0 10px; padding: 0 10px; display: inline-flex; align-items: center; gap: 7px; border-radius: 10px; color: #a9d8c5; font-size: 12px; }
	.research-panel { max-width: 680px; margin: 0 0 12px; padding: 12px; border: 1px solid rgba(75, 101, 89, .52); border-radius: 13px; background: rgba(19, 25, 22, .72); }
	.research-row { display: grid; grid-template-columns: 72px minmax(0, 1fr); gap: 8px; padding: 5px 0; color: #b9c5bf; font-size: 12px; line-height: 1.5; }
	.research-time { color: #718078; }
	.research-activity-content { min-width: 0; display: flex; align-items: center; flex-wrap: wrap; gap: 7px; }
	.research-summary, .research-sources { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; color: #b9c5bf; font-size: 12px; line-height: 1.65; word-break: break-word; }
	.research-summary .ai-markdown-message { min-width: 0; }
	.research-section-label { color: #78d7b2; font-size: 11px; font-weight: 700; letter-spacing: .4px; }
	.message-text { color: #edf3f0; font-size: 15px; line-height: 1.72; white-space: pre-wrap; word-break: break-word; }
	.typing-indicator, .saving-indicator { color: #8b9690; font-size: 13px; }
	.message-error, .message-warning, .composer-error { color: #f2a24d; font-size: 13px; }
	.message-warning { display: block; margin-top: 9px; }
	.attachment-grid { margin-top: 10px; display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 8px; }
	.attachment-card { min-width: 0; overflow: hidden; display: flex; flex-direction: column; border: 1px solid #313a35; border-radius: 12px; background: #141816; }
	.attachment-card.is-video { width: min(100%, 720px); max-width: 100%; justify-self: center; background: #000; }
	.attachment-media-frame { min-width: 0; overflow: hidden; }
	.attachment-media-frame.is-video { width: 100%; max-width: 720px; max-height: min(68vh, 1080px); margin: 0 auto; background: #000; }
	.media-upload-pending-grid { grid-template-columns: minmax(0, 1fr); }
	.media-upload-pending-card { min-height: 0; }
	.media-upload-video-placeholder { min-height: clamp(160px, 38vw, 405px); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; color: #8fdcbe; font-size: 13px; }
	.image-output-slot { min-height: 148px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 7px; color: #8fdcbe; font-size: 12px; text-align: center; }
	.image-output-slot.is-failed { color: #ff9b94; background: rgba(125, 43, 39, .12); }
	.image-output-summary { display: block; margin-top: 9px; color: #a9b5af; font-size: 12px; }
	.attachment-image { width: 100%; height: 180px; display: block; }
	.attachment-video { width: 100%; height: 100%; max-height: min(68vh, 1080px); margin: 0 auto; display: block; object-fit: contain; background: #000; }
	.attachment-image.generated-response-image { width: auto; max-width: 100%; height: auto; margin: 0 auto; display: block; }
	.image-preview-state { display: block; padding: 8px 10px; color: #8fdcbe; font-size: 11px; line-height: 1.45; }
	.image-count-picker { min-height: 34px; margin: 0; padding: 0 10px; border-radius: 10px; color: #cbd4cf; font-size: 12px; }
	.attachment-file { width: 100%; min-height: 54px; margin: 0; padding: 10px 12px; justify-content: flex-start; gap: 9px; border: 0; border-radius: 0; color: #dce5e0; text-align: left; }
	.attachment-file text { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
	.message-bottom { height: 1px; }
	.composer-wrap { width: min(100%, 820px); max-width: 100%; min-width: 0; margin: 0 auto; padding: 8px 14px calc(10px + env(safe-area-inset-bottom)); box-sizing: border-box; }
	.composer { min-height: 58px; padding: 7px; display: flex; align-items: flex-end; gap: 6px; border: 1px solid rgba(99, 117, 107, .55); border-radius: 18px; background: rgba(30, 35, 32, .84); box-shadow: inset 0 1px rgba(255, 255, 255, .05); backdrop-filter: blur(20px) saturate(115%); }
	.composer-icon, .voice-button, .send-button { width: 44px; height: 44px; min-height: 44px; margin: 0; padding: 0; flex-shrink: 0; border-radius: 13px; }
	.composer-input { min-height: 42px; max-height: 160px; flex: 1; padding: 10px 6px; color: #f3f5f4; font-size: 15px; line-height: 1.45; box-sizing: border-box; }
	.voice-button { border-color: rgba(111, 133, 122, .62); background: rgba(25, 31, 28, .9); }
	.voice-button.is-recording { border-color: #ff746b; background: rgba(183, 57, 49, .72); box-shadow: 0 0 0 3px rgba(255, 116, 107, .13); }
	.voice-button.is-finalizing { border-color: rgba(242, 162, 77, .5); opacity: .72; }
	.voice-button:focus-visible { outline: 2px solid rgba(55, 211, 154, .78); outline-offset: 2px; }
	.voice-status { margin-top: 7px; padding: 9px 12px; border: 1px solid rgba(255, 116, 107, .28); border-radius: 12px; background: rgba(76, 28, 25, .3); color: #f4c2be; }
	.voice-status.is-finalizing { border-color: rgba(242, 162, 77, .3); background: rgba(75, 51, 24, .28); color: #f2c997; }
	.voice-status.is-queued { border-color: rgba(77, 156, 242, .34); background: rgba(24, 48, 75, .3); color: #c8def8; }
	.voice-status-heading { display: flex; align-items: center; gap: 8px; font-size: 12px; font-weight: 700; }
	.voice-status-dot { width: 8px; height: 8px; flex: 0 0 8px; border-radius: 50%; background: #ff746b; box-shadow: 0 0 0 4px rgba(255, 116, 107, .12); }
	.voice-status.is-finalizing .voice-status-dot { background: #f2a24d; box-shadow: 0 0 0 4px rgba(242, 162, 77, .12); }
	.voice-status.is-queued .voice-status-dot { background: #66aef7; box-shadow: 0 0 0 4px rgba(102, 174, 247, .13); }
	.voice-queue-cancel { min-height: 32px; margin: 0 0 0 auto; padding: 0 12px; border: 1px solid rgba(200, 222, 248, .42); border-radius: 9px; background: rgba(14, 30, 48, .72); color: #e5f1ff; font-size: 12px; font-weight: 700; line-height: 30px; }
	.voice-queue-cancel:focus-visible { outline: 2px solid rgba(102, 174, 247, .9); outline-offset: 2px; }
	.voice-duration { margin-left: auto; color: inherit; font-variant-numeric: tabular-nums; }
	.voice-preview { display: block; max-height: 68px; margin-top: 8px; overflow: hidden; color: #e7eeea; font-size: 13px; line-height: 1.55; }
	.visually-hidden { width: 1px; height: 1px; position: absolute; overflow: hidden; clip: rect(0 0 0 0); clip-path: inset(50%); white-space: nowrap; }
	.send-button { border-color: #37d39a; background: #37d39a; }
	.stop-button { background: rgba(55, 211, 154, .18); }
	.stop-square { width: 14px; height: 14px; border-radius: 3px; background: #75dfb7; }
	.composer-meta { justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-top: 7px; padding: 0 4px; }
	.composer-controls { min-width: 0; flex-wrap: wrap; gap: 4px; }
	.model-picker, .reasoning-effort-picker { min-height: 36px; padding: 0 10px; display: flex; align-items: center; gap: 5px; border-radius: 10px; color: #b7c2bc; font-size: 12px; }
	.model-picker text { max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
	.context-usage { min-width: 142px; min-height: 36px; padding: 6px 10px; display: flex; flex-direction: column; justify-content: center; gap: 5px; border: 1px solid rgba(55, 211, 154, .24); border-radius: 10px; background: rgba(15, 22, 19, .72); color: #8fdcbe; box-sizing: border-box; }
	.context-usage-copy { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 10px; line-height: 1.15; white-space: nowrap; }
	.context-usage-status { font-size: 9px; opacity: .9; }
	.context-usage-track { width: 100%; height: 3px; overflow: hidden; border-radius: 999px; background: rgba(143, 220, 190, .16); }
	.context-usage-fill { height: 100%; border-radius: inherit; background: #55d9a7; transition: width 180ms ease, background-color 180ms ease; }
	.context-usage.is-warning { border-color: rgba(242, 162, 77, .34); color: #f2b56f; }
	.context-usage.is-warning .context-usage-track { background: rgba(242, 162, 77, .16); }
	.context-usage.is-warning .context-usage-fill { background: #f2a24d; }
	.context-usage.is-danger { border-color: rgba(255, 112, 104, .38); color: #ff9b94; }
	.context-usage.is-danger .context-usage-track { background: rgba(255, 112, 104, .16); }
	.context-usage.is-danger .context-usage-fill { background: #ff7068; }
	.reasoning-effort-picker, .image-aspect-picker, .video-option-picker { min-height: 36px; padding: 0 10px; display: flex; align-items: center; gap: 5px; border-radius: 10px; color: #8fdcbe; font-size: 12px; }
	.image-aspect-picker { color: #9bc8ec; }
	.video-option-picker { color: #9bc8ec; }
	.web-search-toggle { min-height: 36px; margin: 0; padding: 0 8px 0 10px; display: flex; align-items: center; gap: 7px; border-radius: 10px; color: #9bc8ec; font-size: 12px; line-height: 1; }
	.web-search-toggle::after { border: 0; }
	.web-search-toggle:focus-visible { outline: 2px solid rgba(55, 211, 154, .7); outline-offset: 2px; }
	.web-search-toggle[disabled] { opacity: .48; }
	.web-search-track { width: 32px; height: 18px; flex: 0 0 32px; position: relative; border-radius: 999px; background: #3b4540; transition: background-color 160ms ease; }
	.web-search-thumb { width: 14px; height: 14px; position: absolute; top: 2px; left: 2px; border-radius: 50%; background: #d5ddd9; transform: translateX(0); transition: transform 160ms ease, background-color 160ms ease; }
	.web-search-toggle.is-active { color: #8fdcbe; }
	.web-search-toggle.is-active .web-search-track { background: rgba(55, 211, 154, .45); }
	.web-search-toggle.is-active .web-search-thumb { background: #75dfb7; transform: translateX(14px); }
	.composer-note { color: #64706a; font-size: 10px; text-align: right; }
	.composer-blocker { display: block; padding: 6px 6px 0; color: #8ba198; font-size: 11px; }
	.composer-error { display: block; padding: 5px 6px 0; }
	@media screen and (min-width: 768px) {
		.chat-main { padding-bottom: 0; }
		.mobile-only { display: none !important; }
	}
	@media screen and (min-width: 1024px) {
		.message-shell { padding: 38px 28px 28px; }
		.composer-wrap { padding-bottom: 18px; }
	}
	@media screen and (min-width: 768px) and (max-width: 1199px) {
		.message-shell { padding-left: 62px; }
	}
	@media screen and (max-width: 520px) {
		.composer-meta { align-items: flex-start; flex-direction: column; gap: 2px; }
		.composer-controls { max-width: 100%; }
		.model-picker text { max-width: 42vw; }
		.context-usage { min-width: min(168px, 88vw); }
		.research-row { grid-template-columns: 1fr; gap: 2px; }
		.composer-note { padding-left: 10px; text-align: left; }
	}
</style>
