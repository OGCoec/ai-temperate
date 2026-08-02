<template>
	<scroll-view
		v-if="attachments.length"
		class="pending-attachment-scroll"
		scroll-x
		:show-scrollbar="false"
		aria-label="待发送附件"
	>
		<view class="pending-attachment-list">
			<view
				v-for="file in attachments"
				:key="file.localId"
				class="pending-attachment-card"
				:aria-label="cardAriaLabel(file)"
			>
				<view class="pending-attachment-preview">
					<image
						v-if="previewImage(file)"
						class="pending-attachment-image"
						:src="file.path"
						mode="aspectFill"
						:aria-label="`${file.fileName} 本地预览`"
					/>
					<view v-else class="pending-attachment-kind" aria-hidden="true">
						<uni-icons :type="kindIcon(file)" size="24" color="#9be4c5" />
						<text>{{ extension(file) }}</text>
					</view>

					<view
						v-if="file.state === 'PREPARING' || file.state === 'UPLOADING'"
						class="pending-attachment-overlay"
						:role="file.state === 'UPLOADING' ? 'progressbar' : 'status'"
						:aria-valuemin="file.state === 'UPLOADING' ? 0 : undefined"
						:aria-valuemax="file.state === 'UPLOADING' ? 100 : undefined"
						:aria-valuenow="file.state === 'UPLOADING' ? file.progress : undefined"
						:aria-valuetext="statusLabel(file)"
						:aria-label="statusLabel(file)"
					>
						<view
							class="pending-progress-ring"
							:class="{ 'pending-progress-ring-indeterminate': file.state === 'PREPARING' }"
							:style="progressStyle(file)"
						>
							<view class="pending-progress-ring-core">
								<text>{{ file.state === 'PREPARING' ? '…' : `${file.progress}%` }}</text>
							</view>
						</view>
					</view>
					<view v-else-if="file.state === 'UPLOADED'" class="pending-state-mark pending-state-success" role="status" aria-label="上传成功">✓</view>
					<view v-else-if="file.state === 'FAILED'" class="pending-state-mark pending-state-failed" role="status" aria-label="上传失败">!</view>
					<text v-if="!compatible(file)" class="pending-incompatible">不受当前模型支持</text>
				</view>

				<text class="pending-attachment-name">{{ file.fileName }}</text>
				<text class="pending-attachment-meta">{{ categoryLabel(file) }} · {{ formatSize(file.sizeBytes) }}</text>
				<view class="pending-attachment-actions">
					<button
						v-if="file.state === 'FAILED'"
						class="pending-action pending-retry"
						type="button"
						:disabled="generating"
						:aria-label="`重试上传 ${file.fileName}`"
						@click="$emit('retry', file.localId)"
					>重试</button>
					<button
						class="pending-action pending-remove"
						type="button"
						:disabled="generating"
						:aria-label="`移除附件 ${file.fileName}`"
						@click="$emit('remove', file.localId)"
					>×</button>
				</view>
			</view>
		</view>
	</scroll-view>
</template>

<script>
	import {
		attachmentCategory,
		isAttachmentCompatible
	} from '@/common/aichat/ai-conversation-upload-state.js'

	export default {
		props: {
			attachments: { type: Array, default: () => [] },
			model: { type: Object, default: null },
			generating: { type: Boolean, default: false }
		},
		emits: ['remove', 'retry'],
		methods: {
			previewImage(file) {
				return file.contentType?.startsWith('image/')
					&& file.contentType !== 'image/svg+xml'
					&& Boolean(file.path)
			},
			compatible(file) {
				return isAttachmentCompatible(file, this.model)
			},
			kindIcon(file) {
				return ({
					IMAGE: 'image',
					AUDIO: 'mic',
					VIDEO: 'videocam',
					ARCHIVE: 'folder-add',
					DOCUMENT: 'paperclip',
					OTHER: 'paperclip'
				})[attachmentCategory(file)]
			},
			categoryLabel(file) {
				return ({
					IMAGE: '图片',
					AUDIO: '音频',
					VIDEO: '视频',
					ARCHIVE: '压缩包',
					DOCUMENT: '文档',
					OTHER: '文件'
				})[attachmentCategory(file)]
			},
			extension(file) {
				const name = String(file.fileName || '')
				const dot = name.lastIndexOf('.')
				return dot >= 0 ? name.slice(dot + 1, dot + 6).toUpperCase() : 'FILE'
			},
			formatSize(value) {
				const bytes = Number(value)
				if (!Number.isFinite(bytes)) return '未知大小'
				if (bytes < 1024) return `${bytes} B`
				if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
				return `${(bytes / 1024 / 1024).toFixed(1)} MB`
			},
			progressStyle(file) {
				if (file.state === 'PREPARING') return {}
				const progress = Math.max(0, Math.min(100, Number(file.progress) || 0))
				return { background: `conic-gradient(#37d39a ${progress * 3.6}deg, #38433d 0deg)` }
			},
			statusLabel(file) {
				if (file.state === 'PREPARING') return `${file.fileName} 正在准备上传`
				if (file.state === 'UPLOADED') return `${file.fileName} 上传成功`
				if (file.state === 'FAILED') return `${file.fileName} 上传失败，${file.error || '请重试或删除'}`
				return file.retrying
					? `${file.fileName} 正在自动重试，进度 ${file.progress}%`
					: `${file.fileName} 上传进度 ${file.progress}%`
			},
			cardAriaLabel(file) {
				const compatibility = this.compatible(file) ? '' : '，不受当前模型支持'
				return `${file.fileName}，${this.categoryLabel(file)}，${this.formatSize(file.sizeBytes)}，${this.statusLabel(file)}${compatibility}`
			}
		}
	}
</script>

<style lang="scss">
	@import '@/common/ui/user-material.scss';
	.pending-attachment-scroll { width: 100%; margin-bottom: 9px; white-space: nowrap; }
	.pending-attachment-list { display: inline-flex; align-items: flex-start; gap: 10px; padding: 2px; }
	.pending-attachment-card { position: relative; width: 88px; flex: 0 0 88px; padding-bottom: 2px; white-space: normal; }
	.pending-attachment-preview { position: relative; width: 88px; height: 88px; overflow: hidden; border: 1px solid #34413a; border-radius: 13px; background: #181d1a; box-sizing: border-box; }
	.pending-attachment-image { width: 100%; height: 100%; display: block; }
	.pending-attachment-kind { width: 100%; height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 5px; color: #9be4c5; font-size: 10px; font-weight: 750; }
	.pending-attachment-overlay { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; background: rgba(8, 11, 9, .62); }
	.pending-progress-ring { width: 48px; height: 48px; padding: 4px; border-radius: 50%; background: conic-gradient(#37d39a 0deg, #38433d 0deg); box-sizing: border-box; }
	.pending-progress-ring-core { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; border-radius: 50%; background: #111512; color: #dff8ed; font-size: 10px; font-variant-numeric: tabular-nums; }
	.pending-progress-ring-indeterminate { border: 3px solid #38433d; border-top-color: #37d39a; background: transparent; animation: pending-ring-spin .8s linear infinite; }
	.pending-state-mark { position: absolute; top: 6px; right: 6px; width: 22px; height: 22px; display: flex; align-items: center; justify-content: center; border-radius: 50%; font-size: 13px; font-weight: 800; box-shadow: 0 2px 8px rgba(0, 0, 0, .3); }
	.pending-state-success { background: #37d39a; color: #07110d; }
	.pending-state-failed { background: #d95d59; color: white; }
	.pending-incompatible { position: absolute; left: 4px; right: 4px; bottom: 4px; padding: 3px 4px; border-radius: 5px; background: rgba(172, 74, 68, .92); color: #fff4f2; font-size: 8px; line-height: 1.25; text-align: center; }
	.pending-attachment-name { display: block; margin-top: 5px; overflow: hidden; color: #dce5e0; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
	.pending-attachment-meta { display: block; margin-top: 2px; overflow: hidden; color: #76817b; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
	.pending-attachment-actions { min-height: 28px; margin-top: 4px; display: flex; justify-content: flex-end; gap: 3px; }
	.pending-action { @include user-frosted-control; min-width: 28px; min-height: 28px; margin: 0; padding: 0 6px; border-radius: 8px; color: #cbd4cf; font-size: 10px; line-height: 1; }
	.pending-action::after { border: 0; }
	.pending-retry { color: #9be4c5; }
	.pending-remove { font-size: 18px; }
	.pending-action:focus-visible { outline: 2px solid rgba(55, 211, 154, .45); outline-offset: 2px; }
	@keyframes pending-ring-spin { to { transform: rotate(360deg); } }
	@media (prefers-reduced-motion: reduce) { .pending-progress-ring-indeterminate { animation: none; } }
</style>
