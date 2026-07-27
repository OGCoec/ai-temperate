const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const projectRoot = path.resolve(__dirname, '..', '..')
const read = relativePath => fs.readFileSync(path.join(projectRoot, relativePath), 'utf8')

const uiFiles = [
	'pages/ai-models/index.vue',
	'pages/ai-models/create.vue',
	'pages/ai-models/detail.vue',
	'pages/ai-model-icons/index.vue',
	'components/admin/ai-model-form.vue'
]

const modelPages = [
	'pages/ai-models/index.vue',
	'pages/ai-models/create.vue',
	'pages/ai-models/detail.vue'
]

test('all model operations use the protected admin API layer without physical delete', () => {
	const api = read('common/admin/admin-ai-model-api.js')
	const ui = uiFiles.map(read).join('\n')

	assert.match(api, /import \{ adminRequest \} from '\.\/admin-http\.js'/)
	assert.doesNotMatch(api, /uni\.request/)
	assert.doesNotMatch(api, /method:\s*['"]DELETE['"]/)
	assert.doesNotMatch(`${api}\n${ui}`, /\/delete|删除模型|批量删除/)
	assert.match(ui, /adminAiModelApi\.list/)
	assert.match(ui, /adminAiModelApi\.create/)
	assert.match(ui, /adminAiModelApi\.patch/)
	assert.match(ui, /adminAiModelApi\.setEnabled/)
	assert.match(ui, /adminAiModelApi\.setEnabledBatch/)
})

test('detail editing uses If-Match and keeps status outside merge patch', () => {
	const api = read('common/admin/admin-ai-model-api.js')
	const form = read('common/admin/admin-ai-model-form.js')
	const detail = read('pages/ai-models/detail.vue')

	assert.match(api, /'If-Match': etag/)
	assert.match(api, /application\/merge-patch\+json/)
	assert.match(detail, /AI_MODEL_VERSION_CONFLICT/)
	assert.match(detail, /createMergePatch\(this\.snapshot, this\.draft\)/)
	assert.match(detail, /当前草稿仍保留/)
	assert.doesNotMatch(form, /patch\.enabled|enabled:\s*current/)
})

test('list filters are server-backed and selection never crosses query or page boundaries', () => {
	const list = read('pages/ai-models/index.vue')
	const api = read('common/admin/admin-ai-model-api.js')

	assert.match(api, /appendQuery\(parts, 'keyword'/)
	assert.match(api, /appendQuery\(parts, 'enabled'/)
	assert.match(api, /appendQuery\(parts, 'sortPriority'/)
	assert.match(api, /appendQuery\(parts, 'direction'/)
	assert.ok((list.match(/this\.selectedIds = \[\]/g) || []).length >= 3)
	assert.match(list, /this\.query = \{ \.\.\.this\.queryDraft, pageNum: 1 \}/)
	assert.match(list, /this\.query = \{ \.\.\.this\.query, pageNum \}/)
})

test('routes and dashboard expose all three custom-navigation model pages', () => {
	const pages = JSON.parse(read('pages.json'))
	const dashboard = read('pages/index/index.vue')
	const byPath = new Map(pages.pages.map(page => [page.path, page.style]))

	for (const route of [
		'pages/ai-models/index',
		'pages/ai-models/create',
		'pages/ai-models/detail',
		'pages/ai-model-icons/index'
	]) {
		assert.equal(byPath.get(route)?.navigationStyle, 'custom')
		assert.equal(byPath.get(route)?.backgroundColor, '#080b0d')
	}
	assert.equal(byPath.get('pages/ai-models/create')?.['app-plus']?.softinputMode, 'adjustResize')
	assert.equal(byPath.get('pages/ai-models/detail')?.['app-plus']?.softinputMode, 'adjustResize')
	assert.match(dashboard, /AI 模型目录/)
	assert.match(dashboard, /\/pages\/ai-models\/index/)
	assert.match(dashboard, /模型图标库/)
	assert.match(dashboard, /\/pages\/ai-model-icons\/index/)
})

test('model form selects icon public IDs and never submits a handwritten URL', () => {
	const formLogic = read('common/admin/admin-ai-model-form.js')
	const formView = read('components/admin/ai-model-form.vue')

	assert.match(formLogic, /iconPublicId/)
	assert.match(formLogic, /patch\.iconPublicId/)
	assert.doesNotMatch(formLogic, /command:\s*\{[^}]*\bicon:/s)
	assert.match(formView, /管理图标库/)
	assert.match(formView, /按名称或描述筛选图标/)
	assert.match(formView, /不使用图标/)
	assert.doesNotMatch(formView, /图标地址/)
})

test('icon uploads reuse protected admin headers and pages never call network primitives directly', () => {
	const http = read('common/admin/admin-http.js')
	const api = read('common/admin/admin-ai-model-icon-api.js')
	const page = read('pages/ai-model-icons/index.vue')
	const requestSection = http.slice(
		http.indexOf('export async function adminRequest'),
		http.indexOf('export async function adminUploadFile'))
	const uploadSection = http.slice(
		http.indexOf('export async function adminUploadFile'),
		http.indexOf('function request('))

	assert.match(http, /export async function adminUploadFile/)
	assert.match(http, /uni\.uploadFile/)
	assert.match(http, /requiredAdminCsrfToken/)
	assert.match(http, /X-Device-Installation-Id/)
	assert.match(http, /X-AIT-PreAuth/)
	assert.doesNotMatch(requestSection, /ADMIN_UPLOAD_METHOD_UNSUPPORTED/)
	assert.match(uploadSection, /ADMIN_UPLOAD_METHOD_UNSUPPORTED/)
	assert.match(api, /adminRequest,\s*adminUploadFile/)
	assert.match(api, /\/api\/admin\/ai-model-icons/)
	assert.doesNotMatch(`${api}\n${page}`, /uni\.(?:request|uploadFile)/)
	assert.match(page, /statusCode === 409/)
})

test('pages remain responsive, touch-sized and reduced-motion aware', () => {
	const ui = uiFiles.map(read).join('\n')

	assert.match(ui, /@media \(max-width: 767px\)/)
	assert.match(ui, /@media \(prefers-reduced-motion: reduce\)/)
	assert.match(ui, /min-height:\s*(?:88|90|92|94|96)rpx/)
	assert.match(ui, /focus-visible/)
	assert.match(ui, /aria-label=/)
	assert.match(ui, /role="alert"/)
})

test('model pages share the semantic action button without exposing physical delete', () => {
	const button = read('components/admin/admin-action-button.vue')
	const pages = modelPages.map(read).join('\n')

	for (const page of modelPages) {
		assert.match(read(page), /AdminActionButton/)
		assert.match(read(page), /components:\s*\{[^}]*AdminActionButton/)
	}
	assert.match(button, /neutral.*teal.*amber.*lime.*orange.*danger/s)
	assert.match(button, /min-height:\s*88rpx/)
	assert.match(button, /display:\s*inline-flex/)
	assert.match(button, /align-items:\s*center/)
	assert.match(button, /justify-content:\s*center/)
	assert.match(button, /@media \(pointer: coarse\)/)
	assert.match(button, /@media \(hover: hover\) and \(pointer: fine\)/)
	assert.match(button, /@media \(prefers-reduced-motion: reduce\)/)
	assert.doesNotMatch(pages, /物理删除按钮|批量删除/)
})

test('model catalog provides adaptive table, cards, filter sheet, skeletons and mobile batch actions', () => {
	const list = read('pages/ai-models/index.vue')

	assert.match(list, /class="[^"]*\bdesktop-query-panel\b[^"]*"/)
	assert.match(list, /class="mobile-query-panel"/)
	assert.match(list, /class="filter-drawer-layer"/)
	assert.match(list, /role="dialog"/)
	assert.match(list, /aria-modal="true"/)
	assert.match(list, /resetDraftFilters/)
	assert.match(list, /applyMobileFilters/)
	assert.match(list, /onBackPress\(\)/)
	assert.match(list, /class="skeleton-list"/)
	assert.match(list, /class="mobile-batch-bar"/)
	assert.match(list, /env\(safe-area-inset-bottom\)/)
	assert.match(list, /@media \(min-width: 1024px\)/)
	assert.match(list, /@media \(min-width: 768px\) and \(max-width: 1023px\)/)
	assert.match(list, /@media \(max-width: 767px\)/)
})

test('model page actions keep their approved semantic color roles', () => {
	const list = read('pages/ai-models/index.vue')
	const create = read('pages/ai-models/create.vue')
	const detail = read('pages/ai-models/detail.vue')
	const theme = read('common/app-theme.scss')

	assert.match(theme, /\$app-action-teal:\s*#39d6d2/i)
	assert.match(theme, /\$app-action-amber:\s*#f3be58/i)
	assert.match(theme, /\$app-action-lime:\s*#a8dc4a/i)
	assert.match(theme, /\$app-action-orange:\s*#e89a4a/i)
	assert.match(list, /tone="amber"[^>]*>新增模型</)
	assert.match(list, /tone="lime"[^>]*>批量启用</)
	assert.match(list, /tone="orange"[^>]*>批量停用</)
	assert.match(create, /tone="amber"[^>]*>保存模型</)
	assert.match(detail, /tone="teal"[^>]*>刷新详情</)
	assert.match(detail, /tone="lime"[^>]*>\s*启用此模型</)
	assert.match(detail, /tone="orange"[^>]*>\s*停用此模型</)
})

test('model pages stay as Vue single-file components', () => {
	for (const page of modelPages) {
		const source = read(page)
		assert.match(source, /<template>/)
		assert.match(source, /<script>/)
		assert.match(source, /<style lang="scss" scoped>/)
	}
})

test('admin request full-response mode preserves the default data-only contract', () => {
	const http = read('common/admin/admin-http.js')

	assert.match(http, /options\.returnResponse === true/)
	assert.match(http, /data: response\.data/)
	assert.match(http, /headers: \{ \.\.\.\(response\.header \|\| response\.headers \|\| \{\}\) \}/)
	assert.match(http, /resolve\(response\.data\)/)
})

test('admin request serializes structured JSON at the shared transport boundary', () => {
	const http = read('common/admin/admin-http.js')

	assert.match(http, /import \{ serializeStructuredJsonRequestBody \} from '\.\/admin-request-body\.js'/)
	assert.match(
		http,
		/data:\s*serializeStructuredJsonRequestBody\(options\.data,\s*headers\)/
	)
})
