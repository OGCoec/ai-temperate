import c from '@shikijs/langs/c'
import cpp from '@shikijs/langs/cpp'
import csharp from '@shikijs/langs/csharp'
import css from '@shikijs/langs/css'
import go from '@shikijs/langs/go'
import html from '@shikijs/langs/html'
import java from '@shikijs/langs/java'
import javascript from '@shikijs/langs/javascript'
import json from '@shikijs/langs/json'
import kotlin from '@shikijs/langs/kotlin'
import php from '@shikijs/langs/php'
import python from '@shikijs/langs/python'
import rust from '@shikijs/langs/rust'
import shellscript from '@shikijs/langs/shellscript'
import sql from '@shikijs/langs/sql'
import typescript from '@shikijs/langs/typescript'
import vue from '@shikijs/langs/vue'

// App 的 IIFE 产物不能承载 Shiki 全语言注册表的动态分包，因此只静态注册聊天中常用的语言。
export const bundledLanguages = Object.freeze({
	c,
	cpp,
	csharp,
	css,
	go,
	html,
	java,
	javascript,
	json,
	kotlin,
	php,
	python,
	rust,
	shellscript,
	sql,
	typescript,
	vue
})

export const bundledLanguagesInfo = Object.freeze([
	{ id: 'c', name: 'C', aliases: [] },
	{ id: 'cpp', name: 'C++', aliases: ['c++'] },
	{ id: 'csharp', name: 'C#', aliases: ['c#', 'cs'] },
	{ id: 'css', name: 'CSS', aliases: [] },
	{ id: 'go', name: 'Go', aliases: [] },
	{ id: 'html', name: 'HTML', aliases: [] },
	{ id: 'java', name: 'Java', aliases: [] },
	{ id: 'javascript', name: 'JavaScript', aliases: ['js', 'cjs', 'mjs'] },
	{ id: 'json', name: 'JSON', aliases: [] },
	{ id: 'kotlin', name: 'Kotlin', aliases: ['kt', 'kts'] },
	{ id: 'php', name: 'PHP', aliases: [] },
	{ id: 'python', name: 'Python', aliases: ['py'] },
	{ id: 'rust', name: 'Rust', aliases: ['rs'] },
	{ id: 'shellscript', name: 'Shell', aliases: ['bash', 'sh', 'shell', 'zsh'] },
	{ id: 'sql', name: 'SQL', aliases: [] },
	{ id: 'typescript', name: 'TypeScript', aliases: ['ts', 'cts', 'mts'] },
	{ id: 'vue', name: 'Vue', aliases: [] }
])
