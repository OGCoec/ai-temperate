const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const { verifyLocalEsmContracts } = require('../../scripts/verify-local-esm-contracts.cjs')

const frontendRoot = path.resolve(__dirname, '../..')

test('local named ESM imports resolve to exported symbols before release', () => {
	const result = verifyLocalEsmContracts({ root: frontendRoot })

	assert.deepEqual(result.errors, [])

	const catalogImport = result.imports.find(record =>
		record.sourceRelative === 'components/user/workspace/user-model-catalog.vue' &&
		record.specifier === '@/common/aimodel/description-highlight.js'
	)
	assert.ok(catalogImport, 'catalog must import the highlight helper through the local ESM contract')
	assert.deepEqual(catalogImport.importedNames, ['buildTextHighlightSegments'])

	const highlightModule = result.modules.get('common/aimodel/description-highlight.js')
	assert.ok(highlightModule?.exports.has('buildTextHighlightSegments'))
})
