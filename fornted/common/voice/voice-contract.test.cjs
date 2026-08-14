const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function read(relativePath) {
	return fs.readFileSync(path.resolve(__dirname, '..', '..', relativePath), 'utf8')
}

test('H5 permits only self microphone access and explicit secure voice sockets', () => {
	const headers = read('public/_headers')
	const index = read('index.html')
	const connectSource = index.match(/connect-src ([^;]+)/)?.[1] || ''

	assert.match(headers, /microphone=\(self\)/)
	assert.match(connectSource, /wss:\/\/localhost:6655/)
	assert.match(connectSource, /wss:\/\/niko000o\.site/)
	assert.doesNotMatch(connectSource, /wss:\/\/api\.niko000o\.site/)
	assert.doesNotMatch(connectSource, /(?:^|\s)ws:\/\//)
	const ticketApi = read('common/voice/voice-ticket-api.js')
	assert.match(ticketApi, /wss:\/\/\$\{window\.location\.host\}\/ws\/voice/)
	assert.doesNotMatch(ticketApi, /wss:\/\/api\.niko000o\.site\/ws\/voice/)
})

test('Android derives its voice socket from the production primary-domain API base', () => {
	const config = read('common/auth/config.js')
	const ticketApi = read('common/voice/voice-ticket-api.js')
	const session = read('common/voice/voice-websocket-session.js')

	assert.match(config, /let authApiBaseUrl = 'https:\/\/niko000o\.site'/)
	assert.doesNotMatch(config, /https:\/\/api\.niko000o\.site/)
	assert.match(ticketApi, /voiceWebSocketUrl\(apiBaseUrl = AUTH_API_BASE_URL\)/)
	assert.doesNotMatch(ticketApi, /\bnew URL\s*\(/)
	assert.match(ticketApi, /SECURE_API_ORIGIN_PATTERN/)
	assert.match(ticketApi, /return `wss:\/\/\$\{authority\}\/ws\/voice`/)
	assert.doesNotMatch(ticketApi, /(?:'|"|`)ws:\/\//)
	assert.doesNotMatch(ticketApi, /wss:\/\/api\.niko000o\.site\/ws\/voice/)
	assert.match(session, /#ifdef APP-PLUS[\s\S]*X-Client-Platform'[\s\S]*'ANDROID'/)
	assert.match(session, /protocols:\s*\[[\s\S]*ait-voice-v2[\s\S]*ait-ticket\./)
	assert.doesNotMatch(session, /type:\s*'session\.start'[\s\S]{0,180}ticket:/)
})

test('Android uses Base64 only across the UTS bridge and keeps WebSocket audio binary', () => {
	const manifest = read('manifest.json')
	const recorderInterface = read('uni_modules/ait-voice-recorder/utssdk/interface.uts')
	const recorder = read('uni_modules/ait-voice-recorder/utssdk/app-android/index.uts')
	const wrapper = read('common/voice/voice-recorder.js')
	const session = read('common/voice/voice-websocket-session.js')
	const handler = read('../ai-temperate-web/src/main/java/com/example/temperate/web/user/voice/VoiceWebSocketHandler.java')

	assert.match(manifest, /android\.permission\.RECORD_AUDIO/)
	assert.match(manifest, /"Record"\s*:\s*\{\}/)
	assert.match(recorder, /AudioSource\.VOICE_RECOGNITION/)
	assert.match(recorder, /ShortArray\(SAMPLES_PER_FRAME\)/)
	assert.match(recorder, /ByteOrder\.LITTLE_ENDIAN/)
	assert.match(recorder, /ByteBuffer\.allocate\(frameBytes\)/)
	assert.doesNotMatch(recorder, /ByteBuffer\.wrap\(/)
	assert.match(recorder, /Base64\.encodeToString\(frameByteArray, Base64\.NO_WRAP\)/)
	assert.match(recorder, /options\.onFrame\(payloadBase64, frameBytes, frameSequence\)/)
	assert.doesNotMatch(recorder, /ArrayBuffer\.fromByteBuffer/)
	assert.match(recorderInterface, /diagnosticRunId:\s*string/)
	assert.match(recorderInterface, /onFrame:\s*\(\s*payloadBase64:\s*string,\s*declaredByteLength:\s*number,\s*frameSequence:\s*number\s*\)\s*=>\s*void/)
	assert.match(wrapper, /const MAX_ANDROID_PCM16_FRAME_BYTES = 3200/)
	assert.match(wrapper, /uni\.base64ToArrayBuffer\(payloadBase64\)/)
	assert.match(wrapper, /decodedByteLength !== declaredByteLength/)
	assert.match(wrapper, /event=voice_android_pcm_bridge phase=REJECTED/)
	assert.match(wrapper, /VOICE_AUDIO_BRIDGE_INVALID/)
	assert.match(wrapper, /phase=START_RETURNED/)
	assert.match(wrapper, /phase=FRAME_CALLBACK_ENTERED/)
	assert.match(wrapper, /phase=LEASE_RENEW_FAILED/)
	assert.match(wrapper, /phase=FAILURE_REPORTED/)
	assert.match(wrapper, /phase=STOP_INVOKE_FAILED/)
	assert.doesNotMatch(wrapper, /new Uint8Array\(frame\)/)
	assert.match(session, /VOICE_AUDIO_BRIDGE_INVALID:\s*'Android 音频通道转换失败，请重新启动录音。'/)
	assert.match(session, /format:\s*'pcm_s16le'/)
	assert.match(session, /sampleRate:\s*16000/)
	assert.match(session, /channels:\s*1/)
	assert.match(handler, /frameBytes <= 0 \|\| frameBytes % 2 != 0/)
	assert.doesNotMatch(`${session}\n${handler}`, /Base64|base64/)
	assert.doesNotMatch(`${recorder}\n${wrapper}`, /MP3|AAC/)
	assert.doesNotMatch(`${recorder}\n${wrapper}`, /payloadBase64=|pcmBytes=|audioContent=/)
	assert.doesNotMatch(session, /diagnosticRunId|frameSequence/)
})

test('Android UTS recorder keeps repeated frame callbacks alive on its exported function', () => {
	const recorderInterface = read('uni_modules/ait-voice-recorder/utssdk/interface.uts')
	const recorder = read('uni_modules/ait-voice-recorder/utssdk/app-android/index.uts')
	const wrapper = read('common/voice/voice-recorder.js')

	assert.doesNotMatch(recorderInterface, /export function requestRecordPermission/)
	assert.doesNotMatch(recorderInterface, /export function startRecording/)
	assert.match(recorderInterface, /export type RequestRecordPermissionApi\s*=\s*\(options: AitVoicePermissionOptions\) => void/)
	assert.match(recorderInterface, /export type StartRecordingApi\s*=\s*\(options: AitVoiceRecorderOptions\) => number/)
	assert.match(recorderInterface, /export type RenewRecordingLeaseApi\s*=\s*\(recordingId: number\) => boolean/)
	assert.match(recorderInterface, /export type StopRecordingApi\s*=\s*\(recordingId: number\) => boolean/)
	assert.doesNotMatch(recorderInterface, /AitVoiceRecorderSession|renewLease:\s*\(\)\s*=>\s*void|stop:\s*\(\)\s*=>\s*void/)
	assert.match(recorder, /export const requestRecordPermission: RequestRecordPermissionApi\s*=\s*function\(options: AitVoicePermissionOptions\): void/)
	assert.match(recorder, /@UTSJS\.keepAlive\s+export function startRecording\(\s*options: AitVoiceRecorderOptions\s*\): number/)
	assert.doesNotMatch(recorder, /export const startRecording/)
	assert.match(recorder, /export const renewRecordingLease: RenewRecordingLeaseApi\s*=\s*function\(recordingId: number\): boolean/)
	assert.match(recorder, /export const stopRecording: StopRecordingApi\s*=\s*function\(recordingId: number\): boolean/)
	assert.doesNotMatch(recorder, /return\s*\{[\s\S]{0,240}(?:renewLease|stop):\s*\(\)/)
	assert.match(recorder, /const LEASE_TIMEOUT_MS: Long = 2500/)
	assert.match(recorder, /lastLeaseAt\.set\(SystemClock\.elapsedRealtime\(\)\)/)
	assert.match(recorder, /phase=LEASE_EXPIRED/)
	assert.match(recorder, /activeRecorderRenewLease = \(\) =>/)
	assert.match(recorder, /activeRecorderReplaceStop = \(\) => requestStop\('REPLACED_BY_NEW_SESSION'\)/)
	assert.match(recorder, /activeRecorderStop = \(\) => requestStop\('CLIENT_CONTROL'\)/)
	assert.match(recorder, /requestedRecordingId != activeRecordingId/)
	assert.match(recorder, /stopped\.compareAndSet\(false, true\)/)
	assert.match(recorder, /callbacksAvailable\.set\(false\)/)
	assert.match(wrapper, /ANDROID_RECORDING_LEASE_RENEWAL_MS = 500/)
	assert.match(wrapper, /renewRecordingLease\(recordingId\)/)
	assert.match(wrapper, /stopRecording\(recordingId\)/)
	assert.doesNotMatch(wrapper, /nativeSession|\.renewLease\(\)|nativeSession\.stop\(\)/)
	assert.match(wrapper, /import\s*\{\s*requestRecordPermission,\s*startRecording,\s*renewRecordingLease,\s*stopRecording\s*\}\s*from '@\/uni_modules\/ait-voice-recorder'/)
	assert.match(recorder, /phase=START_CALLBACK_DISPATCH_ATTEMPT/)
	assert.match(recorder, /phase=NATIVE_FRAME_DISPATCH_FAILED/)
	assert.match(recorder, /phase=LEASE_RENEWED/)
	assert.match(recorder, /phase=RELEASED/)
	assert.match(recorder, /UTSAndroid\.getJavaClass\(error\)\.simpleName/)
	assert.doesNotMatch(recorder, /error\.getClass\(\)/)
	assert.match(recorder, /renewalCount\.incrementAndGet\(\)\.toInt\(\)/)
})

test('Android UTS recorder pins native AudioRecord arguments to Int', () => {
	const recorder = read('uni_modules/ait-voice-recorder/utssdk/app-android/index.uts')

	assert.match(recorder, /const SAMPLE_RATE: Int = 16000/)
	assert.match(recorder, /const SAMPLES_PER_FRAME: Int = 1600/)
	assert.match(recorder, /const BYTES_PER_SAMPLE: Int = 2/)
	assert.match(recorder, /const BYTES_PER_FRAME: Int = 3200/)
	assert.match(recorder, /AudioRecord\.getMinBufferSize\(\s*SAMPLE_RATE,/)
	assert.match(recorder, /ShortArray\(SAMPLES_PER_FRAME\)/)
	assert.match(recorder, /let sampleIndex: Int = 0/)
	assert.match(recorder, /putShort\(samples\[sampleIndex\]\)/)
	assert.match(recorder, /const frameByteArray = frameBuffer\.array\(\)/)
	assert.doesNotMatch(recorder, /putShort\(samples\[sampleIndex\]\.toInt\(\)\)/)
})

test('chat composer keeps partial text transient and commits only the final transcript', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const partialBranch = panel.slice(
		panel.indexOf("event?.type === 'transcript.partial'"),
		panel.indexOf("event?.type === 'input.limit_reached'"))
	const finalMethod = panel.slice(
		panel.indexOf('acceptVoiceTranscript(text, owner)'),
		panel.indexOf('async handleVoiceFailure'))

	assert.match(partialBranch, /voicePartialText/)
	assert.match(partialBranch, /voiceTranscriptPresenter\?\.setTarget\(/)
	assert.doesNotMatch(partialBranch, /this\.draft\s*=/)
	assert.match(finalMethod, /this\.draft\s*=\s*appendVoiceTranscriptToDraft\(\s*this\.voiceDraftBase/)
	assert.doesNotMatch(finalMethod, /this\.send\(/)
	assert.match(panel, /voiceDraftBase:\s*''/)
	assert.match(panel, /voiceDisplayedPartialText:\s*''/)
	assert.match(panel, /voiceTranscriptPresenter:\s*null/)
	assert.match(panel, /voiceTranscriptTailSequence:\s*0/)
	assert.match(panel, /voiceSessionEpoch:\s*0/)
	assert.match(panel, /voiceMaximumDurationMs:\s*300000/)
	assert.match(panel, /aria-label="聊天消息"/)
	assert.match(panel, /class="voice-cancel-button"[\s\S]*aria-label="放弃语音输入"/)
	assert.match(panel, /class="voice-commit-button"[\s\S]*aria-label="停止录音并生成文字"/)
	assert.doesNotMatch(panel, /class="voice-status"/)
	assert.doesNotMatch(panel, /class="voice-queue-cancel"/)
	assert.match(panel, /event\?\.type === 'session\.queued'/)
	assert.match(panel, /abortVoiceInput\('USER_DISCARD'\)/)
})

test('active voice composer exposes one shared live transcript row without moving controls', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const rowStart = panel.indexOf('class="voice-transcript-row"')
	const rowEnd = panel.indexOf('</view>', panel.indexOf('</scroll-view>', rowStart))
	const rowTemplate = panel.slice(rowStart, rowEnd)

	assert.ok(rowStart >= 0, 'voice transcript row must exist')
	assert.match(rowTemplate, /<user-thinking-orb/)
	assert.match(rowTemplate, /<scroll-view[\s\S]*v-if="voiceInteractionActive"/)
	assert.match(rowTemplate, /class="voice-live-transcript"/)
	assert.match(rowTemplate, /:scroll-into-view="voiceTranscriptTailAnchorId"/)
	assert.match(rowTemplate, /\{\{ voiceLiveTranscriptLabel \}\}/)
	assert.match(rowTemplate, /:id="voiceTranscriptTailAnchorId"/)
	assert.ok(
		rowTemplate.indexOf('<user-thinking-orb') < rowTemplate.indexOf('<scroll-view'),
		'voice orb must stay before live transcript text')
	assert.match(rowTemplate, /<textarea[\s\S]*v-if="!voiceInteractionActive"/)
	assert.match(panel, /\.voice-live-transcript\s*\{[^}]*white-space:\s*nowrap/s)
	assert.match(panel, /\.voice-live-transcript\s*\{[^}]*overflow:\s*hidden/s)
	assert.match(panel, /\.voice-live-transcript-text\s*\{[^}]*white-space:\s*nowrap/s)
	assert.doesNotMatch(rowTemplate, /#ifdef APP-PLUS|#ifndef APP-PLUS/)
})

test('voice transcript presenter is session owned and cleared on every terminal path', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const startMethod = panel.slice(
		panel.indexOf('async startVoiceInput()'),
		panel.indexOf('startVoiceTimer()', panel.indexOf('async startVoiceInput()')))
	const abortMethod = panel.slice(
		panel.indexOf("abortVoiceInput(source = 'USER_DISCARD')"),
		panel.indexOf("completeVoiceInput(source = 'TRANSCRIPT_FINAL'"))
	const completeMethod = panel.slice(
		panel.indexOf("completeVoiceInput(source = 'TRANSCRIPT_FINAL'"),
		panel.indexOf('onAuthenticatedPageReady()'))

	assert.match(panel, /createVoiceLiveTranscriptPresenter/)
	assert.match(startMethod, /resetVoiceTranscriptPresenter\(voiceEpoch\)/)
	assert.match(panel, /onDisplay:\s*text\s*=>[\s\S]*voiceSessionEpoch !== voiceEpoch[\s\S]*voiceDisplayedPartialText = String\(text \|\| ''\)/)
	assert.match(abortMethod, /disposeVoiceTranscriptPresenter\(\)/)
	assert.match(completeMethod, /disposeVoiceTranscriptPresenter\(\)/)
	assert.match(panel, /disposeVoiceTranscriptPresenter\(\)\s*\{[\s\S]*this\.voicePartialText = ''[\s\S]*this\.voiceDisplayedPartialText = ''/)
	assert.match(panel, /beforeUnmount\(\)[\s\S]*abortVoiceInput\('COMPONENT_UNMOUNT'\)/)
	assert.doesNotMatch(panel, /console\.(?:log|warn|error)\([^)]*voice(?:Partial|DisplayedPartial)Text/)
})

test('voice status row uses a single unified Canvas waveform on all platforms', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const statusStart = panel.indexOf('class="voice-inline-status"')
	const statusEnd = panel.indexOf('</view>', statusStart)
	const statusTemplate = panel.slice(statusStart, statusEnd)

	assert.ok(statusStart >= 0, 'voice inline status row must exist')
	assert.match(statusTemplate, /class="visually-hidden"/)
	assert.match(statusTemplate, /\{\{ voiceStatusLabel \}\}/)
	assert.doesNotMatch(statusTemplate, /class="voice-status-label"/)
	assert.match(statusTemplate, /<user-voice-waveform/)
	assert.match(statusTemplate, /:session-epoch="voiceSessionEpoch"/)
	assert.match(statusTemplate, /:packet="voiceWaveformPacket"/)
	assert.match(statusTemplate, /:reduced="motionReduced"/)
	assert.doesNotMatch(statusTemplate, /class="voice-duration"/)
	assert.doesNotMatch(statusTemplate, /<user-thinking-orb/)
	// APP-PLUS 与 H5 统一使用 <user-voice-waveform>，不再条件编译独立 Android 组件。
	assert.doesNotMatch(statusTemplate, /<user-voice-waveform-android/)
	assert.doesNotMatch(statusTemplate, /#ifdef APP-PLUS/)
	assert.match(panel, /\.voice-inline-status\s*\{[^}]*width:\s*calc\(100% \+ 56px\)/s)
	assert.match(panel, /\.composer\s*\{[^}]*gap:\s*8px/s)
	assert.match(panel, /\.chat-main:not\(\.is-android-client\) \.composer\.is-voice-active\s*\{[^}]*gap:\s*7px/s)
	assert.match(panel, /\.voice-inline-status \.user-voice-waveform\s*\{[^}]*width:\s*100%/s)
	assert.match(panel,
		/\.chat-main:not\(\.is-android-client\) \.composer\.is-voice-active \.voice-inline-status\s*\{[^}]*width:\s*calc\(100% \+ 45px\)[^}]*margin-left:\s*-45px/s)
	assert.doesNotMatch(panel, /class="voice-status"/)
})

test('voice duration sits above stop and freezes before finalizing begins', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const cancelStart = panel.indexOf('class="voice-cancel-button"')
	const stackStart = panel.indexOf('class="voice-commit-stack"')
	const stackEnd = panel.indexOf('</view>', stackStart)
	const stackTemplate = panel.slice(stackStart, stackEnd)
	const finalizeStart = panel.indexOf('async finalizeVoiceInput')
	const finalizeEnd = panel.indexOf('async handleVoiceEvent', finalizeStart)
	const finalizeMethod = panel.slice(finalizeStart, finalizeEnd)

	assert.ok(cancelStart >= 0, 'voice cancel button must exist')
	assert.ok(stackStart > cancelStart, 'voice stop stack must sit to the right of cancel')
	assert.match(stackTemplate, /class="voice-duration"[\s\S]*aria-hidden="true"/)
	assert.match(stackTemplate, /class="voice-commit-button"/)
	assert.ok(stackTemplate.indexOf('voice-duration') < stackTemplate.indexOf('voice-commit-button'))
	assert.match(panel, /\.voice-commit-stack\s*\{[^}]*display:\s*flex[^}]*flex-direction:\s*column/s)
	assert.doesNotMatch(panel, /voice-cancel-stack/)
	assert.match(panel, /\.voice-duration\s*\{[^}]*width:\s*44px[^}]*text-align:\s*center/s)
	assert.match(finalizeMethod, /this\.freezeVoiceTimer\(\)/)
	assert.ok(
		finalizeMethod.indexOf('this.freezeVoiceTimer()') <
			finalizeMethod.indexOf("this.voiceState = 'FINALIZING'")
	)
})

test('voice transcript row places a shared 40px orb before the textarea', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const rowStart = panel.indexOf('class="voice-transcript-row"')
	const rowEnd = panel.indexOf('</view>', panel.indexOf('</scroll-view>', rowStart))
	const rowTemplate = panel.slice(rowStart, rowEnd)

	assert.ok(rowStart >= 0, 'voice transcript row must exist')
	assert.match(rowTemplate, /<user-thinking-orb/)
	assert.match(rowTemplate, /:state="voiceActivityPresentation\.state"/)
	assert.match(rowTemplate, /:size="40"/)
	assert.match(rowTemplate, /:reduced="motionReduced"/)
	assert.doesNotMatch(rowTemplate, /:paused="voiceRecording"/)
	assert.match(rowTemplate, /<textarea/)
	assert.ok(
		rowTemplate.indexOf('<user-thinking-orb') < rowTemplate.indexOf('<textarea'),
		'voice orb must appear before the realtime transcript'
	)
	assert.match(panel, /\.voice-transcript-row\s*\{[^}]*display:\s*flex/)
	assert.match(panel, /\.voice-transcript-row \.user-thinking-orb\s*\{[^}]*flex:\s*0 0 40px/)
})

test('Android page lifecycle stops voice while H5 behavior remains conditionally isolated', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const pageHideStart = panel.indexOf('handlePageHide()')
	const pageUnloadStart = panel.indexOf('handlePageUnload()')
	const pageHide = panel.slice(
		pageHideStart,
		panel.indexOf('restoreStoppedDraftForCurrentConversation', pageHideStart))
	const pageUnload = panel.slice(
		pageUnloadStart,
		panel.indexOf('releaseCurrentGenerationView', pageUnloadStart))

	assert.match(panel, /beforeUnmount\(\)[\s\S]*abortVoiceInput/)
	assert.match(pageHide, /#ifdef APP-PLUS[\s\S]*abortVoiceInput\('PAGE_HIDE'\)[\s\S]*#endif/)
	assert.match(pageUnload, /#ifdef APP-PLUS[\s\S]*abortVoiceInput\('PAGE_UNLOAD'\)[\s\S]*#endif/)
	assert.match(panel, /'STOP_REQUESTED'/)
	assert.match(panel, /source=\$\{controlledSource\}/)
	for (const source of [
		'USER_DISCARD',
		'USER_TAP',
		'RUNTIME_FAILURE',
		'PAGE_HIDE',
		'PAGE_UNLOAD',
		'COMPONENT_UNMOUNT',
		'MAX_DURATION',
		'SERVER_LIMIT',
		'TRANSCRIPT_FINAL',
		'STALE_ASYNC_BRANCH'
	]) assert.match(panel, new RegExp(`['"]${source}['"]`))
	assert.doesNotMatch(panel, /visibilitychange/)
	assert.doesNotMatch(panel, /uni\.onAppHide/)
	assert.doesNotMatch(panel, /abortVoiceInput\('NEW_CHAT'\)/)
	assert.doesNotMatch(panel, /abortVoiceInput\('CONVERSATION_CHANGE'\)/)
	assert.match(panel, /recorder\?\.destroy/)
	assert.match(panel, /session\?\.abort/)
})

test('Vue keeps voice runtime handles raw and stale async branches release resources', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const permissionContinuation = panel.slice(
		panel.indexOf('await recorder.requestPermission()'),
		panel.indexOf("this.voiceState = 'ISSUING_TICKET'"))
	const connectionContinuation = panel.slice(
		panel.indexOf('await session.connect(ticket)'),
		panel.indexOf("this.voiceState = 'RECORDING'"))

	assert.match(panel, /import\s*\{\s*markRaw\s*\}\s*from\s*['"]vue['"]/)
	assert.match(panel, /const recorder = markRaw\(createVoiceRecorder\(\)\)/)
	assert.match(panel, /session = markRaw\(createVoiceWebSocketSession\(\{/)
	assert.match(permissionContinuation, /this\.voiceRecorder !== recorder[\s\S]*await recorder\.destroy\(\)/)
	assert.match(connectionContinuation, /this\.voiceSession !== session[\s\S]*session\.abort\('STALE_ASYNC_BRANCH'\)/)
})

test('voice discard is synchronous, restores the base draft, and ignores stale callbacks', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const abortMethod = panel.slice(
		panel.indexOf("abortVoiceInput(source = 'USER_DISCARD')"),
		panel.indexOf('completeVoiceInput', panel.indexOf("abortVoiceInput(source = 'USER_DISCARD')")))
	const session = read('common/voice/voice-websocket-session.js')

	assert.match(abortMethod, /this\.voiceSessionEpoch \+= 1/)
	assert.match(abortMethod, /session\?\.abort\?\.\(controlledSource\)/)
	assert.match(abortMethod, /this\.draft = this\.voiceDraftBase/)
	assert.match(abortMethod, /this\.disposeVoiceTranscriptPresenter\(\)/)
	assert.match(abortMethod, /this\.voiceState = 'IDLE'/)
	assert.doesNotMatch(abortMethod, /\bawait\b/)
	assert.match(session, /abort\(source = 'USER_DISCARD'\)/)
	assert.match(session, /this\.state = VOICE_SESSION_STATES\.CLOSED[\s\S]*this\._closeTask\(1000, controlledSource\)/)
	const sessionAbortStart = session.indexOf("abort(source = 'USER_DISCARD')")
	assert.doesNotMatch(
		session.slice(sessionAbortStart, session.indexOf('\n\t_handleMessage', sessionAbortStart)),
		/input\.commit|session\.stop|\bawait\b/)
})

test('voice callbacks keep epoch ownership across failures and asynchronous completion', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const startMethod = panel.slice(
		panel.indexOf('async startVoiceInput'),
		panel.indexOf('startVoiceTimer()', panel.indexOf('async startVoiceInput')))
	const eventMethod = panel.slice(
		panel.indexOf('async handleVoiceEvent'),
		panel.indexOf('acceptVoiceTranscript(text, owner)'))
	const finalMethod = panel.slice(
		panel.indexOf('acceptVoiceTranscript(text, owner)'),
		panel.indexOf('async handleVoiceFailure'))
	const failureMethod = panel.slice(
		panel.indexOf('async handleVoiceFailure'),
		panel.indexOf("abortVoiceInput(source = 'USER_DISCARD')"))

	assert.match(startMethod, /handleVoiceEvent\(event,\s*\{\s*voiceEpoch,\s*session\s*\}\)/)
	assert.match(startMethod, /handleVoiceFailure\(error,\s*\{\s*voiceEpoch,\s*session\s*\}\)/)
	assert.match(startMethod, /let session = null[\s\S]*try\s*\{/)
	assert.doesNotMatch(startMethod, /session:\s*this\.voiceSession/)
	assert.match(eventMethod, /ownsVoiceSession\(owner\)/)
	assert.match(eventMethod, /input\.limit_reached[\s\S]*try\s*\{[\s\S]*await recorder\?\.stop\?\.\(\)[\s\S]*catch\s*\(error\)[\s\S]*handleVoiceFailure\(error, owner\)/)
	assert.match(eventMethod, /input\.limit_reached[\s\S]*resetVoiceWaveform\(owner\.voiceEpoch\)/)
	assert.match(finalMethod, /acceptVoiceTranscript\(text, owner\)/)
	assert.match(finalMethod, /this\.completeVoiceInput\('TRANSCRIPT_FINAL', owner\)/)
	assert.match(finalMethod, /if \(!this\.voiceEpochActive\(owner\.voiceEpoch\)\) return/)
	assert.match(failureMethod, /handleVoiceFailure\(error, owner = null\)/)
	assert.match(failureMethod, /if \(owner && !this\.ownsVoiceSession\(owner\)\) return/)
})

test('voice waveform analyzes only after audio enqueue and fails open inside the active epoch', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const startMethod = panel.slice(
		panel.indexOf('async startVoiceInput'),
		panel.indexOf('startVoiceTimer()', panel.indexOf('async startVoiceInput')))
	const publishMethod = panel.slice(
		panel.indexOf('publishVoiceWaveform(frame, voiceEpoch)'),
		panel.indexOf('async toggleVoiceInput()', panel.indexOf('publishVoiceWaveform(frame, voiceEpoch)')))
	const sendIndex = startMethod.indexOf('session.sendAudio(frame)')
	const waveformIndex = startMethod.indexOf('this.publishVoiceWaveform(frame, voiceEpoch)')

	assert.match(panel, /createVoiceWaveformAnalyzer/)
	assert.match(panel, /markRaw\(createVoiceWaveformAnalyzer\(\)\)/)
	assert.match(panel, /voiceWaveformAnalyzer:\s*null/)
	assert.match(panel, /voiceWaveformPacket:\s*null/)
	assert.match(panel, /voiceWaveformSequence:\s*0/)
	assert.ok(sendIndex >= 0)
	assert.ok(waveformIndex > sendIndex, 'audio must enter its send path before visualization')
	assert.match(startMethod, /!\['RECORDING', 'FINALIZING'\]\.includes\(this\.voiceState\)/)
	assert.match(publishMethod, /this\.voiceSessionEpoch !== voiceEpoch/)
	assert.match(publishMethod, /this\.voiceState !== 'RECORDING'/)
	assert.match(publishMethod, /try\s*\{[\s\S]*\.analyze\(frame\)[\s\S]*catch\s*\(_\)\s*\{\s*return\s*\}/)
	assert.match(publishMethod, /publishedAtMs:\s*Date\.now\(\)/)
	assert.doesNotMatch(publishMethod, /handleVoiceFailure|sendAudio|console\./)
	assert.match(panel, /resetVoiceWaveform\(this\.voiceSessionEpoch\)/)
	assert.match(panel, /this\.voiceWaveformPacket = null/)
})

test('H5 and Android publish the same normalized waveform presentation levels', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const publishMethod = panel.slice(
		panel.indexOf('publishVoiceWaveform(frame, voiceEpoch)'),
		panel.indexOf('async toggleVoiceInput()', panel.indexOf('publishVoiceWaveform(frame, voiceEpoch)')))

	assert.doesNotMatch(panel, /ANDROID_VOICE_WAVEFORM_VISUAL_GAIN/)
	assert.match(publishMethod, /const visualLevels = levels\.slice\(0, 5\)\.map\(/)
	assert.doesNotMatch(publishMethod, /#ifdef APP-PLUS|#ifndef APP-PLUS/)
	assert.match(publishMethod, /levels:\s*Object\.freeze\(visualLevels\)/)
	assert.doesNotMatch(publishMethod, /sendAudio|session\.|handleVoiceFailure/)
})

test('H5 and Android share one waveform timeline and a single Canvas renderer with platform profiles', () => {
	const timeline = read('common/voice/voice-waveform-timeline.js')
	const presentation = read('common/voice/voice-waveform-presentation.js')
	const renderer = read('components/user/workspace/user-voice-waveform-render.js')

	assert.match(timeline, /VOICE_WAVEFORM_INTERVAL_MS\s*=\s*300/)
	assert.match(timeline, /VOICE_WAVEFORM_QUEUE_LIMIT\s*=\s*15/)
	assert.match(timeline, /VOICE_WAVEFORM_MAX_CAPACITY\s*=\s*192/)
	assert.match(renderer, /createVoiceWaveformTimeline/)
	assert.match(renderer, /CANVAS_VOICE_WAVEFORM_MAX_CAPACITY\s*=\s*512/)
	assert.match(presentation, /VOICE_WAVEFORM_BAR_WIDTH\s*=\s*2\.5/)
	assert.match(presentation, /VOICE_WAVEFORM_BAR_GAP\s*=\s*3/)
	assert.match(presentation, /VOICE_WAVEFORM_BAR_PITCH\s*=\s*5\.5/)
	assert.match(presentation, /rgba\(174,185,179,0\.24\)/)
	assert.doesNotMatch(renderer,
		/117,\s*223,\s*183|55,\s*211,\s*154/)
	assert.doesNotMatch(`${timeline}\n${presentation}`,
		/Vue|Canvas|UniApp|WebSocket|sendAudio|PCM/)
})

test('stop remains disabled until recorder startup really resolves', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const startMethod = panel.slice(
		panel.indexOf('async startVoiceInput'),
		panel.indexOf('startVoiceTimer()', panel.indexOf('async startVoiceInput')))
	const recorderStartIndex = startMethod.indexOf('await recorder.start(')
	const recordingStateIndex = startMethod.indexOf("this.voiceState = 'RECORDING'")

	assert.ok(recorderStartIndex >= 0)
	assert.ok(recordingStateIndex > recorderStartIndex)
	assert.match(startMethod, /await recorder\.start\([\s\S]*this\.voiceRecorder !== recorder[\s\S]*session\.abort\('STALE_ASYNC_BRANCH'\)[\s\S]*this\.voiceState = 'RECORDING'/)
})

test('voice control availability matches connecting, recording, and finalizing states', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')

	assert.match(panel, /voiceCancelDisabled\(\) \{ return this\.voiceFinalizing \}/)
	assert.match(panel, /voiceCommitDisabled\(\) \{ return !this\.voiceRecording \}/)
	assert.match(panel, /:disabled="voiceCancelDisabled"/)
	assert.match(panel, /:disabled="voiceCommitDisabled"/)
	assert.match(panel, /v-if="voiceInteractionActive"[\s\S]*class="voice-cancel-button"[\s\S]*class="voice-commit-button"/)
	assert.match(panel, /v-if="!voiceInteractionActive"[\s\S]*class="voice-button"[\s\S]*class="send-button"/)
})

test('Android diagnostics stay inside the client bridge and never enter the wire protocol', () => {
	const recorderInterface = read('uni_modules/ait-voice-recorder/utssdk/interface.uts')
	const recorder = read('uni_modules/ait-voice-recorder/utssdk/app-android/index.uts')
	const wrapper = read('common/voice/voice-recorder.js')
	const session = read('common/voice/voice-websocket-session.js')
	const h5Recorder = read('common/voice/voice-recorder-h5.js')

	assert.match(recorderInterface, /diagnosticRunId:\s*string/)
	assert.match(recorderInterface, /frameSequence:\s*number/)
	assert.match(`${recorder}\n${wrapper}`, /diagnosticRunId/)
	assert.match(`${recorder}\n${wrapper}`, /frameSequence/)
	assert.doesNotMatch(session, /diagnosticRunId|frameSequence/)
	assert.doesNotMatch(h5Recorder, /voice_android_|renewRecordingLease|stopRecording|diagnosticRunId|frameSequence/)
	assert.doesNotMatch(`${recorder}\n${wrapper}\n${session}`, /payloadBase64=|pcmBytes=|audioContent=/)
})
