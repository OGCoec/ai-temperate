const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function read(relativePath) {
	return fs.readFileSync(path.resolve(__dirname, '..', '..', relativePath), 'utf8')
}

test('Android waveform is now a unified Canvas component, not a Vue v-for list', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const renderer = read('components/user/workspace/user-voice-waveform-render.js')

	// APP-PLUS 与 H5 统一使用 <user-voice-waveform>，不再条件编译独立 Android 组件。
	assert.match(panel, /<user-voice-waveform/)
	assert.doesNotMatch(panel, /<user-voice-waveform-android/)
	assert.doesNotMatch(panel, /import UserVoiceWaveformAndroid/)
	assert.doesNotMatch(panel, /user-android-voice-composer|UserAndroidVoiceComposer/)
	assert.doesNotMatch(renderer, /visibleCapacity:\s*192|transition-duration:\s*300ms/)
})

test('the unified waveform component passes a platform profile to the renderer', () => {
	const component = read('components/user/workspace/user-voice-waveform.vue')

	// 内部条件编译决定 profile，不暴露为公共 Prop。
	assert.match(component, /profile/)
	assert.match(component, /#ifdef APP-PLUS/)
	assert.match(component, /'android'/)
	assert.match(component, /'h5'/)
	assert.match(component, /publishedAtMs:\s*Number\(this\.packet\.publishedAtMs\)/)
	assert.doesNotMatch(component, /props:.*profile/)
})

test('Android Canvas renderer uses smaller symmetric bars without a midline', () => {
	const renderer = read('components/user/workspace/user-voice-waveform-render.js')

	// Android 保留 2px～14px 名义范围，再由指数与 0.82 上限得到 11.84px 实际最大柱长。
	assert.match(renderer, /ANDROID_BAR_WIDTH\s*=\s*2/)
	assert.match(renderer, /ANDROID_MAX_HEIGHT\s*=\s*14/)
	assert.match(renderer, /ANDROID_LEVEL_EXPONENT\s*=\s*1\.3/)
	assert.match(renderer, /ANDROID_LEVEL_CEILING\s*=\s*0\.82/)
	assert.match(renderer, /resolveVoiceWaveformDisplayLevel/)
	assert.match(renderer, /profile === 'android' \? 1 : resolvedDpr/)
	assert.doesNotMatch(renderer, /ANDROID_MIDLINE|drawAndroidMidline/)
})

test('Android visible width must come from Canvas display area, not a wider ancestor', () => {
	const renderer = read('components/user/workspace/user-voice-waveform-render.js')

	// 宽度测量优先使用 .user-voice-waveform 实际渲染框和 Canvas Host。
	assert.match(renderer, /measureWidth/)
	// 宽度为 0 时不缓存为有效 metrics。
	assert.match(renderer, /metricsDirty/)
	// 不使用 Canvas 的固有 width 属性作为 CSS 可视宽度。
	assert.doesNotMatch(renderer, /canvas\.width\s*\/\s*dpr/)
})

test('zero-width canvas does not cache as valid metrics', () => {
	const renderer = read('components/user/workspace/user-voice-waveform-render.js')

	// 宽度为 0 时 configureCanvas 返回 null 且保持 metricsDirty = true。
	assert.match(renderer, /!\(width > 0\)\) return null/)
	assert.match(renderer, /this\.metricsDirty = false/)
})

test('Android waveform extends across the empty cancel column and aligns with duration', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')

	// 波形只延伸到时间左侧，Canvas 仍按扩展后的真实渲染框计算容量。
	assert.match(panel,
		/\.is-android-client\s+\.voice-inline-status\s*\{[^}]*width:\s*calc\(100% \+ 56px\)[^}]*max-width:\s*none/s)
	assert.match(panel,
		/\.is-android-client\s+\.voice-inline-status\s*\{[^}]*height:\s*28px[^}]*min-height:\s*28px[^}]*padding:\s*2px 0[^}]*overflow:\s*hidden/s)
	assert.match(panel,
		/\.is-android-client\s+\.voice-duration\s*\{[^}]*height:\s*28px[^}]*min-height:\s*28px[^}]*flex:\s*0 0 28px/s)
	assert.doesNotMatch(panel,
		/\.is-android-client\s+\.voice-inline-status\s*\{[^}]*(?:transform|margin-left):/s)
})

test('Android idle composer reserves the active voice height in every orientation', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')

	// 两种输入状态共用 94px border-box 基准，避免依赖各自不同的内边距反推外框高度。
	assert.match(panel,
		/\.is-android-client\s+\.composer\s*\{[^}]*min-height:\s*94px[^}]*box-sizing:\s*border-box/s)
	// 普通态与紧凑横屏都不得再用状态选择器覆盖共同高度。
	assert.doesNotMatch(panel,
		/\.is-android-client\s+\.composer:not\(\.is-voice-active\)\s*\{[^}]*min-height:/s)
})

test('H5 desktop waveform display area is 24px, not 18px', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')

	// H5 桌面波形显示区固定为 24px。
	assert.doesNotMatch(panel,
		/\.voice-inline-status\s+\.user-voice-waveform\s*\{[^}]*height:\s*18px/)
	// 波形组件固有 24px 不再被外部压缩。
	assert.match(panel,
		/\.chat-main:not\(\.is-android-client\)\s+\.composer\.is-voice-active\s+\.voice-inline-status\s*\{[^}]*min-height:\s*24px/s)
})

test('H5 desktop horizontal waveform span is preserved', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')

	// 横向规则保持不变。
	assert.match(panel,
		/\.chat-main:not\(\.is-android-client\)\s+\.composer\.is-voice-active\s+\.voice-inline-status\s*\{[^}]*width:\s*calc\(100% \+ 45px\)[^}]*margin-left:\s*-45px/s)
})

test('H5 desktop cancel button and stop button align vertically', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')

	// 取消键使用 align-self: flex-end 贴底。
	assert.match(panel,
		/\.chat-main:not\(\.is-android-client\)\s+\.composer\.is-voice-active\s+\.voice-cancel-button\s*\{[^}]*align-self:\s*flex-end/s)
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

test('unified waveform receives only visual data and retains existing voice actions', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const start = panel.indexOf('<user-voice-waveform')
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

test('component input stays visual-only and Canvas lifecycle stays native', () => {
	const component = fs.readFileSync(path.resolve(
		__dirname,
		'../../components/user/workspace/user-voice-waveform.vue'), 'utf8')
	const renderer = fs.readFileSync(path.resolve(
		__dirname,
		'../../components/user/workspace/user-voice-waveform-render.js'), 'utf8')

	assert.match(component, /aria-hidden="true"/)
	assert.match(component, /levels\.slice\(0, 5\)/)
	assert.match(component,
		/#ifdef APP-PLUS[\s\S]*<canvas[\s\S]*:hidpi="false"[\s\S]*#endif/)
	assert.match(component,
		/#ifdef H5[\s\S]*class="user-voice-waveform-native-host"[\s\S]*#endif/)
	assert.doesNotMatch(component, /ArrayBuffer|DataView|PCM|sendAudio|WebSocket/)
	assert.doesNotMatch(renderer, /handleVoiceFailure|sendAudio|WebSocket/)
	assert.match(renderer, /IntersectionObserver/)
	assert.match(renderer, /ResizeObserver/)
	assert.match(renderer, /this\.canvas\.width\s*=\s*metrics\.pixelWidth/)
	assert.match(renderer, /resolveVoiceWaveformContextScale\(profile, metrics\.dpr\)/)
})
