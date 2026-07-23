import fs from 'fs'
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
	const platform = process.env.UNI_PLATFORM
	const isH5Development = ['h5', 'web'].includes(platform) && process.env.NODE_ENV !== 'production'
	if (!isH5Development) {
		return { plugins }
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
		server: {
			host: '127.0.0.1',
			port: 3001,
			strictPort: true,
			https: {
				pfx: fs.readFileSync(p12Path),
				passphrase
			}
		}
	}
})
