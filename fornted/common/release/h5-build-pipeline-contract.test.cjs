const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')

const frontendRoot = path.resolve(__dirname, '..', '..')
const {
	FRONTEND_ROOT,
	H5_OUTPUT_DIR,
	UNI_CLI_PATH,
	createH5BuildConfiguration
} = require('../../scripts/build-h5.cjs')

function read(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

test('build:h5 always invokes the project-local uni compiler with fixed H5 paths', () => {
	const configuration = createH5BuildConfiguration({
		PATH: 'contract-path',
		HX_Version: 'unexpected-hbuilderx',
		UNI_CLI_CONTEXT: 'unexpected-context',
		UNI_HBUILDERX_PLUGINS: 'unexpected-plugins',
		UNI_PLATFORM: 'web'
	})

	assert.equal(FRONTEND_ROOT, frontendRoot)
	assert.equal(
		H5_OUTPUT_DIR,
		path.join(frontendRoot, 'unpackage', 'dist', 'build', 'h5')
	)
	assert.equal(
		UNI_CLI_PATH,
		path.join(
			frontendRoot,
			'node_modules',
			'@dcloudio',
			'vite-plugin-uni',
			'bin',
			'uni.js'
		)
	)
	assert.equal(configuration.command, process.execPath)
	assert.deepEqual(configuration.args, [UNI_CLI_PATH, 'build', '-p', 'h5'])
	assert.equal(configuration.options.cwd, FRONTEND_ROOT)
	assert.equal(configuration.options.env.UNI_CLI_CONTEXT, FRONTEND_ROOT)
	assert.equal(configuration.options.env.UNI_INPUT_DIR, FRONTEND_ROOT)
	assert.equal(configuration.options.env.UNI_OUTPUT_DIR, H5_OUTPUT_DIR)
	assert.equal(configuration.options.env.UNI_PLATFORM, 'h5')
	assert.equal(configuration.options.env.HX_Version, undefined)
	assert.equal(configuration.options.env.UNI_HBUILDERX_PLUGINS, undefined)
})

test('all direct DCloud build packages use one exact version in package and lock files', () => {
	const packageJson = JSON.parse(read('package.json'))
	const packageLock = JSON.parse(read('package-lock.json'))
	const dependencies = {
		...(packageJson.dependencies || {}),
		...(packageJson.devDependencies || {})
	}
	const dcloudDependencies = Object.entries(dependencies).filter(([name]) => (
		name.startsWith('@dcloudio/uni-') || name === '@dcloudio/vite-plugin-uni'
	))

	assert.ok(dcloudDependencies.length > 0)
	assert.equal(new Set(dcloudDependencies.map(([, version]) => version)).size, 1)
	for (const [name, version] of dcloudDependencies) {
		assert.doesNotMatch(version, /^[~^]/, `${name} must use an exact version`)
		assert.equal(packageLock.packages[''].devDependencies[name], version)
		assert.equal(packageLock.packages[`node_modules/${name}`].version, version)
	}
})

test('Vue 2 and Vue 3 explicitly register the authentication uni components', () => {
	const source = read('main.js')
	const components = [
		['uni-icons', 'UniIcons'],
		['uni-popup', 'UniPopup'],
		['uni-transition', 'UniTransition'],
		['uni-search-bar', 'UniSearchBar']
	]

	for (const [componentName, bindingName] of components) {
		assert.match(
			source,
			new RegExp(`Vue\\.component\\(['"]${componentName}['"],\\s*${bindingName}\\)`)
		)
		assert.match(
			source,
			new RegExp(`app\\.component\\(['"]${componentName}['"],\\s*${bindingName}\\)`)
		)
	}
})

test('the release documentation only publishes the canonical H5 directory', () => {
	const source = read('../docs/operations/frontend-public-deployment.md')
	assert.match(source, /npm run build:h5/)
	assert.match(source, /fornted\\unpackage\\dist\\build\\h5/)
	assert.doesNotMatch(
		source,
		/(?:pages deploy|verify:h5-release)[^\r\n]*build\\web/
	)
})

test('the stable H5 bootstrap scopes the body scroll lock to workspace routes', async () => {
	const source = read('static/bootstrap/viewport-bootstrap.js')
	const listeners = new Map()
	const bodyClasses = new Set()
	let pathname = '/pages/account/profile'

	const context = {
		CSS: { supports: () => true },
		document: {
			readyState: 'complete',
			body: {
				classList: {
					toggle(name, enabled) {
						if (enabled) bodyClasses.add(name)
						else bodyClasses.delete(name)
					}
				}
			},
			createElement: () => ({}),
			head: { appendChild: () => {} }
		},
		window: {
			location: {
				get pathname() { return pathname }
			},
			history: {
				pushState(_state, _title, nextPath) { pathname = nextPath },
				replaceState(_state, _title, nextPath) { pathname = nextPath }
			},
			addEventListener(name, listener) { listeners.set(name, listener) }
		}
	}

	vm.runInNewContext(source, context)
	assert.equal(bodyClasses.has('ait-workspace-active'), true)

	context.window.history.pushState(null, '', '/pages/account/membership-plans')
	await Promise.resolve()
	assert.equal(bodyClasses.has('ait-workspace-active'), false)

	pathname = '/pages/account/profile'
	listeners.get('popstate')()
	await Promise.resolve()
	assert.equal(bodyClasses.has('ait-workspace-active'), true)
})
