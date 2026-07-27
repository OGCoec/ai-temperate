<template>
	<view class="icon-page">
		<view class="icon-shell">
			<view class="page-head">
				<admin-action-button tone="neutral" size="compact" @click="goBack">返回</admin-action-button>
				<view class="head-copy">
					<text class="eyebrow">MODEL ASSET LIBRARY</text>
					<text class="page-title">模型图标库</text>
					<text class="page-copy">一个图标资源可被多个模型复用；本地图片存入 OSS，外部图片只登记验证后的 HTTPS 地址。</text>
				</view>
			</view>

			<view v-if="message" class="message" :class="{ error: messageError }" role="alert">
				<text class="message-summary">{{ message }}</text>
				<view v-if="messageDiagnostics" class="message-diagnostics">
					<text v-if="messageDiagnostics.code">错误码：{{ messageDiagnostics.code }}</text>
					<text v-if="messageDiagnostics.exceptionType">异常类型：{{ messageDiagnostics.exceptionType }}</text>
					<text v-if="messageDiagnostics.exceptionMessage">异常信息：{{ messageDiagnostics.exceptionMessage }}</text>
					<text v-if="messageDiagnostics.rootCauseType">根因类型：{{ messageDiagnostics.rootCauseType }}</text>
					<text v-if="messageDiagnostics.rootCauseMessage">根因信息：{{ messageDiagnostics.rootCauseMessage }}</text>
				</view>
			</view>

			<view class="create-panel">
				<view class="panel-heading">
					<view>
						<text class="panel-title">新增图标资源</text>
						<text class="panel-copy">支持 PNG、JPEG/JPG、WebP、GIF、ICO、AVIF 和安全 SVG，最大 2 MiB。外链不会复制到 OSS。</text>
					</view>
					<view class="source-tabs" role="tablist" aria-label="图标来源">
						<button type="button" :class="{ active: createMode === 'UPLOAD' }" @click="createMode = 'UPLOAD'">本地上传</button>
						<button type="button" :class="{ active: createMode === 'REMOTE' }" @click="createMode = 'REMOTE'">外部 URL</button>
					</view>
				</view>
				<view class="form-grid">
					<view class="field">
						<text class="label">图标名称</text>
						<input v-model="createForm.iconName" maxlength="128" placeholder="例如 OpenAI" :disabled="writing" />
					</view>
					<view class="field">
						<text class="label">描述</text>
						<input v-model="createForm.description" maxlength="512" placeholder="例如 OpenAI / ChatGPT 模型族" :disabled="writing" />
					</view>
				</view>
				<view v-if="createMode === 'REMOTE'" class="field">
					<text class="label">外部 HTTPS 图片地址</text>
					<input v-model="createForm.iconUrl" maxlength="1024" placeholder="https://cdn.example.com/icon.png" :disabled="writing" />
				</view>
				<view v-else class="upload-row">
					<admin-action-button tone="teal" :disabled="writing" @click="chooseCreateFile">选择图片</admin-action-button>
					<text>{{ createForm.fileName || '尚未选择本地图片' }}</text>
				</view>
				<view class="panel-actions">
					<admin-action-button tone="amber" :loading="writing" @click="createIcon">创建图标</admin-action-button>
				</view>
			</view>

			<view v-if="editing" class="edit-panel">
				<view class="panel-heading">
					<view>
						<text class="panel-title">编辑 {{ editing.iconName }}</text>
						<text class="panel-copy">外部 URL 留空时只修改名称和描述；填写后会切换为外链来源。</text>
					</view>
					<admin-action-button tone="neutral" size="compact" :disabled="writing" @click="cancelEdit">取消</admin-action-button>
				</view>
				<view class="form-grid">
					<view class="field">
						<text class="label">图标名称</text>
						<input v-model="editForm.iconName" maxlength="128" :disabled="writing" />
					</view>
					<view class="field">
						<text class="label">描述</text>
						<input v-model="editForm.description" maxlength="512" :disabled="writing" />
					</view>
				</view>
				<view class="field">
					<text class="label">切换为新的外部 HTTPS URL（可选）</text>
					<input v-model="editForm.iconUrl" maxlength="1024" placeholder="留空则保留当前图片" :disabled="writing" />
				</view>
				<view class="panel-actions">
					<admin-action-button tone="teal" :loading="writing" @click="saveEdit">保存字段</admin-action-button>
					<admin-action-button tone="amber" :disabled="writing" @click="chooseReplacement">替换本地图片</admin-action-button>
				</view>
			</view>

			<view class="library-panel">
				<view class="library-head">
					<view>
						<text class="panel-title">资源列表</text>
						<text class="panel-copy">共 {{ page.total || 0 }} 条，按名称排序。</text>
					</view>
					<admin-action-button tone="neutral" size="compact" :disabled="loading" @click="loadPage">刷新</admin-action-button>
				</view>
				<view v-if="loading" class="empty-state" role="status">正在加载图标资源…</view>
				<view v-else-if="!icons.length" class="empty-state">还没有图标资源，请先在上方创建。</view>
				<view v-else class="icon-grid">
					<view v-for="icon in icons" :key="icon.publicId" class="icon-card">
						<view v-if="previewFailures[icon.publicId]" class="preview preview-fallback" role="img" :aria-label="`${icon.iconName} 预览失败`">!</view>
						<image v-else class="preview" :src="icon.iconUrl" mode="aspectFit" :alt="icon.iconName" @error="markPreviewFailure(icon.publicId)" />
						<view class="identity">
							<text class="icon-name">{{ icon.iconName }}</text>
							<text class="description">{{ icon.description || '暂无描述' }}</text>
							<text class="source">{{ sourceLabel(icon.iconUrl) }}</text>
						</view>
						<view class="card-actions">
							<admin-action-button tone="neutral" size="compact" :disabled="writing" @click="startEdit(icon)">编辑</admin-action-button>
							<admin-action-button tone="danger" size="compact" :disabled="writing" @click="confirmDelete(icon)">删除</admin-action-button>
						</view>
					</view>
				</view>
				<view v-if="page.pages > 1" class="pagination">
					<admin-action-button tone="neutral" size="compact" :disabled="loading || !page.hasPrevious" @click="changePage(-1)">上一页</admin-action-button>
					<text>第 {{ page.pageNum }} / {{ page.pages }} 页</text>
					<admin-action-button tone="neutral" size="compact" :disabled="loading || !page.hasNext" @click="changePage(1)">下一页</admin-action-button>
				</view>
			</view>
		</view>
	</view>
</template>

<script>
import AdminActionButton from '@/components/admin/admin-action-button.vue'
import {
	adminAiModelIconApi,
	aiModelIconUrlSource
} from '@/common/admin/admin-ai-model-icon-api.js'

const MAX_FILE_BYTES = 2 * 1024 * 1024
const ICON_FILE_EXTENSIONS = ['.png', '.jpg', '.jpeg', '.webp', '.gif', '.ico', '.avif', '.svg']

function emptyCreateForm() {
	return { iconName: '', description: '', iconUrl: '', filePath: '', fileName: '' }
}

export default {
	components: { AdminActionButton },
	data() {
		return {
			createMode: 'UPLOAD',
			createForm: emptyCreateForm(),
			editing: null,
			editForm: { iconName: '', description: '', iconUrl: '' },
			icons: [],
			page: { pageNum: 1, pageSize: 24, total: 0, pages: 0, hasPrevious: false, hasNext: false },
			loading: false,
			writing: false,
			message: '',
			messageError: false,
			messageDiagnostics: null,
			previewFailures: {}
		}
	},
	onShow() {
		this.loadPage()
	},
	methods: {
		sourceLabel: aiModelIconUrlSource,
		goBack() {
			if (!this.writing) uni.navigateBack({ delta: 1 })
		},
		setMessage(message, error = false, diagnosticError = null) {
			this.message = message
			this.messageError = error
			this.messageDiagnostics = error ? this.errorDiagnostics(diagnosticError) : null
		},
		errorDiagnostics(error) {
			if (!error) return null
			const diagnostics = {
				code: error.code || '',
				exceptionType: error.exceptionType || '',
				exceptionMessage: error.exceptionMessage || '',
				rootCauseType: error.rootCauseType || '',
				rootCauseMessage: error.rootCauseMessage || ''
			}
			return Object.values(diagnostics).some(Boolean) ? diagnostics : null
		},
		async loadPage() {
			if (this.loading) return
			this.loading = true
			try {
				const result = await adminAiModelIconApi.list(this.page.pageNum, this.page.pageSize)
				this.icons = Array.isArray(result?.icons) ? result.icons : []
				this.page = { ...this.page, ...result }
				this.previewFailures = {}
			} catch (error) {
				this.setMessage(error?.message || '模型图标列表加载失败。', true, error)
			} finally {
				this.loading = false
			}
		},
		changePage(offset) {
			const next = this.page.pageNum + offset
			if (next < 1 || next > this.page.pages) return
			this.page.pageNum = next
			this.loadPage()
		},
		chooseImage() {
			return new Promise((resolve, reject) => {
				uni.chooseImage({
					count: 1,
					sizeType: ['original'],
					sourceType: ['album', 'camera'],
					extension: ICON_FILE_EXTENSIONS,
					success: result => {
						const file = result.tempFiles?.[0]
						if (Number(file?.size || 0) > MAX_FILE_BYTES) {
							reject(new Error('图片不能超过 2 MiB。'))
							return
						}
						const filePath = result.tempFilePaths?.[0] || file?.path || ''
						if (!filePath) {
							reject(new Error('没有取得可上传的本地图片。'))
							return
						}
						resolve({ filePath, fileName: file?.name || filePath.split('/').pop() || '已选择图片' })
					},
					fail: cause => {
						const error = new Error(cause?.errMsg?.includes('cancel') ? '已取消选择图片。' : '无法读取本地图片。')
						error.cancelled = cause?.errMsg?.includes('cancel')
						reject(error)
					}
				})
			})
		},
		markPreviewFailure(publicId) {
			this.previewFailures = { ...this.previewFailures, [publicId]: true }
		},
		iconErrorMessage(error, fallback) {
			switch (error?.code) {
				case 'AI_MODEL_ICON_IMAGE_FORMAT_UNSUPPORTED':
					return '只支持 PNG、JPEG/JPG、WebP、GIF、ICO、AVIF 和 SVG 图标。'
				case 'AI_MODEL_ICON_IMAGE_UNSAFE':
					return '图片包含危险内容，或动画、容器、尺寸超出安全限制。'
				case 'AI_MODEL_ICON_DECODER_UNAVAILABLE':
					return '服务器图片解码器暂时不可用，请稍后重试。'
				default:
					return error?.message || fallback
			}
		},
		async chooseCreateFile() {
			try {
				const selected = await this.chooseImage()
				this.createForm.filePath = selected.filePath
				this.createForm.fileName = selected.fileName
				this.setMessage('')
			} catch (error) {
				if (!error?.cancelled) this.setMessage(error.message, true)
			}
		},
		async createIcon() {
			if (this.writing) return
			this.writing = true
			this.setMessage('')
			try {
				if (this.createMode === 'REMOTE') {
					await adminAiModelIconApi.createRemote({
						iconName: this.createForm.iconName,
						description: this.createForm.description,
						iconUrl: this.createForm.iconUrl
					})
				} else {
					await adminAiModelIconApi.createUpload(this.createForm)
				}
				this.createForm = emptyCreateForm()
				this.page.pageNum = 1
				this.setMessage('模型图标已经创建。')
				await this.loadPage()
			} catch (error) {
				this.setMessage(this.iconErrorMessage(error, '模型图标创建失败。'), true, error)
			} finally {
				this.writing = false
			}
		},
		startEdit(icon) {
			this.editing = icon
			this.editForm = {
				iconName: icon.iconName,
				description: icon.description || '',
				iconUrl: ''
			}
		},
		cancelEdit() {
			this.editing = null
		},
		async saveEdit() {
			if (!this.editing || this.writing) return
			const patch = {
				iconName: this.editForm.iconName,
				description: this.editForm.description || null
			}
			if (String(this.editForm.iconUrl || '').trim()) patch.iconUrl = this.editForm.iconUrl
			this.writing = true
			try {
				await adminAiModelIconApi.patch(this.editing.publicId, patch)
				this.editing = null
				this.setMessage('图标字段已经更新。')
				await this.loadPage()
			} catch (error) {
				this.setMessage(error?.message || '图标字段更新失败。', true, error)
			} finally {
				this.writing = false
			}
		},
		async chooseReplacement() {
			if (!this.editing || this.writing) return
			try {
				const selected = await this.chooseImage()
				this.writing = true
				await adminAiModelIconApi.replaceFile(this.editing.publicId, selected.filePath)
				this.editing = null
				this.setMessage('图标图片已经替换。')
				await this.loadPage()
			} catch (error) {
				if (!error?.cancelled) {
					this.setMessage(this.iconErrorMessage(error, '图标替换失败。'), true, error)
				}
			} finally {
				this.writing = false
			}
		},
		confirmDelete(icon) {
			if (this.writing) return
			uni.showModal({
				title: `删除 ${icon.iconName}？`,
				content: '仍被任何模型引用时，后端会拒绝删除且不会自动清空模型图标。',
				confirmText: '确认删除',
				confirmColor: '#d9686b',
				success: result => {
					if (result.confirm) this.deleteIcon(icon)
				}
			})
		},
		async deleteIcon(icon) {
			this.writing = true
			try {
				await adminAiModelIconApi.delete(icon.publicId)
				this.setMessage('模型图标已经删除。')
				if (this.icons.length === 1 && this.page.pageNum > 1) this.page.pageNum -= 1
				await this.loadPage()
			} catch (error) {
				const message = error?.statusCode === 409
					? '该图标仍被模型引用，请先为相关模型更换或清除图标。'
					: error?.message || '模型图标删除失败。'
				this.setMessage(message, true, error)
			} finally {
				this.writing = false
			}
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.icon-page { min-height: 100vh; background: $app-bg; color: $app-text; }
.icon-shell { width: min(1180px, calc(100% - 40rpx)); margin: 0 auto; padding: 30rpx 0 80rpx; }
.page-head, .panel-heading, .library-head, .panel-actions, .pagination, .upload-row, .card-actions {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 18rpx;
}
.page-head { align-items: flex-start; margin-bottom: 24rpx; }
.head-copy { flex: 1; }
.eyebrow, .page-title, .page-copy, .panel-title, .panel-copy, .label, .icon-name, .description, .source { display: block; }
.eyebrow { color: $app-green; font-size: 17rpx; font-weight: 800; letter-spacing: .12em; }
.page-title { margin-top: 8rpx; font-size: 42rpx; font-weight: 800; }
.page-copy, .panel-copy { margin-top: 7rpx; color: $app-muted; font-size: 20rpx; line-height: 1.5; }
.message { margin-bottom: 18rpx; padding: 16rpx 18rpx; border: 1px solid rgba(57, 214, 210, .3); border-radius: $app-radius-control; background: rgba(57, 214, 210, .07); color: #c9f4f0; }
.message.error { border-color: rgba(217, 104, 107, .42); background: rgba(217, 104, 107, .09); color: $app-danger-text; }
.message-summary, .message-diagnostics text { display: block; }
.message-diagnostics { margin-top: 12rpx; padding-top: 12rpx; border-top: 1px solid rgba(217, 104, 107, .28); color: #d5b9ba; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 17rpx; line-height: 1.55; overflow-wrap: anywhere; }
.create-panel, .edit-panel, .library-panel { margin-top: 20rpx; padding: 26rpx; border: 1px solid $app-border; border-radius: $app-radius-panel; background: $app-surface; }
.edit-panel { border-color: rgba(232, 154, 74, .34); }
.panel-title { font-size: 28rpx; font-weight: 760; }
.source-tabs { display: flex; padding: 4rpx; border: 1px solid $app-border; border-radius: 12rpx; background: #0b1115; }
.source-tabs button { min-height: 58rpx; margin: 0; padding: 0 20rpx; border-radius: 9rpx; background: transparent; color: $app-muted; font-size: 19rpx; }
.source-tabs button.active { background: rgba(57, 214, 210, .12); color: $app-green; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16rpx; }
.field { margin-top: 18rpx; }
.label { margin-bottom: 9rpx; color: #c9d5d8; font-size: 20rpx; font-weight: 680; }
.field input { min-height: 82rpx; padding: 0 18rpx; border: 1px solid $app-border; border-radius: $app-radius-control; background: #0b1115; color: $app-text; }
.upload-row { justify-content: flex-start; margin-top: 20rpx; color: $app-muted; }
.panel-actions { justify-content: flex-end; margin-top: 22rpx; }
.library-head { align-items: flex-start; margin-bottom: 20rpx; }
.icon-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14rpx; }
.icon-card { min-width: 0; padding: 18rpx; border: 1px solid $app-border; border-radius: $app-radius-control; display: grid; grid-template-columns: 76rpx minmax(0, 1fr); gap: 14rpx; background: #0b1115; }
.preview { width: 76rpx; height: 76rpx; border-radius: 14rpx; background: rgba(255, 255, 255, .05); }
.preview-fallback { display: grid; place-items: center; color: $app-muted; font-size: 28rpx; font-weight: 800; }
.identity { min-width: 0; }
.icon-name, .description, .source { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.icon-name { font-size: 22rpx; font-weight: 740; }
.description, .source { margin-top: 5rpx; color: $app-muted; font-size: 17rpx; }
.source { color: #7ea4aa; }
.card-actions { grid-column: 1 / -1; justify-content: flex-end; padding-top: 12rpx; border-top: 1px solid rgba(115, 154, 162, .12); }
.empty-state { min-height: 180rpx; display: grid; place-items: center; color: $app-muted; }
.pagination { justify-content: center; margin-top: 22rpx; color: $app-muted; }
button::after { border: 0; }
button:focus-visible, input:focus-visible { outline: 2px solid $app-focus; outline-offset: 2px; }

@media (max-width: 880px) {
	.icon-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 640px) {
	.icon-shell { width: 100%; padding-top: 20rpx; }
	.page-head, .panel-heading, .library-head { padding: 0 20rpx; flex-direction: column; align-items: stretch; }
	.create-panel, .edit-panel, .library-panel { padding: 22rpx 20rpx; border-left: 0; border-right: 0; border-radius: 0; }
	.form-grid, .icon-grid { grid-template-columns: 1fr; }
	.source-tabs button { flex: 1; }
}

@media (prefers-reduced-motion: reduce) {
	* { transition-duration: .01ms !important; }
}
</style>
