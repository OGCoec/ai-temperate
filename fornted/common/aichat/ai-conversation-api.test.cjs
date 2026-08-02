const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadApi(request) {
	const nonce = `${Date.now()}-${Math.random()}`
	globalThis.__aiConversationRequest = request
	const httpClient = sourceUrl(
		'export const authorizedRequest = (...args) => globalThis.__aiConversationRequest(...args)'
	)
	const source = fs.readFileSync(
		path.resolve(__dirname, 'ai-conversation-api.js'),
		'utf8'
	).replace("from '../auth/http-client.js'", `from '${httpClient}#${nonce}'`)
	return import(`${sourceUrl(source)}#${nonce}`)
}

const conversationId = 'AAAAAAAAAAAAAAAAAAAAAQ'
const messageId = 'AAAAAAAAAAE'
const usageId = 'AAAAAAAAAAAAAAAAAAAAAg'
const attachmentId = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKL'

function availableAttachment() {
	return {
		schemaVersion: 1,
		attachmentId,
		fileName: 'example.png',
		contentType: 'image/png',
		sizeBytes: '428716',
		category: 'IMAGE',
		url: 'https://public-oss.example.test/example.png',
		state: 'AVAILABLE',
		failureCode: null
	}
}

test('reads PostgreSQL conversation history through the authenticated API', async () => {
	const calls = []
	const module = await loadApi(async (...args) => {
		calls.push(args)
		return {
			messages: [{
				messagePublicId: messageId,
				contentText: ' hello ',
				contentAttachments: [availableAttachment()],
				responseText: ' answer ',
				responseAttachments: [],
				createdAt: '2026-07-30T12:00:00Z',
				usagePublicId: usageId,
				modelPublicId: messageId,
				modelName: 'gpt-5.6-sol',
				promptTokens: '10',
				cachedPromptTokens: '2',
				completionTokens: '20',
				reasoningTokens: '4',
				chargedQuotaMinor: '18',
				finishReason: 'STOP'
			}],
			nextBefore: null,
			hasMore: false
		}
	})

	const page = await module.aiConversationApi.messages(conversationId)

	assert.equal(calls[0][0], `/api/ai/conversations/${conversationId}/messages?pageSize=50`)
	assert.deepEqual(calls[0][1], { method: 'GET' })
	assert.equal(page.messages[0].contentText, ' hello ')
	assert.equal(page.messages[0].contentAttachments[0].sizeBytes, '428716')
	delete globalThis.__aiConversationRequest
})

test('validates preupload fields without exposing object keys or credentials', async () => {
	const module = await loadApi(async (url, options) => {
		assert.equal(url, '/api/ai/conversation-attachments/preuploads')
		assert.equal(options.method, 'POST')
		return {
			uploadSessionId: conversationId,
			files: [{
				attachmentId,
				fileName: 'manual.pdf',
				contentType: 'application/pdf',
				sizeBytes: '1048576',
				uploadUrl: 'https://oss.example.test/signed',
				method: 'PUT',
				uploadHeaders: { 'Content-Type': 'application/pdf' },
				expiresAt: '2026-07-30T12:10:00Z'
			}]
		}
	})

	const result = await module.aiConversationApi.createPreuploads([{
		fileName: 'manual.pdf',
		contentType: 'application/pdf',
		sizeBytes: 1048576
	}])

	assert.equal(result.files[0].method, 'PUT')
	assert.equal(result.files[0].sizeBytes, '1048576')
	assert.equal('objectKey' in result.files[0], false)
	delete globalThis.__aiConversationRequest
})

test('rejects inconsistent attachment storage states', async () => {
	const module = await loadApi(async () => ({
		messages: [{
			messagePublicId: messageId,
			contentText: 'hello',
			contentAttachments: [{ ...availableAttachment(), state: 'STORAGE_FAILED' }],
			responseText: 'answer',
			responseAttachments: [],
			createdAt: '2026-07-30T12:00:00Z'
		}],
		nextBefore: null,
		hasMore: false
	}))

	await assert.rejects(
		() => module.aiConversationApi.messages(conversationId),
		error => error?.code === 'AI_CONVERSATION_RESPONSE_INVALID'
	)
	delete globalThis.__aiConversationRequest
})
