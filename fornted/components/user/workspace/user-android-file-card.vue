<template>
	<view class="android-file-card" :class="`is-${category.toLowerCase()}`">
		<view class="android-file-badge" aria-hidden="true">
			<uni-icons :type="icon" size="22" color="#9be4c5" />
			<text>{{ extension }}</text>
		</view>
		<view class="android-file-copy">
			<text class="android-file-name">{{ attachment.fileName || '未命名文件' }}</text>
			<text class="android-file-meta">{{ typeLabel }} · {{ sizeLabel }}</text>
			<text v-if="stateLabel" class="android-file-state">{{ stateLabel }}</text>
		</view>
		<view class="android-file-actions">
			<button
				v-if="failed"
				class="android-file-action"
				type="button"
				@click="$emit('retry', attachment)"
			>重试</button>
			<button
				v-else
				class="android-file-action"
				type="button"
				:disabled="!available"
				@click="$emit('open', attachment)"
			>打开</button>
			<button
				class="android-file-action"
				type="button"
				:disabled="!available"
				@click="$emit('download', attachment)"
			>下载</button>
		</view>
	</view>
</template>

<script>
	import { attachmentCategory } from '@/common/aichat/ai-conversation-upload-state.js'

	export default {
		name: 'UserAndroidFileCard',
		props: {
			attachment: { type: Object, required: true }
		},
		emits: ['open', 'retry', 'download'],
		computed: {
			category() {
				return attachmentCategory(this.attachment)
			},
			extension() {
				const name = String(this.attachment?.fileName || '')
				const dot = name.lastIndexOf('.')
				return dot >= 0 ? name.slice(dot + 1, dot + 7).toUpperCase() : 'FILE'
			},
			icon() {
				return ({
					ARCHIVE: 'folder-add',
					DOCUMENT: 'paperclip',
					AUDIO: 'mic',
					OTHER: 'paperclip'
				})[this.category] || 'paperclip'
			},
			typeLabel() {
				if (['JAR', 'WAR', 'EAR'].includes(this.extension)) return 'Java Archive'
				if (this.extension === 'JAVA') return 'Java 源文件'
				return ({
					ARCHIVE: '压缩归档',
					DOCUMENT: '文档',
					AUDIO: '音频',
					OTHER: '文件'
				})[this.category] || '文件'
			},
			sizeLabel() {
				const bytes = Number(this.attachment?.sizeBytes)
				if (!Number.isFinite(bytes) || bytes < 0) return '未知大小'
				if (bytes < 1024) return `${bytes} B`
				if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
				return `${(bytes / 1024 / 1024).toFixed(1)} MB`
			},
			available() {
				return this.attachment?.state === 'AVAILABLE' && Boolean(this.attachment?.url)
			},
			failed() {
				return ['FAILED', 'ERROR', 'STORAGE_FAILED']
					.includes(String(this.attachment?.state || '').toUpperCase())
			},
			stateLabel() {
				if (this.failed) return '文件暂时不可用'
				if (!this.available) return '文件正在准备'
				return ''
			}
		}
	}
</script>

<style lang="scss" scoped>
	.android-file-card { width: 100%; min-width: 0; padding: 12px; display: grid; grid-template-columns: 48px minmax(0, 1fr); gap: 10px 12px; border: 1px solid #313a35; border-radius: 12px; background: #141a17; box-sizing: border-box; }
	.android-file-badge { width: 48px; height: 48px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px; border-radius: 10px; background: #1b2721; color: #9be4c5; font-size: 8px; font-weight: 800; }
	.android-file-copy { min-width: 0; display: flex; flex-direction: column; justify-content: center; gap: 3px; }
	.android-file-name { overflow: hidden; color: #e2e9e5; font-size: 13px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
	.android-file-meta, .android-file-state { color: #87938c; font-size: 11px; }
	.android-file-state { color: #d9a16b; }
	.android-file-actions { grid-column: 1 / -1; display: flex; justify-content: flex-end; gap: 7px; }
	.android-file-action { min-height: 34px; margin: 0; padding: 0 12px; border: 1px solid #35433c; border-radius: 8px; background: #19211d; color: #cdd8d2; font-size: 11px; line-height: 32px; }
	.android-file-action::after { border: 0; }
	.android-file-action[disabled] { opacity: .45; }
</style>
