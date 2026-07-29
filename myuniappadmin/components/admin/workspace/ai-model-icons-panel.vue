<template>
	<view class="workspace-panel">
		<admin-page-header
			kicker="模型资源"
			title="模型图标库"
			description="一个图标资源可被多个模型复用；本地图片存入 OSS，外部图片只登记验证后的 HTTPS 地址。"
		>
		<template #actions>
			<admin-action-button tone="neutral" :loading="loading" :disabled="writing" @click="loadPage">
				刷新
			</admin-action-button>
			<admin-action-button tone="amber" :disabled="writing" @click="openCreateInspector">
				新增图标
			</admin-action-button>
		</template>
		</admin-page-header>
		<view
			class="icon-shell"
			:class="{
				'mobile-inspector-open': mobileInspectorOpen,
				'spring-inspector': inspectorSpringEnabled
			}"
		>
			<view v-if="message" class="feedback-stack">
				<admin-feedback-banner
					:tone="messageError ? 'danger' : 'success'"
					:message="message"
					:dismissible="true"
					@dismiss="setMessage('')"
				/>
				<button
					v-if="messageDiagnostics"
					class="diagnostics-toggle"
					type="button"
					:aria-expanded="diagnosticsOpen ? 'true' : 'false'"
					@click="diagnosticsOpen = !diagnosticsOpen"
				>{{ diagnosticsOpen ? '收起诊断信息' : '查看诊断信息' }}</button>
				<view v-if="messageDiagnostics && diagnosticsOpen" class="message-diagnostics">
					<text v-if="messageDiagnostics.code">错误码：{{ messageDiagnostics.code }}</text>
					<text v-if="messageDiagnostics.exceptionType">异常类型：{{ messageDiagnostics.exceptionType }}</text>
					<text v-if="messageDiagnostics.exceptionMessage">异常信息：{{ messageDiagnostics.exceptionMessage }}</text>
					<text v-if="messageDiagnostics.rootCauseType">根因类型：{{ messageDiagnostics.rootCauseType }}</text>
					<text v-if="messageDiagnostics.rootCauseMessage">根因信息：{{ messageDiagnostics.rootCauseMessage }}</text>
				</view>
			</view>

			<view v-if="!editing" class="create-panel" :style="inspectorMotionStyle">
				<view class="panel-heading">
					<view>
						<text class="panel-title">创建图标资源</text>
						<text class="panel-copy">支持 PNG、JPEG/JPG、WebP、GIF、ICO、AVIF 和安全 SVG，最大 2 MiB。外链不会复制到 OSS。</text>
					</view>
					<button class="mobile-inspector-close" type="button" aria-label="关闭图标编辑器" @click="closeInspector">×</button>
				</view>
				<view class="source-field">
					<text class="label source-label">图标来源</text>
					<picker
						class="source-picker"
						mode="selector"
						:range="createSourceLabels"
						:value="createSourceIndex"
						:disabled="writing"
						aria-label="图标来源"
						@change="changeCreateMode"
					>
						<view class="source-select-control">
							<text>{{ createSourceLabels[createSourceIndex] }}</text>
							<text class="source-select-caret" aria-hidden="true">⌄</text>
						</view>
					</picker>
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

			<view v-if="editing" class="edit-panel" :style="inspectorMotionStyle">
				<view class="panel-heading">
					<view>
						<text class="panel-title">编辑 {{ editing.iconName }}</text>
						<text class="panel-copy">外部 URL 留空时只修改名称和描述；填写后会切换为外链来源。</text>
					</view>
					<button class="mobile-inspector-close" type="button" aria-label="关闭图标编辑器" @click="cancelEdit">×</button>
					<admin-action-button class="desktop-cancel-edit" tone="neutral" size="compact" :disabled="writing" @click="cancelEdit">取消</admin-action-button>
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
							<admin-action-button tone="neutral" size="compact" :disabled="writing" @click="openEditInspector(icon)">编辑</admin-action-button>
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
			<button
				v-if="mobileInspectorOpen"
				class="mobile-inspector-backdrop"
				type="button"
				aria-label="关闭图标编辑器"
				@click="closeInspector"
			/>
		</view>
	</view>
</template>

<script>
import AdminActionButton from '@/components/admin/admin-action-button.vue'
import AdminFeedbackBanner from '@/components/admin/admin-feedback-banner.vue'
import AdminPageHeader from '@/components/admin/admin-page-header.vue'
import {
	adminAiModelIconApi,
	aiModelIconUrlSource
} from '@/common/admin/admin-ai-model-icon-api.js'
import {
	ADMIN_MOTION_PRESETS,
	adminSupportsSpringMotion,
	animateAdminSpring,
	cancelAdminMotion
} from '@/common/admin/admin-motion.js'

const MAX_FILE_BYTES = 2 * 1024 * 1024
const ICON_FILE_EXTENSIONS = ['.png', '.jpg', '.jpeg', '.webp', '.gif', '.ico', '.avif', '.svg']
const CREATE_SOURCE_OPTIONS = [
	{ value: 'UPLOAD', label: '本地上传' },
	{ value: 'REMOTE', label: '外部 URL' }
]

function emptyCreateForm() {
	return { iconName: '', description: '', iconUrl: '', filePath: '', fileName: '' }
}

export default {
	name: 'AiModelIconsPanel',
	components: { AdminActionButton, AdminFeedbackBanner, AdminPageHeader },
	data() {
		return {
			createMode: 'UPLOAD',
			createSourceLabels: CREATE_SOURCE_OPTIONS.map(option => option.label),
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
			diagnosticsOpen: false,
			mobileInspectorOpen: false,
			inspectorProgress: 0,
			inspectorSpringEnabled: adminSupportsSpringMotion(),
			previewFailures: {}
		}
	},
	computed: {
		createSourceIndex() {
			const sourceIndex = CREATE_SOURCE_OPTIONS.findIndex(option => option.value === this.createMode)
			return sourceIndex >= 0 ? sourceIndex : 0
		},
		inspectorMotionStyle() {
			const progress = Math.max(0, Math.min(1, this.inspectorProgress))
			return {
				'--inspector-opacity': String(progress),
				'--inspector-offset': `${(1 - progress) * 104}%`,
				'--inspector-scale': String(.985 + progress * .015)
			}
		}
	},
	watch: {
		mobileInspectorOpen(open) {
			animateAdminSpring({
				owner: this,
				from: this.inspectorProgress,
				to: open ? 1 : 0,
				preset: open ? ADMIN_MOTION_PRESETS.sheet : ADMIN_MOTION_PRESETS.quiet,
				precision: .002,
				onUpdate: value => {
					this.inspectorProgress = Math.max(0, Math.min(1, value))
				}
			})
		}
	},
	beforeDestroy() {
		cancelAdminMotion(this)
	},
	beforeUnmount() {
		cancelAdminMotion(this)
	},
	methods: {
		sourceLabel: aiModelIconUrlSource,
		changeCreateMode(event) {
			const sourceIndex = Number(event?.detail?.value)
			this.createMode = CREATE_SOURCE_OPTIONS[sourceIndex]?.value || CREATE_SOURCE_OPTIONS[0].value
		},
		onWorkspaceActivated() {
			return this.loadPage()
		},
		onWorkspaceDeactivated() {
			this.closeInspector()
			cancelAdminMotion(this)
		},
		beforeWorkspaceLeave() {
			return !this.writing
		},
		hasWorkspaceOverlay() {
			return this.mobileInspectorOpen
		},
		closeWorkspaceOverlay() {
			if (!this.mobileInspectorOpen) return false
			this.closeInspector()
			return true
		},
		openCreateInspector() {
			if (this.writing) return
			this.editing = null
			this.mobileInspectorOpen = true
		},
		openEditInspector(icon) {
			this.startEdit(icon)
			this.mobileInspectorOpen = true
		},
		closeInspector() {
			if (this.writing) return
			this.mobileInspectorOpen = false
			this.editing = null
		},
		setMessage(message, error = false, diagnosticError = null) {
			this.message = message
			this.messageError = error
			this.messageDiagnostics = error ? this.errorDiagnostics(diagnosticError) : null
			this.diagnosticsOpen = false
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
				this.mobileInspectorOpen = false
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
			this.mobileInspectorOpen = false
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
				this.mobileInspectorOpen = false
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

.icon-shell {
	width: 100%;
	margin-top: $app-space-4;
	display: grid;
	grid-template-columns: minmax(0, 1fr) minmax(340px, 420px);
	grid-template-areas:
		"feedback feedback"
		"library inspector";
	gap: $app-space-3;
	align-items: start;
	color: $app-text;
}

.feedback-stack {
	grid-area: feedback;
}

.panel-heading,
.library-head,
.panel-actions,
.pagination,
.upload-row,
.card-actions {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 18rpx;
}

.panel-title,
.panel-copy,
.label,
.icon-name,
.description,
.source,
.message-diagnostics text {
	display: block;
}

.diagnostics-toggle {
	min-height: 60rpx;
	margin: 10rpx 0 0;
	padding: 0 16rpx;
	border: 0;
	border-radius: $app-radius-control;
	background: rgba($app-muted, .08);
	color: $app-muted;
	font-size: $app-font-size-caption;
}

.diagnostics-toggle::after {
	border: 0;
}

.diagnostics-toggle:focus-visible {
	@include admin-focus-ring;
}

.message-diagnostics {
	margin-top: 12rpx;
	padding: 18rpx;
	border-radius: $app-radius-control;
	background: rgba($app-danger, .07);
	color: #e3c4c5;
	font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
	font-size: $app-font-size-caption;
	line-height: 1.6;
	overflow-wrap: anywhere;
}

.create-panel,
.edit-panel,
.library-panel {
	padding: 28rpx;
	@include admin-solid-panel;
}

.create-panel,
.edit-panel {
	position: sticky;
	top: $app-space-4;
	grid-area: inspector;
}

.library-panel {
	grid-area: library;
}

.panel-title { font-size: 28rpx; font-weight: 760; }
.panel-copy { margin-top: 7rpx; color: $app-muted; font-size: 24rpx; line-height: 1.5; }
.source-field { margin-top: 20rpx; display: flex; align-items: center; justify-content: space-between; gap: 18rpx; }
.source-label { margin-bottom: 0; }
.source-picker { flex: 0 0 220rpx; min-width: 0; }
.source-select-control { min-height: 72rpx; padding: 0 18rpx; border: 1px solid $app-border; border-radius: $app-radius-control; box-sizing: border-box; display: flex; align-items: center; justify-content: space-between; gap: 12rpx; background: $app-surface-soft; color: $app-text; font-size: 24rpx; font-weight: 680; }
.source-select-caret { color: $app-muted; font-size: 24rpx; line-height: 1; }
.form-grid { display: grid; grid-template-columns: 1fr; gap: 16rpx; }
.field { margin-top: 18rpx; }
.label { margin-bottom: 9rpx; color: #c9d5d8; font-size: 24rpx; font-weight: 680; }
.field input { min-height: 90rpx; padding: 0 18rpx; border: 1px solid $app-border; border-radius: $app-radius-control; background: $app-surface-soft; color: $app-text; font-size: 26rpx; }
.upload-row { justify-content: flex-start; margin-top: 20rpx; color: $app-muted; }
.panel-actions { justify-content: flex-end; margin-top: 22rpx; }
.library-head { align-items: flex-start; margin-bottom: 20rpx; }
.icon-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 14rpx; }
.icon-card { min-width: 0; padding: 18rpx; border-radius: $app-radius-control; display: grid; grid-template-columns: 76rpx minmax(0, 1fr); gap: 14rpx; background: $app-surface-soft; }
.preview { width: 76rpx; height: 76rpx; border-radius: 14rpx; background: rgba(255, 255, 255, .05); }
.preview-fallback { display: grid; place-items: center; color: $app-muted; font-size: 28rpx; font-weight: 800; }
.identity { min-width: 0; }
.icon-name, .description, .source { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.icon-name { font-size: 26rpx; font-weight: 740; }
.description, .source { margin-top: 5rpx; color: $app-muted; font-size: 24rpx; }
.source { color: #7ea4aa; }
.card-actions { grid-column: 1 / -1; justify-content: flex-end; padding-top: 12rpx; border-top: 1px solid rgba(115, 154, 162, .12); }
.empty-state { min-height: 220rpx; display: grid; place-items: center; color: $app-muted; font-size: $app-font-size-body; text-align: center; }
.pagination { justify-content: center; margin-top: 22rpx; color: $app-muted; font-size: $app-font-size-caption; }
.mobile-inspector-close,
.mobile-inspector-backdrop { display: none; }
button::after { border: 0; }
button:focus-visible, input:focus-visible { @include admin-focus-ring; }
.source-picker:focus-visible .source-select-control { @include admin-focus-ring; }

@media (hover: hover) and (pointer: fine) {
	.icon-card {
		transition: transform $app-motion-micro $app-ease-out, background-color $app-motion-state ease;
	}

	.icon-card:hover {
		transform: translate3d(0, -2rpx, 0);
		background: $app-surface-elevated;
	}

	.diagnostics-toggle:hover {
		background: rgba($app-muted, .14);
		cursor: pointer;
	}

	.source-picker:hover .source-select-control {
		border-color: rgba($app-action-teal, .38);
		background: $app-raised;
		cursor: pointer;
	}
}

@media (max-width: 1023px) {
	.icon-shell {
		grid-template-columns: minmax(0, 1fr) minmax(300px, 360px);
	}
}

@media (max-width: 767px) {
	.icon-shell {
		margin-top: $app-space-3;
		display: block;
	}

	.feedback-stack {
		margin-bottom: $app-space-3;
	}

	.library-panel {
		padding: 24rpx 20rpx;
	}

	.panel-heading,
	.library-head {
		flex-direction: column;
		align-items: stretch;
	}

	.create-panel,
	.edit-panel {
		position: fixed;
		inset: auto 0 0;
		z-index: 82;
		max-height: 86vh;
		padding: 36rpx 24rpx calc(28rpx + env(safe-area-inset-bottom));
		border: 0;
		border-radius: $app-radius-sheet $app-radius-sheet 0 0;
		overflow-y: auto;
		@include admin-glass-chrome(true);
		box-shadow: $app-shadow-sheet;
		opacity: var(--inspector-opacity, 0);
		transform: translate3d(0, var(--inspector-offset, 104%), 0) scale(var(--inspector-scale, .985));
		pointer-events: none;
		transition:
			transform $app-motion-surface $app-ease-out,
			opacity $app-motion-state ease;
	}

	.mobile-inspector-open .create-panel,
	.mobile-inspector-open .edit-panel {
		pointer-events: auto;
	}

	.spring-inspector .create-panel,
	.spring-inspector .edit-panel {
		transition: none;
	}

	.mobile-inspector-backdrop {
		position: fixed;
		inset: 0;
		z-index: 80;
		width: 100%;
		height: 100%;
		margin: 0;
		padding: 0;
		border: 0;
		border-radius: 0;
		display: block;
		background: $app-scrim;
	}

	.mobile-inspector-close {
		position: absolute;
		top: 22rpx;
		right: 22rpx;
		width: 64rpx;
		height: 64rpx;
		min-height: 64rpx;
		margin: 0;
		padding: 0;
		border: 0;
		border-radius: 50%;
		display: flex;
		align-items: center;
		justify-content: center;
		background: rgba($app-muted, .12);
		color: $app-text;
		font-size: 36rpx;
	}

	.desktop-cancel-edit {
		display: none;
	}

	.icon-grid { grid-template-columns: 1fr; }
}

@media (prefers-reduced-motion: reduce) {
	.create-panel,
	.edit-panel,
	.icon-card,
	.diagnostics-toggle {
		transition: opacity 80ms linear, background-color 80ms linear;
		transform: none !important;
	}
}

@media (prefers-reduced-transparency: reduce) {
	.create-panel,
	.edit-panel {
		background: $app-surface-elevated;
		-webkit-backdrop-filter: none;
		backdrop-filter: none;
	}
}

@media (prefers-contrast: more) {
	.create-panel,
	.edit-panel,
	.library-panel {
		border: 2px solid $app-text;
		background: $app-canvas;
	}
}
</style>
