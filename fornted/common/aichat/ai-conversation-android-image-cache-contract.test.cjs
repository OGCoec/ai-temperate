const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const moduleRoot = path.resolve(
	__dirname,
	'../../uni_modules/ait-android-image-cache/utssdk'
)

function readSource(relativePath) {
	return fs.readFileSync(path.join(moduleRoot, relativePath), 'utf8')
}

test('Android image cache exposes only the bounded internal image operations', () => {
	const contract = readSource('interface.uts')
	const implementation = readSource('app-android/index.uts')

	assert.equal(implementation.includes('materializeBase64Image'), true)
	assert.equal(implementation.includes('fetchHttpsImage'), true)
	assert.equal(implementation.includes('removeManagedImage'), true)
	assert.equal(contract.includes('export function materializeBase64Image'), false)
	assert.equal(contract.includes('export function fetchHttpsImage'), false)
	assert.equal(contract.includes('export function removeManagedImage'), false)
	assert.equal(contract.includes('AitAndroidMaterializeBase64ImageApi'), true)
	assert.equal(contract.includes('AitAndroidFetchHttpsImageApi'), true)
	assert.equal(contract.includes('AitAndroidRemoveManagedImageApi'), true)
	assert.equal(
		implementation.includes(
			'export const materializeBase64Image: AitAndroidMaterializeBase64ImageApi'
		),
		true
	)
	assert.equal(
		implementation.includes('export const fetchHttpsImage: AitAndroidFetchHttpsImageApi'),
		true
	)
	assert.equal(
		implementation.includes('export const removeManagedImage: AitAndroidRemoveManagedImageApi'),
		true
	)
	assert.equal(contract.includes('maximumBytes'), true)
	assert.equal(contract.includes('filePath: string'), true)
	assert.equal(contract.includes('displayUri: string'), true)
	assert.equal(contract.includes('contentType: string'), true)
	assert.equal(contract.includes('sizeBytes: number'), true)
	assert.equal(contract.includes('diagnosticsEnabled: boolean'), true)
	assert.equal(contract.includes('diagnosticRunId: string'), true)
	assert.equal(contract.includes('downloadAttempt: number'), true)
	assert.equal(contract.includes('stage: string'), true)
	assert.equal(contract.includes('exceptionType: string'), true)
})

test('Android cache writes private part files, sniffs bytes, and streams HTTPS', () => {
	const source = readSource('app-android/index.uts')

	assert.equal(source.includes('const BUFFER_BYTES: Int = 64 * 1024'), true)
	assert.equal(source.includes("'ait-conversation-images'"), true)
	assert.equal(source.includes('.part'), true)
	assert.equal(source.includes('UUID.randomUUID()'), true)
	assert.equal(source.includes("target.getProtocol().lowercase() != 'https'"), true)
	assert.equal(source.includes('BufferedInputStream'), true)
	assert.equal(source.includes('FileOutputStream'), true)
	assert.equal(source.includes('renameTo'), true)
	assert.equal(source.includes('detectImageFormat'), true)
	assert.equal(source.includes('BitmapFactory.decodeFile'), true)
	assert.equal(source.includes('setInstanceFollowRedirects(false)'), true)
	assert.equal(source.includes('Base64.decode'), true)
	assert.equal(source.includes('384000'), true)
	assert.equal(source.includes("displayUri: `file://${file.getAbsolutePath()}`"), true)
	for (const phase of [
		'FETCH_ENTERED',
		'HTTP_RESPONSE_RECEIVED',
		'STREAM_COMPLETED',
		'FORMAT_DETECTED',
		'ATOMIC_RENAME_SUCCEEDED',
		'BITMAP_DECODE_SUCCEEDED',
		'SUCCESS_CALLBACK_ATTEMPT',
		'SUCCESS_CALLBACK_RETURNED',
		'WORKER_FINISHED'
	]) {
		assert.equal(source.includes(phase), true, phase)
	}
})

test('managed deletion is canonical-path guarded and cancellation removes partial files', () => {
	const source = readSource('app-android/index.uts')

	assert.equal(source.includes('getCanonicalPath'), true)
	assert.equal(source.includes("contains('..')"), true)
	assert.equal(source.includes('partFile?.delete()'), true)
	assert.equal(source.includes('worker.interrupt()'), true)
	assert.equal(source.includes('activeConnection?.disconnect()'), true)
})
