import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { bundledLanguagesInfo } from 'shiki/langs'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const targetPath = path.resolve(scriptDirectory,
	'../common/aichat/ai-code-languages-app.generated.js')

function generatedSource() {
	const imports = bundledLanguagesInfo.map((language, index) =>
		`import language${index} from '@shikijs/langs/${language.id}'`).join('\n')
	const registrations = bundledLanguagesInfo.map((language, index) =>
		`\t${JSON.stringify(language.id)}: language${index}`).join(',\n')
	const metadata = JSON.stringify(bundledLanguagesInfo, null, '\t')
	return `// 此文件由 scripts/generate-ai-code-languages-app.mjs 生成，禁止手工修改。\n${imports}\n\nexport const bundledLanguages = Object.freeze({\n${registrations}\n})\n\nexport const bundledLanguagesInfo = Object.freeze(${metadata})\n`
}

const expected = generatedSource()
if (process.argv.includes('--check')) {
	const actual = fs.existsSync(targetPath)
		? fs.readFileSync(targetPath, 'utf8') : ''
	if (actual !== expected) {
		console.error('Android Shiki 语言注册表与当前 Shiki 版本不一致。')
		process.exitCode = 1
	}
} else {
	fs.writeFileSync(targetPath, expected, 'utf8')
}
