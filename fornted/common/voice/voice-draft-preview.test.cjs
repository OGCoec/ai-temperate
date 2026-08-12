const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadModule() {
	const source = fs.readFileSync(
		path.join(__dirname, 'voice-draft-preview.js'),
		'utf8')
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

test('builds every partial preview from the immutable pre-recording draft', async () => {
	const { appendVoiceTranscriptToDraft } = await loadModule()
	const baseDraft = '原来的手工草稿'

	assert.equal(
		appendVoiceTranscriptToDraft(baseDraft, '第一段临时文字'),
		'原来的手工草稿第一段临时文字')
	assert.equal(
		appendVoiceTranscriptToDraft(baseDraft, '第二段完整临时文字'),
		'原来的手工草稿第二段完整临时文字')
})

test('preserves punctuation and whitespace joining behavior for final transcripts', async () => {
	const { appendVoiceTranscriptToDraft } = await loadModule()

	assert.equal(appendVoiceTranscriptToDraft('', ' 最终文字 '), '最终文字')
	assert.equal(appendVoiceTranscriptToDraft('hello', 'world'), 'hello world')
	assert.equal(appendVoiceTranscriptToDraft('你好', '世界'), '你好世界')
	assert.equal(appendVoiceTranscriptToDraft('你好', '。'), '你好。')
	assert.equal(appendVoiceTranscriptToDraft('hello ', 'world'), 'hello world')
})

