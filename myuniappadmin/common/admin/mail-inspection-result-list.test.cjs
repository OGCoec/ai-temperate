const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

function loadComponentOptions() {
	const filePath = path.resolve(__dirname, '..', '..', 'components', 'admin', 'mail-inspection-result-list.vue')
	const source = fs.readFileSync(filePath, 'utf8')
	const script = source.match(/<script>\s*([\s\S]*?)<\/script>/)?.[1]
	if (!script) throw new Error('result list component script was not found')

	const executable = script
		.replace(/^import AdminActionButton.*$/m, '')
		.replace(/import \{[\s\S]*?\} from '@\/common\/admin\/mail-inspection-presenter\.js'/m, '')
		.replace('export default', 'globalThis.component =')
	const context = {
		AdminActionButton: {},
		MAIL_INSPECTION_RESULT_GROUPS: [],
		mailInspectionResultGroupOptions: () => [],
		countMailInspectionGroups: () => ({}),
		uni: {
			getSystemInfoSync: () => ({ windowWidth: 1200 }),
			upx2px: value => value
		},
		component: null
	}
	vm.runInNewContext(executable, context)
	return context.component
}

test('virtual result window renders at most forty ordered items', () => {
	const component = loadComponentOptions()
	const filteredResults = Array.from({ length: 1_000 }, (_, index) => ({ lineNumber: index + 1 }))
	const visible = component.computed.visibleResults.call({
		filteredResults,
		windowStart: 80
	})

	assert.equal(visible.length, 40)
	assert.equal(visible[0].lineNumber, 81)
	assert.equal(visible[39].lineNumber, 120)
})

test('scrolling advances a bounded result window with twenty-item overscan', () => {
	const component = loadComponentOptions()
	const context = {
		filteredResults: Array.from({ length: 1_000 }),
		rowHeightPixels: 100,
		virtualScrollTop: 0,
		windowStart: 0
	}

	component.methods.onResultScroll.call(context, { detail: { scrollTop: 10_000 } })

	assert.equal(context.virtualScrollTop, 10_000)
	assert.equal(context.windowStart, 80)
})

test('result details keep only one selected line outside the virtual rows', () => {
	const component = loadComponentOptions()
	const context = { selectedLineNumber: null }
	context.expanded = lineNumber => component.methods.expanded.call(context, lineNumber)

	component.methods.toggle.call(context, 101)
	assert.equal(context.selectedLineNumber, 101)
	component.methods.toggle.call(context, 1_000)
	assert.equal(context.selectedLineNumber, 1_000)
	component.methods.toggle.call(context, 1_000)
	assert.equal(context.selectedLineNumber, null)
})

test('business filters emit stable categories and reset virtual state', () => {
	const component = loadComponentOptions()
	const emitted = []
	const context = {
		activeGroup: 'ALL',
		selectedLineNumber: 9,
		businessOptionValues: new Set(['UNREGISTERED', 'REGISTERED']),
		$emit: (name, value) => emitted.push([name, value]),
		resetWindow() {
			this.windowStart = 0
			this.virtualScrollTop = 0
		},
		windowStart: 17,
		virtualScrollTop: 600
	}

	component.methods.selectGroup.call(context, 'UNREGISTERED')

	assert.equal(context.activeGroup, 'UNREGISTERED')
	assert.equal(context.selectedLineNumber, null)
	assert.equal(context.windowStart, 0)
	assert.equal(context.virtualScrollTop, 0)
	assert.deepEqual(emitted, [
		['select-business-category', 'UNREGISTERED'],
		['request-unregistered-reveal', undefined]
	])
})
