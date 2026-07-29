import fs from 'fs'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

function requiredEnvironment(name) {
	const value = process.env[name]?.trim()
	if (!value) {
		throw new Error(
			`Admin H5 HTTPS is missing environment variable ${name}. ` +
			'Start HBuilderX through start-admin-local-https-dev.bat.'
		)
	}
	return value
}

export default defineConfig(() => {
	const plugins = [uni()]
	const resolve = {
		alias: {
			'@shared-auth': fileURLToPath(new URL('../shared-frontend/auth', import.meta.url)),
			'validator': fileURLToPath(new URL('node_modules/validator', import.meta.url)),
			'libphonenumber-js': fileURLToPath(new URL('node_modules/libphonenumber-js', import.meta.url))
		}
	}
	const platform = process.env.UNI_PLATFORM
	const isH5Development = ['h5', 'web'].includes(platform) && process.env.NODE_ENV !== 'production'
	if (!isH5Development) {
		return { plugins, resolve }
	}

	if (process.env.LOCAL_HTTPS_ENABLED !== 'true') {
		throw new Error('Admin H5 must be started through the local HTTPS launcher.')
	}

	const p12Path = requiredEnvironment('LOCAL_HTTPS_P12_PATH')
	const passphrase = requiredEnvironment('SERVER_SSL_KEY_STORE_PASSWORD')
	if (!fs.existsSync(p12Path)) {
		throw new Error(`Admin H5 HTTPS certificate does not exist: ${p12Path}`)
	}

	return {
		plugins,
		resolve,
		server: {
			host: '127.0.0.1',
			port: 3001,
			strictPort: true,
			headers: {
				'Cache-Control': 'no-store, max-age=0',
				Pragma: 'no-cache'
			},
			fs: {
				allow: [fileURLToPath(new URL('..', import.meta.url))]
			},
			https: {
				pfx: fs.readFileSync(p12Path),
				passphrase
			}
		}
	}
})
