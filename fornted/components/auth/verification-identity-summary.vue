<template>
	<view class="verification-identity-summary" role="group" :aria-label="ariaLabel">
		<view v-if="email" class="identity-row">
			<view class="identity-leading" aria-hidden="true">
				<uni-icons type="email" size="20" color="#37d39a" />
			</view>
			<view class="identity-copy">
				<text class="identity-label">邮箱</text>
				<view class="identity-value-line">
					<text class="identity-value" selectable>{{ email }}</text>
					<button
						v-if="showEmailRestart"
						class="identity-restart"
						type="button"
						:disabled="restartDisabled"
						@click="$emit('restart')"
					>
						重新填写
					</button>
				</view>
			</view>
		</view>

		<view v-if="email && phonePresentation" class="identity-divider" />

		<view v-if="phonePresentation" class="identity-row">
			<view class="identity-leading" aria-hidden="true">
				<image
					v-if="hasFlag"
					class="identity-flag"
					:src="phonePresentation.flag"
					mode="aspectFill"
					@error="flagFailed = true"
				/>
				<uni-icons v-else type="map" size="20" color="#65c7c2" />
			</view>
			<view class="identity-copy">
				<text class="identity-label">手机号</text>
				<view class="identity-country">
					<text>{{ phonePresentation.countryName }}</text>
					<text v-if="phonePresentation.countryIso2" class="identity-country-code">
						/ {{ phonePresentation.countryIso2 }}
					</text>
				</view>
				<view class="identity-value-line">
					<text class="identity-value identity-phone" selectable>
						<text class="identity-dial-code">{{ phonePresentation.dialCode }}</text>
						{{ phonePresentation.nationalDisplay }}
					</text>
					<button
						v-if="showPhoneRestart"
						class="identity-restart"
						type="button"
						:disabled="restartDisabled"
						@click="$emit('restart')"
					>
						重新填写
					</button>
				</view>
			</view>
		</view>
	</view>
</template>

<script>
	export default {
		name: 'VerificationIdentitySummary',
		emits: ['restart'],
		props: {
			email: { type: String, default: '' },
			phonePresentation: {
				type: Object,
				default: () => null
			},
			restartDisabled: { type: Boolean, default: false }
		},
		data() {
			return {
				flagFailed: false
			}
		},
		computed: {
			showEmailRestart() {
				return Boolean(this.email && !this.phonePresentation)
			},
			showPhoneRestart() {
				return Boolean(this.phonePresentation)
			},
			ariaLabel() {
				if (this.email && this.phonePresentation) return '本次验证的只读邮箱和手机号'
				if (this.email) return '本次验证的只读邮箱'
				return '本次验证的只读手机号'
			},
			hasFlag() {
				return Boolean(this.phonePresentation?.flag && !this.flagFailed)
			}
		},
		watch: {
			phonePresentation() {
				this.flagFailed = false
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.verification-identity-summary {
		@include user-frosted-surface;
		margin: 0 0 22px;
		border-radius: 14px;
		overflow: hidden;
	}
	.identity-row {
		min-height: 74px;
		padding: 14px 16px;
		display: flex;
		align-items: center;
		gap: 13px;
		box-sizing: border-box;
	}
	.identity-leading {
		width: 38px;
		height: 38px;
		flex-shrink: 0;
		display: flex;
		align-items: center;
		justify-content: center;
		border-radius: 11px;
		background: #1b201d;
		overflow: hidden;
	}
	.identity-flag {
		width: 28px;
		height: 21px;
		border-radius: 3px;
	}
	.identity-copy {
		min-width: 0;
		flex: 1;
		display: flex;
		flex-direction: column;
	}
	.identity-label {
		color: #8b9690;
		font-size: 12px;
		line-height: 1.35;
	}
	.identity-country {
		margin-top: 3px;
		display: flex;
		align-items: baseline;
		gap: 4px;
		color: #cbd3cf;
		font-size: 13px;
		line-height: 1.4;
	}
	.identity-country-code { color: #8b9690; }
	.identity-value-line {
		min-width: 0;
		display: flex;
		align-items: center;
		gap: 10px;
	}
	.identity-value {
		margin-top: 4px;
		min-width: 0;
		flex: 1;
		color: #f3f5f4;
		font-size: 15px;
		line-height: 1.45;
		word-break: break-all;
	}
	.identity-phone { word-break: normal; }
	.identity-dial-code {
		margin-right: 7px;
		color: #65c7c2;
		font-weight: 700;
	}
	.identity-restart {
		min-width: 72px;
		min-height: 44px;
		margin: 0 -8px 0 0;
		padding: 0 8px;
		flex-shrink: 0;
		display: flex;
		align-items: center;
		justify-content: center;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: #a9b5af;
		font-size: 13px;
		font-weight: 600;
		line-height: 1.4;
		box-sizing: border-box;
		touch-action: manipulation;
	}
	.identity-restart::after { border: 0; }
	.identity-restart:active:not([disabled]) {
		background: #1b201d;
		color: #37d39a;
	}
	.identity-restart:focus-visible {
		outline: 2px solid rgba(55, 211, 154, .72);
		outline-offset: 2px;
	}
	.identity-restart[disabled] {
		color: #667069;
		opacity: 1;
	}
	.identity-divider {
		height: 1px;
		margin-left: 67px;
		background: #303733;
	}
	@media (hover: hover) and (pointer: fine) {
		.identity-restart:not([disabled]):hover {
			background: #1b201d;
			color: #37d39a;
		}
	}
</style>
