const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const frontendRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

test('catalog page uses the protected model API and covers loading, empty, retry, and append states', () => {
	const entry = read('pages/ai-models/catalog.vue')
	const page = read('components/user/workspace/user-model-catalog.vue')

	assert.match(entry, /<user-workspace[\s\S]*initial-destination="models"/)
	assert.match(page, /aiModelApi\.list/)
	assert.match(page, /ai-model-catalog-store/)
	assert.match(page, /readAiModelCatalog/)
	assert.match(page, /refreshAiModelCatalog/)
	assert.match(page, /catalog-session-pending/)
	assert.doesNotMatch(page, /v-if="authReady" class="catalog-page"/)
	assert.match(page, /!this\.hasLoaded && !this\.initialLoading && !this\.initialError/)
	assert.match(page, /catalog-skeleton/)
	assert.match(page, /catalog-empty/)
	assert.match(page, /catalog-error/)
	assert.match(page, /loadNextPage/)
	assert.match(page, /refreshCatalog/)
	assert.match(page, /submitSearch/)
	assert.match(page, /clearSearch/)
	assert.match(page, /setAiModelCatalogKeyword/)
	assert.match(page, /modelNameMatchedTokens/)
	assert.match(page, /descriptionMatchedTokens/)
	assert.match(page, /buildTextHighlightSegments/)
	assert.match(page, /modelNameSegments/)
	assert.match(page, /class="catalog-model-description"/)
	assert.match(page, /catalog-text-match/)
	assert.doesNotMatch(page, /v-html/)
	assert.match(
		page,
		/\.catalog-search-submit,\s*\.catalog-search-clear\s*\{[^}]*display:\s*flex;[^}]*align-items:\s*center;[^}]*justify-content:\s*center;/
	)
	assert.match(page, /this\.failedIconIds\s*=\s*\{\}/)
	assert.doesNotMatch(page, /uni\.navigateTo\(/)
	assert.match(page, /\$emit\('open-model', modelPublicId\)/)
})

test('model detail uses a public ID, handles an unavailable model, and explains cache-input ratios', () => {
	const entry = read('pages/ai-models/detail.vue')
	const page = read('components/user/workspace/user-model-detail.vue')

	assert.match(entry, /<user-workspace[\s\S]*initial-destination="models"/)
	assert.match(page, /aiModelApi\.detail/)
	assert.match(page, /AI_MODEL_NOT_FOUND/)
	assert.match(page, /cached_tokens/)
	assert.match(page, /Redis/)
	assert.match(page, /\$emit\('back'\)/)
	assert.doesNotMatch(page, /uni\.(?:navigateBack|redirectTo|reLaunch)\(/)
})
