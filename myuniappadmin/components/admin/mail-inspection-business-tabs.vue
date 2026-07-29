<template>
	<view class="business-tabs" role="navigation" aria-label="邮箱证据检查业务">
		<button
			v-for="item in items"
			:key="item.value"
			type="button"
			:class="{ active: active === item.value }"
			:aria-current="active === item.value ? 'page' : undefined"
			@click="navigate(item)"
		>
			<text class="tab-label">{{ item.label }}</text>
			<text class="tab-copy">{{ item.copy }}</text>
		</button>
	</view>
</template>

<script>
import { guardedAdminRedirect } from '@/common/admin/admin-route-guard-runtime.js'

const ITEMS = Object.freeze([
	{
		value: 'OPENAI',
		label: 'OpenAI',
		copy: 'ChatGPT 邮件证据',
		url: '/pages/mail-inspection/openai/index'
	},
	{
		value: 'KIRO',
		label: 'Kiro',
		copy: 'Kiro / AWS 邮件证据',
		url: '/pages/mail-inspection/kiro/index'
	},
	{
		value: 'IP2LOCATION',
		label: 'IP2Location',
		copy: '注册与验证链接',
		url: '/pages/mail-inspection/ip2location/index'
	}
])

export default {
	name: 'MailInspectionBusinessTabs',
	props: {
		active: {
			type: String,
			required: true
		}
	},
	data() {
		return { items: ITEMS }
	},
	methods: {
		navigate(item) {
			if (item.value === this.active) return
			return guardedAdminRedirect(item.url)
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.business-tabs {
	margin-top: 22rpx;
	padding: 5rpx;
	border: 1px solid $app-border;
	border-radius: $app-radius-control;
	display: grid;
	grid-template-columns: repeat(3, minmax(0, 1fr));
	gap: 5rpx;
	background: #0b1115;
}

.business-tabs button {
	min-width: 0;
	min-height: 88rpx;
	margin: 0;
	padding: 12rpx 16rpx;
	border: 0;
	border-radius: 9rpx;
	display: flex;
	flex-direction: column;
	align-items: flex-start;
	justify-content: center;
	background: transparent;
	color: $app-muted;
	text-align: left;
	transition: background-color 180ms ease, color 180ms ease, transform 120ms ease-out;
}

.business-tabs button::after {
	border: 0;
}

.business-tabs button.active {
	background: rgba($app-action-teal, .15);
	color: #e4ffff;
}

.business-tabs button:active {
	transform: scale(.985);
}

.tab-label,
.tab-copy {
	max-width: 100%;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}

.tab-label {
	font-size: 26rpx;
	font-weight: 780;
}

.tab-copy {
	margin-top: 5rpx;
	font-size: 24rpx;
	color: $app-muted;
}

button:focus-visible {
	outline: 2px solid $app-focus;
	outline-offset: 2px;
}

@media (max-width: 767px) {
	.business-tabs button {
		align-items: center;
		text-align: center;
	}

	.tab-copy {
		display: none;
	}
}

@media (prefers-reduced-motion: reduce) {
	.business-tabs button {
		transition: none;
	}

	.business-tabs button:active {
		transform: none;
	}
}
</style>
