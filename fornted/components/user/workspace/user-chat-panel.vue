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

			<scroll-view class="message-scroll" scroll-y :scroll-into-view="scrollTarget">
				<view class="message-shell">
					<view v-if="messagesLoading && !messages.length" class="chat-empty" role="status">
						<text>正在读取历史消息…</text>
					</view>
					<view v-else-if="!messages.length" class="chat-empty">
						<view class="chat-empty-mark">AI</view>
						<text class="chat-empty-title">有什么可以帮你？</text>
						<text class="chat-empty-copy">输入文字或添加附件。会话会在第一条完整回答成功后出现在最近列表。</text>
					</view>
					<button v-if="hasMoreMessages" class="history-more" type="button" :disabled="messagesLoading" @click="loadOlderMessages">
						{{ messagesLoading ? '加载中…' : '加载更早消息' }}
					</button>

					<view v-for="message in messages" :id="message.localId || `message-${message.messagePublicId}`" :key="message.localId || message.messagePublicId" class="message-turn">
						<view class="message-block user-message">
							<text v-if="message.contentText" class="message-text">{{ message.contentText }}</text>
							<view v-if="message.contentAttachments?.length" class="attachment-grid">
								<view v-for="attachment in message.contentAttachments" :key="attachment.attachmentId" class="attachment-card">
									<image v-if="previewImage(attachment)" class="attachment-image" :src="attachment.url" mode="aspectFill" />
									<video v-else-if="previewVideo(attachment)" class="attachment-video" :src="attachment.url" controls />
									<view v-else class="attachment-file" @click="openAttachment(attachment)">
										<uni-icons type="paperclip" size="20" color="#37d39a" />
										<text>{{ attachment.fileName }}</text>
									</view>
								</view>
							</view>
						</view>
						<view class="message-block assistant-message">
							<view class="assistant-label"><text>AI</text><text v-if="message.stopped" class="stopped-label">已停止</text></view>
							<text v-if="message.responseText" class="message-text">{{ message.responseText }}</text>
							<text v-else-if="message.streaming" class="typing-indicator">正在生成…</text>
							<text v-if="message.saving" class="saving-indicator">正在保存生成内容…</text>
							<view v-if="message.responseAttachments?.length" class="attachment-grid">
								<view v-for="attachment in message.responseAttachments" :key="attachment.attachmentId" class="attachment-card">
									<image v-if="previewImage(attachment)" class="attachment-image" :src="attachment.url" mode="aspectFit" />
									<video v-else-if="previewVideo(attachment)" class="attachment-video" :src="attachment.url" controls />
									<button v-else class="attachment-file" type="button" :disabled="attachment.state !== 'AVAILABLE'" @click="openAttachment(attachment)">
										<uni-icons type="download" size="20" color="#37d39a" />
										<text>{{ attachment.state === 'AVAILABLE' ? attachment.fileName : '生成内容保存失败' }}</text>
									</button>
								</view>
							</view>
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

			<view class="composer-wrap">
				<user-chat-attachment-list
					:attachments="pendingAttachments"
					:model="selectedModel"
					:generating="generating"
					@remove="removePending"
					@retry="retryAttachment"
				/>
				<view class="composer">
					<button class="composer-icon" type="button" aria-label="添加附件" :disabled="generating || attachmentPickerBusy || pendingAttachments.length >= 8" @click="chooseAttachments">
						<uni-icons type="plusempty" size="24" color="#dce5e0" aria-hidden="true" />
					</button>
					<textarea v-model="draft" class="composer-input" auto-height :maxlength="65536" placeholder="输入消息" :disabled="generating" @confirm="send" />
					<button v-if="generating" class="send-button stop-button" type="button" aria-label="停止生成" @click="stop">
						<view class="stop-square"></view>
					</button>
					<button v-else class="send-button" type="button" aria-label="发送消息" :disabled="!canSend" @click="send">
						<uni-icons type="arrow-up" size="22" color="#07110d" aria-hidden="true" />
					</button>
				</view>
				<view class="composer-meta">
					<view class="composer-controls">
						<picker :range="models" range-key="modelName" :value="selectedModelIndex" :disabled="generating || !models.length" @change="selectModel">
							<view class="model-picker"><text>{{ selectedModel?.modelName || '选择模型' }}</text><uni-icons type="down" size="14" color="#9ba6a0" /></view>
						</picker>
						<picker
							:range="reasoningEffortOptions"
							range-key="label"
							:value="selectedReasoningEffortIndex"
							:disabled="generating || !selectedModel"
							@change="selectReasoningEffort"
						>
							<view class="reasoning-effort-picker">
								<text>推理 · {{ selectedReasoningEffortLabel }}</text>
								<uni-icons type="down" size="14" color="#9ba6a0" />
							</view>
						</picker>
					</view>
					<text class="composer-note">模型可能会出错，请核查重要信息。</text>
				</view>
				<text v-if="pendingAttachments.length && !canSend" class="composer-blocker" role="status">{{ sendBlockedReason }}</text>
				<text v-if="composerError" class="composer-error" role="alert">{{ composerError }}</text>
			</view>
		</view>
</template>

<script>
	import { aiModelApi } from '@/common/aimodel/ai-model-api.js'
	import { clientPlatform } from '@/common/auth/config.js'
	import { aiConversationApi } from '@/common/aichat/ai-conversation-api.js'
	import { aiConversationErrorMessage } from '@/common/aichat/ai-conversation-error-presentation.js'
	import { chooseConversationFiles } from '@/common/aichat/ai-conversation-file-picker.js'
	import {
		openAiConversationGenerationStream,
		openAiConversationStream
	} from '@/common/aichat/ai-conversation-stream.js'
	import {
		asyncGenerationEnabled,
		listActiveGenerations,
		listPendingGenerationRequests,
		markGenerationTerminal,
		registerPendingGeneration,
		registerGeneration,
		subscribeGeneration,
		updateGeneration
	} from '@/common/aichat/ai-conversation-generation-manager.js'
	import { createAiConversationTextDrain } from '@/common/aichat/ai-conversation-text-drain.js'
	import { createAiConversationStreamDiagnostics } from '@/common/aichat/ai-conversation-stream-diagnostics.js'
	import { reportAiConversationStreamDiagnostics } from '@/common/aichat/ai-conversation-stream-diagnostics-reporter.js'
	import { createAiConversationLifecycleDiagnostics } from '@/common/aichat/ai-conversation-lifecycle-diagnostics.js'
	import { uploadConversationFiles } from '@/common/aichat/ai-conversation-upload.js'
	import {
		ATTACHMENT_UPLOAD_STATES,
		attachmentCategory,
		createPendingAttachment,
		deriveSendGate,
		validateAttachmentSelection
	} from '@/common/aichat/ai-conversation-upload-state.js'
	import UserChatAttachmentList from './user-chat-attachment-list.vue'
	import {
		appendLocalMessage,
		clearAiConversationHistoryStale,
		discardTransientMessages,
		markAiConversationHistoryStale,
		patchLocalMessage,
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

	const MODEL_STORAGE_KEY = 'ait.user.ai.selected-model.v1'
	const REASONING_EFFORT_STORAGE_KEY = 'ait.user.ai.reasoning-effort.v1'
	const REASONING_EFFORT_OPTIONS = Object.freeze([
		Object.freeze({ value: 1, label: 'Low' }),
		Object.freeze({ value: 2, label: 'Medium' }),
		Object.freeze({ value: 3, label: 'High' }),
		Object.freeze({ value: 4, label: 'Extra High' }),
		Object.freeze({ value: 5, label: 'Ultra' })
	])
	const CANCEL_RETRY_DELAYS = Object.freeze([0, 250, 750])

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

	export default {
		components: { UserChatAttachmentList },
		data() {
			return {
				...readAiConversationStore(),
				draft: '',
				models: [],
				selectedModelPublicId: '',
				selectedReasoningEffortLevel: 2,
				pendingAttachments: [],
				attachmentPickerBusy: false,
				localPreviewUrls: new Map(),
				generating: false,
				activeStream: null,
				activeGenerationPublicId: '',
				activeGenerationSubscription: null,
				cancelRequestedBeforeGenerationId: false,
				generationCancelDispatching: false,
				generationCancelSentFor: '',
				textDrain: null,
				streamDiagnostics: null,
				lifecycleDiagnostics: null,
				terminalPresentationPending: false,
				activeLocalId: '',
				composerError: '',
				scrollTarget: '',
				historyResyncing: false,
				modelsLoading: false
			}
		},
		beforeUnmount() {
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
			this.streamDiagnostics?.finish?.('UNMOUNT')
			this.lifecycleDiagnostics?.finish?.('UNMOUNT')
			this.cancelPendingUploads()
			this.releasePreviewUrls(this.pendingAttachments.map(file => file.path))
			this.releaseAllLocalPreviews()
		},
		computed: {
			selectedModel() { return this.models.find(model => model.publicId === this.selectedModelPublicId) || null },
			selectedModelIndex() { return Math.max(0, this.models.findIndex(model => model.publicId === this.selectedModelPublicId)) },
			reasoningEffortOptions() {
				const supported = new Set(
					this.selectedModel?.supportedReasoningEffortLevels || [])
				return REASONING_EFFORT_OPTIONS.filter(option =>
					supported.has(option.value))
			},
			selectedReasoningEffortIndex() {
				return Math.max(0, this.reasoningEffortOptions.findIndex(option =>
					option.value === this.selectedReasoningEffortLevel))
			},
			selectedReasoningEffortLabel() {
				return this.reasoningEffortOptions.find(option =>
					option.value === this.selectedReasoningEffortLevel)?.label || 'Medium'
			},
			sendGate() {
				return deriveSendGate({
					model: this.selectedModel,
					text: this.draft,
					attachments: this.pendingAttachments,
					generating: this.generating
				})
			},
			canSend() { return this.sendGate.allowed },
			sendBlockedReason() { return this.sendGate.reason },
			activeConversationTitle() {
				if (!this.currentConversationPublicId) return '新聊天'
				return this.conversations.find(item => item.conversationPublicId === this.currentConversationPublicId)?.title || '未命名对话'
			}
		},
		methods: {
			onAuthenticatedPageReady() {
				this.applyStore(readAiConversationStore())
				if (!this.models.length && !this.modelsLoading) this.loadModels()
				if (asyncGenerationEnabled()) this.restoreActiveGenerations()
			},
			applyStore(value) {
				Object.assign(this, value)
				this.$emit('conversation-state-change', value)
			},
			syncStore() {
				this.applyStore(readAiConversationStore())
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
					uni.setStorageSync(REASONING_EFFORT_STORAGE_KEY, level)
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
				if (this.generating) {
					if (asyncGenerationEnabled()) this.releaseCurrentGenerationView()
					else this.stop()
				}
				this.cancelPendingUploads()
				this.releasePreviewUrls(this.pendingAttachments.map(file => file.path))
				this.releaseAllLocalPreviews()
				this.applyStore(resetCurrentConversation())
				this.draft = ''
				this.pendingAttachments = []
				this.composerError = ''
			},
			async openConversation(publicId) {
				if (publicId === this.currentConversationPublicId) return
				if (this.generating && asyncGenerationEnabled()) this.releaseCurrentGenerationView()
				else if (this.generating) return
				this.releaseAllLocalPreviews()
				this.applyStore(selectConversation(publicId))
				await this.reloadCurrentMessages()
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
			async loadOlderMessages() {
				if (!this.currentConversationPublicId || !this.nextBefore || this.messagesLoading) return
				this.applyStore(setMessagesLoading(true))
				try { this.applyStore(setMessagePage(await aiConversationApi.messages(this.currentConversationPublicId, { before: this.nextBefore }), true)) }
				catch (error) { this.composerError = error.message || '更早消息加载失败。'; this.applyStore(setMessagesLoading(false)) }
			},
			selectModel(event) {
				const model = this.models[Number(event.detail.value)]
				if (!model) return
				this.selectedModelPublicId = model.publicId
				uni.setStorageSync(MODEL_STORAGE_KEY, model.publicId)
				const level = this.normalizeReasoningEffortForModel(model)
				uni.setStorageSync(REASONING_EFFORT_STORAGE_KEY, level)
			},
			normalizeReasoningEffortForModel(
				model,
				candidate = this.selectedReasoningEffortLevel) {
				const supported = model?.supportedReasoningEffortLevels || []
				const normalizedCandidate = Number(candidate)
				const fallback = supported.includes(model?.defaultReasoningEffortLevel)
					? model.defaultReasoningEffortLevel
					: 2
				const level = supported.includes(normalizedCandidate)
					? normalizedCandidate
					: fallback
				this.selectedReasoningEffortLevel = level
				return level
			},
			selectReasoningEffort(event) {
				const option =
					this.reasoningEffortOptions[Number(event.detail.value)]
				if (!option) return
				const level = option.value
				this.selectedReasoningEffortLevel = level
				uni.setStorageSync(REASONING_EFFORT_STORAGE_KEY, level)
			},
			async chooseAttachments() {
				if (this.attachmentPickerBusy) return
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
						url: file.path,
						state: 'AVAILABLE'
					})),
					responseText: '', responseAttachments: [], streaming: true, saving: false, stopped: false, error: ''
				}))
				this.draft = ''
				this.pendingAttachments = []
				this.generating = true
				this.textDrain?.close?.()
				this.terminalPresentationPending = false
				this.cancelRequestedBeforeGenerationId = false
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
				this.scrollBottom()
				const command = {
					conversationPublicId: this.currentConversationPublicId,
					idempotencyKey: uuidV4(),
					localId,
					inputText: text,
					body: {
						modelPublicId: this.selectedModelPublicId,
						reasoningEffortLevel: this.selectedReasoningEffortLevel,
						input: { text, attachments: attachmentRefs }
					}
				}
				if (asyncGenerationEnabled()) registerPendingGeneration({
					idempotencyKey: command.idempotencyKey,
					conversationPublicId: command.conversationPublicId,
					localId: command.localId,
					inputText: command.inputText
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
					await this.activeStream.completed
				} catch (error) {
					if (this.generating && this.activeLocalId === localId) {
						const message = aiConversationErrorMessage(error)
						this.finishTextPresentation(() => {
							this.applyStore(patchLocalMessage(localId, { streaming: false, saving: false, error: message }))
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
			onStreamEvent(localId, event) {
				// 后台任务由全局 Manager 持续收集；旧会话事件不能覆盖用户当前打开会话的局部状态。
				if (asyncGenerationEnabled() && localId !== this.activeLocalId) return
				if (event.type === 'accepted') {
					this.applyStore(setAcceptedConversation(event.data.conversationPublicId))
					if (event.data.generationPublicId) {
						this.activeGenerationPublicId = event.data.generationPublicId
						this.bindCurrentGenerationView(event.data.generationPublicId, localId)
					}
				} else if (event.type === 'snapshot') {
					this.applyStore(patchLocalMessage(localId, {
						responseText: String(event.data?.text || ''),
						streaming: true
					}))
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
					this.textDrain?.push?.(event.data.text || '')
				} else if (event.type === 'completed' && event.data?.generationPublicId) {
					const generationPublicId = event.data.generationPublicId
					markGenerationTerminal(generationPublicId, event.data.status || 'COMPLETED')
					this.finishTextPresentation(() => {
						const failed = event.data.terminalType
							&& event.data.terminalType !== 'COMPLETED'
						this.applyStore(patchLocalMessage(localId, {
							streaming: false,
							saving: false,
							stopped: String(event.data.terminalType || '').includes('CANCELLED'),
							error: failed && !String(event.data.terminalType).includes('CANCELLED')
								? '模型响应未能完成，预扣额度已按终态处理。' : ''
						}))
						this.$emit('conversation-completed')
						this.reloadCurrentMessages()
						this.streamDiagnostics?.finish?.('COMPLETE')
						this.$nextTick(() =>
							this.lifecycleDiagnostics?.finish?.('COMPLETE'))
					})
				} else if (event.type === 'completed') {
					this.finishTextPresentation(() => {
						this.applyStore(patchLocalMessage(localId, {
							messagePublicId: event.data.messagePublicId,
							contentAttachments: event.data.inputAttachments || [],
							responseAttachments: event.data.responseAttachments || [],
							streaming: false,
							saving: false,
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
					this.finishTextPresentation(() => {
						const message = aiConversationErrorMessage(
							event.data,
							'模型响应未能完成'
						)
						this.applyStore(patchLocalMessage(localId, {
							streaming: false,
							error: message
						}))
						this.composerError = message
						this.streamDiagnostics?.finish?.('SSE_ERROR')
						this.$nextTick(() =>
							this.lifecycleDiagnostics?.finish?.('SSE_ERROR'))
					})
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
			async stop(reason = 'USER_STOP') {
				if (!this.generating) return
				const cancelReason = typeof reason === 'string'
					? reason : 'USER_STOP'
				if (this.currentConversationPublicId) {
					this.applyStore(markAiConversationHistoryStale())
				}
				const cancelledLocalId = this.activeLocalId
				const current = this.messages.find(message =>
					message.localId === cancelledLocalId)
				if (asyncGenerationEnabled()) {
					// Stop 是明确业务取消；响应头尚未返回时保留意图，拿到 Generation ID 后立即补发一次。
					this.cancelRequestedBeforeGenerationId = true
					this.applyStore(patchLocalMessage(cancelledLocalId, {
						saving: true,
						error: '取消处理中…'
					}))
					await this.requestGenerationCancellation(this.activeGenerationPublicId)
				}
				if (!asyncGenerationEnabled()) this.activeStream?.close?.(cancelReason, {
					hasVisibleOutput: Boolean(current?.responseText),
					emittedTextCharacters: String(current?.responseText || '').length
				})
				// 新链路保留全局 Observer 接收唯一终态，只解除当前组件订阅并停止本地文本渲染。
				if (asyncGenerationEnabled()) {
					// 淇濈暀褰撳墠 Generation 鐨勬湰鍦拌闃咃紝鐢ㄤ簬鏀跺埌鍚庡彴鐨勮鍗曠粓鎬佺‘璁わ紱
					// Stop 鍙殏鍋滄湰鍦扮敤鎴风晫闈氦浜掞紝涓嶆妸 Observer 鏂紑褰撴垚妯″瀷鍙栨秷銆? 
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
			scrollBottom() { this.scrollTarget = ''; this.$nextTick(() => { this.scrollTarget = 'message-bottom' }) },
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
				if (!asyncGenerationEnabled()) {
					if (this.generating) this.stop('PAGE_HIDDEN')
					this.applyStore(discardTransientMessages())
					this.releaseAllLocalPreviews()
				}
			},
			handlePageUnload() {
				if (this.activeStream && this.currentConversationPublicId) {
					this.applyStore(markAiConversationHistoryStale())
				}
				const current = this.messages.find(message =>
					message.localId === this.activeLocalId)
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
							responseText: task.responseText || '',
							streaming: !['SETTLED', 'REFUNDED', 'RECONCILE_REQUIRED', 'COMPLETED']
								.includes(task.status),
							saving: task.status === 'CANCEL_REQUESTED',
							stopped: String(task.terminalType || '').includes('CANCELLED')
						}))
						if (['SETTLED', 'REFUNDED', 'RECONCILE_REQUIRED', 'COMPLETED']
								.includes(task.status)) {
							this.generating = false
						}
					})
			}
		}
	}
</script>

<style lang="scss">
	@import '@/common/ui/user-material.scss';
	.chat-header, .composer-meta, .composer-controls, .assistant-label { display: flex; align-items: center; }
	.icon-button, .history-more, .composer-icon, .send-button, .attachment-file { @include user-frosted-control; box-sizing: border-box; }
	.icon-button { width: 48px; height: 48px; margin: 0; padding: 0; border-radius: 14px; }
	.history-more { min-height: 44px; margin: 8px auto; padding: 0 16px; color: #dce5e0; }
	.chat-main { min-width: 0; min-height: 0; height: 100%; display: grid; grid-template-rows: auto minmax(0, 1fr) auto; padding-bottom: calc(72px + env(safe-area-inset-bottom)); color: #f3f5f4; box-sizing: border-box; }
	.chat-header { min-height: 68px; padding: max(8px, env(safe-area-inset-top)) 14px 8px; gap: 10px; border-bottom: 1px solid #29302c; background: rgba(11, 13, 12, .88); backdrop-filter: blur(16px); box-sizing: border-box; }
	.chat-header-copy { min-width: 0; flex: 1; display: flex; flex-direction: column; align-items: center; }
	.chat-header-balance { width: 48px; height: 48px; flex: 0 0 48px; }
	.chat-header-title { max-width: 100%; overflow: hidden; font-size: 15px; font-weight: 700; text-overflow: ellipsis; white-space: nowrap; }
	.chat-header-subtitle { margin-top: 2px; color: #8b9690; font-size: 11px; }
	.message-scroll { min-height: 0; height: 100%; }
	.message-shell { width: min(100%, 780px); min-height: 100%; margin: 0 auto; padding: 28px 18px 22px; box-sizing: border-box; }
	.chat-empty { min-height: 48vh; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; }
	.chat-empty-mark { width: 58px; height: 58px; display: flex; align-items: center; justify-content: center; border: 1px solid rgba(55, 211, 154, .38); border-radius: 18px; background: rgba(55, 211, 154, .12); color: #72e1b8; font-weight: 800; }
	.chat-empty-title { margin-top: 18px; font-size: 25px; font-weight: 750; }
	.chat-empty-copy { max-width: 440px; margin-top: 9px; color: #8b9690; line-height: 1.6; }
	.message-turn { margin-bottom: 30px; }
	.message-block { max-width: 92%; padding: 14px 16px; border-radius: 16px; box-sizing: border-box; }
	.user-message { margin-left: auto; background: #1b211e; border: 1px solid #303a35; }
	.assistant-message { margin-top: 12px; padding-left: 2px; background: transparent; }
	.assistant-label { gap: 8px; margin-bottom: 8px; color: #37d39a; font-size: 12px; font-weight: 800; letter-spacing: .8px; }
	.stopped-label { color: #f2a24d; font-weight: 600; letter-spacing: 0; }
	.message-text { color: #edf3f0; font-size: 15px; line-height: 1.72; white-space: pre-wrap; word-break: break-word; }
	.typing-indicator, .saving-indicator { color: #8b9690; font-size: 13px; }
	.message-error, .message-warning, .composer-error { color: #f2a24d; font-size: 13px; }
	.message-warning { display: block; margin-top: 9px; }
	.attachment-grid { margin-top: 10px; display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 8px; }
	.attachment-card { min-width: 0; overflow: hidden; border: 1px solid #313a35; border-radius: 12px; background: #141816; }
	.attachment-image, .attachment-video { width: 100%; height: 180px; display: block; }
	.attachment-file { width: 100%; min-height: 54px; margin: 0; padding: 10px 12px; justify-content: flex-start; gap: 9px; border: 0; border-radius: 0; color: #dce5e0; text-align: left; }
	.attachment-file text { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
	.message-bottom { height: 1px; }
	.composer-wrap { width: min(100%, 820px); margin: 0 auto; padding: 8px 14px calc(10px + env(safe-area-inset-bottom)); box-sizing: border-box; }
	.composer { min-height: 58px; padding: 7px; display: flex; align-items: flex-end; gap: 6px; border: 1px solid rgba(99, 117, 107, .55); border-radius: 18px; background: rgba(30, 35, 32, .84); box-shadow: inset 0 1px rgba(255, 255, 255, .05); backdrop-filter: blur(20px) saturate(115%); }
	.composer-icon, .send-button { width: 44px; height: 44px; min-height: 44px; margin: 0; padding: 0; flex-shrink: 0; border-radius: 13px; }
	.composer-input { min-height: 42px; max-height: 160px; flex: 1; padding: 10px 6px; color: #f3f5f4; font-size: 15px; line-height: 1.45; box-sizing: border-box; }
	.send-button { border-color: #37d39a; background: #37d39a; }
	.stop-button { background: rgba(55, 211, 154, .18); }
	.stop-square { width: 14px; height: 14px; border-radius: 3px; background: #75dfb7; }
	.composer-meta { justify-content: space-between; gap: 12px; margin-top: 7px; padding: 0 4px; }
	.composer-controls { min-width: 0; gap: 4px; }
	.model-picker, .reasoning-effort-picker { min-height: 36px; padding: 0 10px; display: flex; align-items: center; gap: 5px; border-radius: 10px; color: #b7c2bc; font-size: 12px; }
	.model-picker text { max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
	.reasoning-effort-picker { color: #8fdcbe; }
	.composer-note { color: #64706a; font-size: 10px; text-align: right; }
	.composer-blocker { display: block; padding: 6px 6px 0; color: #8ba198; font-size: 11px; }
	.composer-error { display: block; padding: 5px 6px 0; }
	@media screen and (min-width: 1024px) {
		.chat-main { padding-bottom: 0; }
		.mobile-only { display: none !important; }
		.message-shell { padding: 38px 28px 28px; }
		.composer-wrap { padding-bottom: 18px; }
	}
	@media screen and (max-width: 520px) {
		.composer-meta { align-items: flex-start; flex-direction: column; gap: 2px; }
		.composer-controls { max-width: 100%; }
		.model-picker text { max-width: 42vw; }
		.composer-note { padding-left: 10px; text-align: left; }
	}
</style>
