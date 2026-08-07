const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const { loadEsmModule } = require('./ai-code-test-loader.cjs')

function loadNavigation() {
	return loadEsmModule(path.resolve(__dirname, 'ai-conversation-turn-navigation.js'))
}

function message(index, patch = {}) {
	return {
		messagePublicId: `message_${index}`,
		contentText: `第 ${index} 个问题`,
		contentAttachments: [],
		responseText: `第 ${index} 个回答`,
		responseAttachments: [],
		createdAt: '2026-08-07T12:00:00Z',
		...patch
	}
}

test('creates a stable turn item with plain-text answer summary and attachment counts', async () => {
	const navigation = await loadNavigation()
	const item = navigation.createTurnNavigationItem(message(7, {
		contentText: '怎样处理 **很长** 的历史会话？',
		responseText: '## 方案\n\n使用 `50` 轮窗口。\n\n- 保持锚点\n- 避免跳动',
		contentAttachments: [
			{ contentType: 'image/png' },
			{ contentType: 'video/mp4' },
			{ contentType: 'application/pdf' }
		]
	}), 6)

	assert.equal(item.key, 'message_7')
	assert.equal(item.elementId, 'message-message_7')
	assert.equal(item.question, '怎样处理 很长 的历史会话？')
	assert.equal(item.answerSummary, '方案 使用 50 轮窗口。 保持锚点 避免跳动')
	assert.equal(item.attachmentSummary, '图片 1 · 视频 1 · 文件 1')
	assert.equal(item.position, 7)
	assert.equal(item.status, 'complete')
})

test('uses the local id for an in-flight turn and maps visible generation states', async () => {
	const navigation = await loadNavigation()
	const streaming = navigation.createTurnNavigationItem(message(1, {
		localId: 'generation:local/1',
		streaming: true
	}), 0)
	const saving = navigation.createTurnNavigationItem(message(2, { saving: true }), 1)
	const stopped = navigation.createTurnNavigationItem(message(3, { stopped: true }), 2)
	const failed = navigation.createTurnNavigationItem(message(4, { error: '失败' }), 3)

	assert.equal(streaming.key, 'generation:local/1')
	assert.match(streaming.elementId, /^message-[A-Za-z0-9_-]+$/)
	assert.equal(streaming.status, 'streaming')
	assert.equal(saving.status, 'saving')
	assert.equal(stopped.status, 'stopped')
	assert.equal(failed.status, 'failed')
})

test('keeps the initial and shifted render window at no more than fifty turns', async () => {
	const navigation = await loadNavigation()

	assert.deepEqual(navigation.createInitialTurnWindow(0), { start: 0, end: 0 })
	assert.deepEqual(navigation.createInitialTurnWindow(49), { start: 0, end: 49 })
	assert.deepEqual(navigation.createInitialTurnWindow(100), { start: 50, end: 100 })
	assert.deepEqual(
		navigation.shiftTurnWindow({ start: 50, end: 100 }, 'before', 100),
		{ start: 25, end: 75 }
	)
	assert.deepEqual(
		navigation.shiftTurnWindow({ start: 25, end: 75 }, 'after', 100),
		{ start: 50, end: 100 }
	)
})

test('preserves overlap after prepending older history and can center a cached target', async () => {
	const navigation = await loadNavigation()

	assert.deepEqual(
		navigation.windowAfterPrepend({ start: 0, end: 50 }, 50, 100),
		{ start: 25, end: 75 }
	)
	assert.deepEqual(navigation.centerTurnWindow(10, 100), { start: 0, end: 50 })
	assert.deepEqual(navigation.centerTurnWindow(60, 100), { start: 35, end: 85 })
	assert.deepEqual(navigation.centerTurnWindow(99, 100), { start: 50, end: 100 })
})

test('uses measured heights before bounded estimates for hidden spacers', async () => {
	const navigation = await loadNavigation()
	const messages = [
		message(1, { contentText: '短问题', responseText: '短回答' }),
		message(2, { contentText: '较长问题'.repeat(30), responseText: '较长回答'.repeat(80) })
	]
	const measured = { message_1: 420 }

	assert.equal(navigation.sumTurnHeights(messages, measured, 0, 1), 420)
	assert.ok(navigation.estimateTurnHeight(messages[1]) > navigation.estimateTurnHeight(messages[0]))
	assert.ok(navigation.estimateTurnHeight(messages[1]) <= 1200)
})
