import fs from 'fs'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

function requiredEnvironment(name) {
	const value = process.env[name]?.trim()
	if (!value) {
		throw new Error(
			`本地 H5 HTTPS 缺少环境变量 ${name}。请完全退出 HBuilderX 后执行 .\\start-local-https-dev.bat；` +
				'如果 Antigravity/Codex 已经在运行，可执行 .\\start-local-https-dev.bat -HBuilderXOnly 只重开 HBuilderX。'
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
	const define = {
		__AI_CONVERSATION_STREAM_DIAGNOSTICS_ENABLED__: JSON.stringify(
			process.env.AI_CONVERSATION_STREAM_DIAGNOSTICS_ENABLED === 'true'
		),
		__AI_CONVERSATION_LIFECYCLE_DIAGNOSTICS_ENABLED__: JSON.stringify(
			process.env.AI_CONVERSATION_LIFECYCLE_DIAGNOSTICS_ENABLED === 'true'
		),
		__AI_CONVERSATION_ASYNC_GENERATION_ENABLED__: JSON.stringify(
			process.env.AI_CONVERSATION_ASYNC_GENERATION_ENABLED === 'true'
		),
		__AI_CONVERSATION_WEB_SEARCH_ENABLED__: JSON.stringify(
			process.env.AI_CONVERSATION_WEB_SEARCH_ENABLED === 'true'
		)
	}
	const platform = process.env.UNI_PLATFORM
	const isH5Development = ['h5', 'web'].includes(platform) && process.env.NODE_ENV !== 'production'
	if (!isH5Development) {
		return { plugins, resolve, define }
	}

	if (process.env.LOCAL_HTTPS_ENABLED !== 'true') {
		throw new Error(
			'本地 H5 只允许通过统一 HTTPS 启动器运行。请完全退出 HBuilderX 后执行 .\\start-local-https-dev.bat；' +
				'如果 Antigravity/Codex 已经在运行，可执行 .\\start-local-https-dev.bat -HBuilderXOnly 只重开 HBuilderX。'
		)
	}

	const p12Path = requiredEnvironment('LOCAL_HTTPS_P12_PATH')
	const passphrase = requiredEnvironment('SERVER_SSL_KEY_STORE_PASSWORD')
	if (!fs.existsSync(p12Path)) {
		throw new Error(`本地 H5 HTTPS 证书不存在：${p12Path}`)
	}

	return {
		plugins,
		resolve,
		define,
		server: {
			host: '127.0.0.1',
			port: 3000,
			strictPort: true,
			headers: {
				'Cache-Control': 'no-store, no-cache, must-revalidate',
				'Pragma': 'no-cache',
				'Expires': '0'
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
