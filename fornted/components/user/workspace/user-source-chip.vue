<template>
	<button
		v-if="displayDomain"
		class="user-source-chip"
		:class="[`is-${variant}`, { 'is-disabled': inactive }]"
		type="button"
		:aria-label="accessibleLabel"
		:aria-disabled="String(inactive)"
		@click.stop="activate"
	>
		<image
			v-if="faviconUrl"
			class="user-source-chip-favicon"
			:src="faviconUrl"
			mode="aspectFit"
			aria-hidden="true"
			@error="handleFaviconError"
		/>
		<image
			v-else
			class="user-source-chip-fallback"
			src="/static/icons/source-globe.svg"
			mode="aspectFit"
			aria-hidden="true"
		/>
		<view v-if="variant === 'card'" class="user-source-chip-card-copy">
			<text class="user-source-chip-title">{{ cardTitle }}</text>
			<text class="user-source-chip-domain">{{ displayDomain }}</text>
		</view>
		<text v-else class="user-source-chip-domain">{{ displayDomain }}</text>
	</button>
</template>

<script>
	import { normalizeAiConversationSource } from '@/common/aichat/ai-conversation-source-presentation.js'
	import {
		buildAiSourceFaviconUrl,
		normalizeAiSourceFaviconDomain
	} from '@/common/aichat/ai-source-favicon.js'
	import { openExternalHttpUrl } from '@/common/platform/external-url-opener.js'

	const SOURCE_VARIANTS = new Set(['inline', 'activity', 'card'])

	export default {
		name: 'UserSourceChip',
		props: {
			source: { type: Object, default: null },
			domain: { type: String, default: '' },
			variant: {
				type: String,
				default: 'inline',
				validator: value => SOURCE_VARIANTS.has(value)
			},
			disabled: { type: Boolean, default: false }
		},
		data() {
			return { faviconFailed: false }
		},
		computed: {
			normalizedSource() {
				return normalizeAiConversationSource(this.source)
			},
			displayDomain() {
				return this.normalizedSource?.domain
					|| normalizeAiSourceFaviconDomain(this.domain)
			},
			linkUrl() {
				return this.normalizedSource?.url || ''
			},
			inactive() {
				return this.disabled || !this.linkUrl
			},
			faviconUrl() {
				return this.faviconFailed ? ''
					: buildAiSourceFaviconUrl(this.displayDomain)
			},
			cardTitle() {
				return this.normalizedSource?.title || this.displayDomain
			},
			accessibleLabel() {
				if (this.inactive) {
					return `来源 ${this.displayDomain}，完整地址尚未返回`
				}
				return `打开来源 ${this.cardTitle}，${this.displayDomain}`
			}
		},
		watch: {
			displayDomain() {
				this.faviconFailed = false
			}
		},
		methods: {
			activate() {
				if (this.inactive) return
				openExternalHttpUrl(this.linkUrl)
			},
			handleFaviconError() {
				this.faviconFailed = true
			}
		}
	}
</script>

<style lang="scss">
	.user-source-chip { min-width: 0; min-height: 30px; margin: 0; padding: 0 9px; box-sizing: border-box; display: inline-flex; align-items: center; justify-content: flex-start; gap: 6px; border: 1px solid rgba(151, 170, 160, .24); border-radius: 999px; background: rgba(26, 30, 27, .86); color: #bfe9d6; font-size: 11px; line-height: normal; vertical-align: middle; cursor: pointer; }
	.user-source-chip::after { border: 0; }
	.user-source-chip:focus-visible { outline: 2px solid #8fe8c4; outline-offset: 2px; }
	.user-source-chip:not(.is-disabled):active { background: rgba(55, 211, 154, .16); }
	.user-source-chip.is-disabled { border-style: dashed; color: #91a49b; cursor: default; opacity: .78; }
	.user-source-chip.is-card { width: 100%; min-height: 52px; padding: 7px 10px; border-radius: 10px; color: #d5ded9; text-align: left; }
	.user-source-chip-favicon, .user-source-chip-fallback { width: 14px; height: 14px; flex: 0 0 14px; }
	.user-source-chip-favicon { border-radius: 3px; }
	.user-source-chip.is-card .user-source-chip-favicon, .user-source-chip.is-card .user-source-chip-fallback { width: 17px; height: 17px; flex-basis: 17px; }
	.user-source-chip-card-copy { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 4px; }
	.user-source-chip-title, .user-source-chip-domain { min-width: 0; display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; line-height: 1.35; }
	.user-source-chip-title { color: #d5ded9; font-size: 12px; }
	.user-source-chip-domain { color: inherit; font-size: 11px; }
	.user-source-chip.is-card .user-source-chip-domain { color: #8b9690; font-size: 11px; }
</style>
