<template>
	<view class="admin-page-header">
		<view class="header-copy">
			<text v-if="kicker" class="header-kicker">{{ kicker }}</text>
			<text class="header-title">{{ title }}</text>
			<text v-if="description" class="header-description">{{ description }}</text>
			<view v-if="$slots.meta" class="header-meta">
				<slot name="meta" />
			</view>
		</view>
		<view v-if="$slots.actions" class="header-actions">
			<slot name="actions" />
		</view>
	</view>
</template>

<script>
export default {
	name: 'AdminPageHeader',
	props: {
		title: { type: String, required: true },
		description: { type: String, default: '' },
		kicker: { type: String, default: '' }
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.admin-page-header {
	min-height: 132rpx;
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: $app-space-4;
}

.header-copy {
	min-width: 0;
	max-width: 1080rpx;
}

.header-kicker,
.header-title,
.header-description {
	display: block;
}

.header-kicker {
	margin-bottom: 10rpx;
	color: $app-green;
	font-size: $app-font-size-caption;
	font-weight: 720;
}

.header-title {
	color: $app-text;
	font-size: $app-font-size-page;
	font-weight: 790;
	line-height: $app-line-height-tight;
	letter-spacing: -.025em;
	text-wrap: balance;
}

.header-description {
	max-width: 72ch;
	margin-top: 12rpx;
	color: $app-muted;
	font-size: $app-font-size-body;
	line-height: $app-line-height-body;
	text-wrap: pretty;
}

.header-meta {
	margin-top: $app-space-2;
	display: flex;
	flex-wrap: wrap;
	gap: $app-space-1;
}

.header-actions {
	flex: 0 0 auto;
	display: flex;
	flex-wrap: wrap;
	justify-content: flex-end;
	gap: $app-space-2;
}

@media (max-width: 767px) {
	.admin-page-header {
		min-height: auto;
		flex-direction: column;
		gap: $app-space-3;
	}

	.header-title {
		font-size: 44rpx;
	}

	.header-description {
		font-size: 26rpx;
	}

	.header-actions {
		width: 100%;
		justify-content: flex-start;
	}
}
</style>
