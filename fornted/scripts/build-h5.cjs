const fs = require('node:fs')
const path = require('node:path')
const { spawnSync } = require('node:child_process')

const FRONTEND_ROOT = path.resolve(__dirname, '..')
const H5_OUTPUT_DIR = path.join(
	FRONTEND_ROOT,
	'unpackage',
	'dist',
	'build',
	'h5'
)
const UNI_CLI_PATH = path.join(
	FRONTEND_ROOT,
	'node_modules',
	'@dcloudio',
	'vite-plugin-uni',
	'bin',
	'uni.js'
)

function createH5BuildConfiguration(environment = process.env) {
	const buildEnvironment = { ...environment }
	delete buildEnvironment.HX_Version
	delete buildEnvironment.UNI_HBUILDERX_PLUGINS

	return {
		command: process.execPath,
		args: [UNI_CLI_PATH, 'build', '-p', 'h5'],
		options: {
			cwd: FRONTEND_ROOT,
			stdio: 'inherit',
			env: {
				...buildEnvironment,
				UNI_CLI_CONTEXT: FRONTEND_ROOT,
				UNI_INPUT_DIR: FRONTEND_ROOT,
				UNI_OUTPUT_DIR: H5_OUTPUT_DIR,
				UNI_PLATFORM: 'h5'
			}
		}
	}
}

function assertSafeOutputDirectory() {
	const expected = path.join(
		FRONTEND_ROOT,
		'unpackage',
		'dist',
		'build',
		'h5'
	)
	if (path.resolve(H5_OUTPUT_DIR) !== path.resolve(expected)) {
		throw new Error(`Refusing to clean unexpected H5 output directory: ${H5_OUTPUT_DIR}`)
	}
}

function main() {
	if (!fs.existsSync(UNI_CLI_PATH)) {
		throw new Error(
			`Project-local uni compiler is missing: ${UNI_CLI_PATH}. Run the approved dependency installation before building.`
		)
	}

	assertSafeOutputDirectory()
	fs.rmSync(H5_OUTPUT_DIR, { recursive: true, force: true })

	const configuration = createH5BuildConfiguration()
	const result = spawnSync(
		configuration.command,
		configuration.args,
		configuration.options
	)
	if (result.error) throw result.error
	if (result.status !== 0) {
		process.exitCode = Number.isInteger(result.status) ? result.status : 1
	}
}

if (require.main === module) main()

module.exports = {
	FRONTEND_ROOT,
	H5_OUTPUT_DIR,
	UNI_CLI_PATH,
	createH5BuildConfiguration
}
