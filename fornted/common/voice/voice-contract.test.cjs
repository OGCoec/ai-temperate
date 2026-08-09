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

test('Android declares record permission and streams AudioRecord bytes without Base64', () => {
	const manifest = read('manifest.json')
	const recorder = read('uni_modules/ait-voice-recorder/utssdk/app-android/index.uts')

	assert.match(manifest, /android\.permission\.RECORD_AUDIO/)
	assert.match(manifest, /"Record"\s*:\s*\{\}/)
	assert.match(recorder, /AudioSource\.VOICE_RECOGNITION/)
	assert.match(recorder, /ArrayBuffer\.fromByteBuffer/)
	assert.doesNotMatch(recorder, /Base64|base64/)
})

test('Android UTS recorder exports typed const APIs without function declarations', () => {
	const recorderInterface = read('uni_modules/ait-voice-recorder/utssdk/interface.uts')
	const recorder = read('uni_modules/ait-voice-recorder/utssdk/app-android/index.uts')
	const wrapper = read('common/voice/voice-recorder.js')

	assert.doesNotMatch(recorderInterface, /export function requestRecordPermission/)
	assert.doesNotMatch(recorderInterface, /export function startRecording/)
	assert.match(recorderInterface, /export type RequestRecordPermissionApi\s*=\s*\(options: AitVoicePermissionOptions\) => void/)
	assert.match(recorderInterface, /export type StartRecordingApi\s*=\s*\(options: AitVoiceRecorderOptions\) => AitVoiceRecorderSession/)
	assert.match(recorder, /export const requestRecordPermission: RequestRecordPermissionApi\s*=\s*function\(options: AitVoicePermissionOptions\): void/)
	assert.match(recorder, /export const startRecording: StartRecordingApi\s*=\s*function\(options: AitVoiceRecorderOptions\): AitVoiceRecorderSession/)
	assert.match(wrapper, /import\s*\{\s*requestRecordPermission,\s*startRecording\s*\}\s*from '@\/uni_modules\/ait-voice-recorder'/)
})

test('Android UTS recorder pins native AudioRecord arguments to Int', () => {
	const recorder = read('uni_modules/ait-voice-recorder/utssdk/app-android/index.uts')

	assert.match(recorder, /const SAMPLE_RATE: Int = 16000/)
	assert.match(recorder, /const BYTES_PER_FRAME: Int = 3200/)
	assert.match(recorder, /AudioRecord\.getMinBufferSize\(\s*SAMPLE_RATE,/)
	assert.match(recorder, /ByteArray\(BYTES_PER_FRAME\)/)
})

test('chat composer previews partial text and only appends final text to draft', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const partialBranch = panel.slice(
		panel.indexOf("event?.type === 'transcript.partial'"),
		panel.indexOf("event?.type === 'input.limit_reached'"))
	const finalMethod = panel.slice(
		panel.indexOf('async acceptVoiceTranscript'),
		panel.indexOf('async handleVoiceFailure'))

	assert.match(partialBranch, /voicePartialText/)
	assert.doesNotMatch(partialBranch, /this\.draft\s*=/)
	assert.match(finalMethod, /this\.draft = appendTranscriptToDraft/)
	assert.doesNotMatch(finalMethod, /this\.send\(/)
	assert.match(panel, /voiceMaximumDurationMs:\s*300000/)
	assert.match(panel, /aria-label="聊天消息"/)
	assert.match(panel, /:aria-label="voiceButtonLabel"/)
	assert.match(panel, /class="voice-queue-cancel"[\s\S]*aria-label="取消语音识别排队"/)
	assert.match(panel, /event\?\.type === 'session\.queued'/)
	assert.match(panel, /async cancelVoiceQueue/)
})

test('tab visibility changes preserve voice while component unmount releases resources', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')

	assert.match(panel, /beforeUnmount\(\)[\s\S]*cancelVoiceInput/)
	assert.doesNotMatch(panel, /visibilitychange/)
	assert.doesNotMatch(panel, /uni\.onAppHide/)
	assert.doesNotMatch(panel, /cancelVoiceInput\('NEW_CHAT'\)/)
	assert.doesNotMatch(panel, /cancelVoiceInput\('CONVERSATION_CHANGE'\)/)
	assert.match(panel, /recorder\?\.destroy/)
	assert.match(panel, /session\?\.stop/)
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
	assert.match(connectionContinuation, /this\.voiceSession !== session[\s\S]*await session\.stop\(\)/)
})
