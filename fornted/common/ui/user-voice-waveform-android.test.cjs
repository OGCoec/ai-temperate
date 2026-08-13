const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function read(relativePath) {
	return fs.readFileSync(path.resolve(__dirname, '..', '..', relativePath), 'utf8')
}

test('Android waveform is a plain UniApp view keyed by stable bar ids', () => {
	const component = read('components/user/workspace/user-voice-waveform-android.vue')

	assert.match(component, /createAndroidVoiceWaveformController/)
	assert.match(component, /v-for="bar in renderedBars"/)
	assert.match(component, /:key="bar\.id"/)
	assert.match(component, /class="user-voice-waveform-android-bar"/)
	assert.match(component, /class="user-voice-waveform-android-viewport"/)
	assert.match(component, /VOICE_WAVEFORM_HEIGHT/)
	assert.match(component, /overflow:\s*hidden/)
	assert.doesNotMatch(component, /<canvas|renderjs|getContext|createCanvasContext|MutationObserver|ResizeObserver/)
	assert.doesNotMatch(component, /ArrayBuffer|DataView|PCM|sendAudio|WebSocket/)
})

test('Android animates only one track transform with the shared 300ms pitch', () => {
	const component = read('components/user/workspace/user-voice-waveform-android.vue')

	assert.match(component, /'--voice-waveform-bar-width':\s*`\$\{VOICE_WAVEFORM_BAR_WIDTH\}px`/)
	assert.match(component, /'--voice-waveform-bar-gap':\s*`\$\{VOICE_WAVEFORM_BAR_GAP\}px`/)
	assert.match(component, /'--voice-waveform-bar-pitch':\s*`\$\{VOICE_WAVEFORM_BAR_PITCH\}px`/)
	assert.match(component, /'--voice-waveform-visible-width':\s*`\$\{this\.visibleCapacity \* VOICE_WAVEFORM_BAR_PITCH\}px`/)
	assert.match(component, /transition-property:\s*transform/)
	assert.match(component, /transition-duration:\s*300ms/)
	assert.match(component, /transition-timing-function:\s*linear/)
	assert.match(component, /translate3d\(calc\(-1 \* var\(--voice-waveform-bar-pitch\)\),\s*0,\s*0\)/)
	assert.doesNotMatch(component, /transition:\s*(?:height|width|left|margin)/)
	assert.doesNotMatch(component, /transition-property:\s*(?:height|width|left|margin)/)
	assert.doesNotMatch(component, /117,\s*223,\s*183|55,\s*211,\s*154/)
})

test('Android applies a fresh shared snapshot before starting the next transform cycle', () => {
	const component = read('components/user/workspace/user-voice-waveform-android.vue')

	assert.match(component, /onSnapshot:\s*snapshot\s*=>\s*this\.applySnapshot\(snapshot\)/)
	assert.match(component, /this\.trackAdvancing\s*=\s*false[\s\S]*this\.renderedBars\s*=/)
	assert.match(component, /this\.\$nextTick\(\(\)\s*=>\s*\{[\s\S]*this\.trackAdvancing\s*=\s*true/)
	assert.match(component, /this\.reduced\s*\?\s*snapshot\.settledBars\s*:\s*snapshot\.movingBars/)
	assert.match(component, /'is-advancing':\s*trackAdvancing\s*&&\s*!reduced/)
})

test('Android keeps the unsettled trailing slot for transform geometry but hides its zero-level node', () => {
	const component = read('components/user/workspace/user-voice-waveform-android.vue')

	assert.match(component, /:class="\{ 'is-pending': bar\.pending \}"/)
	assert.match(component, /const pendingBarId = this\.reduced[\s\S]*snapshot\.movingBars\[snapshot\.movingBars\.length - 1\]\?\.id/)
	assert.match(component, /pending:\s*bar\.id === pendingBarId/)
	assert.match(component, /\.user-voice-waveform-android-bar\.is-pending\s*\{[^}]*visibility:\s*hidden/s)
	assert.doesNotMatch(component, /transition-property:\s*visibility|transition:\s*visibility/)
})

test('Android keeps the original orb, cancel, and stop layout unchanged', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const composerStart = panel.indexOf('class="composer"')
	const composerEnd = panel.indexOf('class="android-composer-tools"', composerStart)
	const composer = panel.slice(composerStart, composerEnd)
	const orbIndex = composer.indexOf('<user-thinking-orb')
	const cancelIndex = composer.indexOf('class="voice-cancel-button"')
	const commitIndex = composer.indexOf('class="voice-commit-button"')

	assert.ok(orbIndex >= 0)
	assert.ok(cancelIndex > orbIndex)
	assert.ok(commitIndex > cancelIndex)
	assert.match(composer, /<user-thinking-orb[\s\S]*:size="40"/)
	assert.match(panel,
		/\.is-android-client \.voice-cancel-button,\s*\.is-android-client \.voice-commit-button\s*\{[^}]*user-android-compact-control\(34px,\s*34px,\s*11px\)[^}]*width:\s*44px[^}]*height:\s*44px/s)
	assert.doesNotMatch(panel, /user-android-voice-composer|UserAndroidVoiceComposer/)
})

test('App-Plus replaces only the waveform node while H5 keeps its Canvas component', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const renderer = read('components/user/workspace/user-voice-waveform-render.js')

	assert.match(panel, /#ifdef APP-PLUS[\s\S]*<user-voice-waveform-android/)
	assert.match(panel, /#ifndef APP-PLUS[\s\S]*<user-voice-waveform/)
	assert.match(panel, /#ifndef APP-PLUS[\s\S]*import UserVoiceWaveform/)
	assert.match(panel, /#ifdef APP-PLUS[\s\S]*import UserVoiceWaveformAndroid/)
	assert.doesNotMatch(panel, /v-if="androidClient \|\| voiceInteractionActive"/)
	assert.doesNotMatch(panel, /user-android-voice-composer|UserAndroidVoiceComposer/)
	assert.doesNotMatch(renderer, /App-Plus|CANVAS_CANDIDATE_MISSING|scheduleCanvasProbe|observeCanvasMount/)
})

test('Android waveform receives only visual data and retains existing voice actions', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const start = panel.indexOf('<user-voice-waveform-android')
	const end = panel.indexOf('/>', start)
	const binding = panel.slice(start, end)

	assert.ok(start >= 0)
	assert.match(binding, /:state="voiceState"/)
	assert.match(binding, /:session-epoch="voiceSessionEpoch"/)
	assert.match(binding, /:packet="voiceWaveformPacket"/)
	assert.match(binding, /:reduced="motionReduced"/)
	assert.match(panel, /class="voice-cancel-button"[\s\S]*@click="abortVoiceInput\('USER_DISCARD'\)"/)
	assert.match(panel, /class="voice-commit-button"[\s\S]*@click="finalizeVoiceInput\(false, 'USER_TAP'\)"/)
	assert.doesNotMatch(binding, /sendAudio|WebSocket|handleVoiceFailure/)
})

test('Android component failures stop only visualization and never report a voice error', () => {
	const component = read('components/user/workspace/user-voice-waveform-android.vue')

	assert.match(component, /try\s*\{[\s\S]*controller\?\.accept\?\.\(packet\)[\s\S]*catch\s*\(_\)/)
	assert.match(component, /this\.stopVisualization\(\)/)
	assert.doesNotMatch(component, /handleVoiceFailure|finalizeVoiceInput|abortVoiceInput|sendAudio/)
})
