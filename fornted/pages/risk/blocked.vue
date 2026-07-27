<template>
	<view class="gate-page" role="main">
		<view class="gate-shell">
			<view class="status-rail" role="alert" aria-live="assertive">
				<view class="status-dot" aria-hidden="true"></view>
				<text class="status-copy">访问已阻断</text>
				<text class="status-context">普通端网络安全</text>
			</view>

			<view class="gate-layout">
				<view class="gate-message">
					<view class="shield-mark" aria-hidden="true">
						<text>!</text>
					</view>
					<text class="gate-label">账号安全保护</text>
					<text class="gate-title">当前访问已暂停</text>
					<text class="gate-copy">
						当前网络环境风险较高，请更换可信网络后重试。
					</text>

					<view class="assurance">
						<view class="assurance-mark" aria-hidden="true">✓</view>
						<text>为保护账号与会话安全，本次操作未继续执行。</text>
					</view>
				</view>

				<view class="recovery-panel" role="note" aria-label="恢复访问说明">
					<text class="recovery-title">恢复访问</text>
					<text class="recovery-intro">
						请先调整当前网络环境，再重新打开应用。
					</text>

					<view class="recovery-list">
						<view class="recovery-row">
							<text class="step-number">1</text>
							<text>切换到家庭网络、移动网络或其他可信网络。</text>
						</view>
						<view class="recovery-row">
							<text class="step-number">2</text>
							<text>关闭不必要的代理、VPN 或异常网络分流。</text>
						</view>
					</view>

					<view class="stopped-state">
						<view class="stopped-mark" aria-hidden="true"></view>
						<view>
							<text class="stopped-title">访问流程已安全停止</text>
							<text class="stopped-copy">
								页面不会自动重试，也不会恢复之前的敏感操作。
							</text>
						</view>
					</view>
				</view>
			</view>
		</view>
	</view>
</template>

<script>
	export default {
		data() {
			return {
				historyLockInstalled: false,
				gateUrl: ''
			}
		},
		onLoad() {
			this.installHistoryLock()
		},
		onUnload() {
			this.removeHistoryLock()
		},
		onBackPress() {
			return true
		},
		methods: {
			installHistoryLock() {
				// #ifdef H5
				if (typeof window === 'undefined' || this.historyLockInstalled) return
				this.gateUrl = window.location.href
				window.history.pushState({ aitRiskBlockGate: true }, '', this.gateUrl)
				window.addEventListener('popstate', this.keepGateActive)
				this.historyLockInstalled = true
				// #endif
			},
			removeHistoryLock() {
				// #ifdef H5
				if (typeof window === 'undefined' || !this.historyLockInstalled) return
				window.removeEventListener('popstate', this.keepGateActive)
				this.historyLockInstalled = false
				// #endif
			},
			keepGateActive() {
				// #ifdef H5
				if (typeof window === 'undefined') return
				window.history.pushState(
					{ aitRiskBlockGate: true },
					'',
					this.gateUrl || window.location.href
				)
				// #endif
			}
		}
	}
</script>

<style scoped>
	.gate-page {
		box-sizing: border-box;
		display: grid;
		min-height: 100vh;
		min-height: 100dvh;
		padding:
			calc(28rpx + env(safe-area-inset-top))
			20rpx
			calc(28rpx + env(safe-area-inset-bottom));
		place-items: center;
		overflow: hidden;
		background:
			radial-gradient(circle at 50% -18%, rgba(239, 165, 35, 0.24), transparent 42%),
			#f1f0ec;
		color: #261d14;
		-webkit-font-smoothing: antialiased;
	}

	.gate-shell {
		width: min(100%, 980px);
		border-radius: 24rpx;
		overflow: hidden;
		background: #fffefb;
		box-shadow: 0 24rpx 64rpx rgba(73, 48, 20, 0.14);
	}

	.status-rail {
		display: flex;
		min-height: 88rpx;
		align-items: center;
		gap: 16rpx;
		padding: 18rpx 28rpx;
		background: #9f4105;
		color: #fff;
	}

	.status-dot {
		width: 14rpx;
		height: 14rpx;
		border: 3rpx solid rgba(255, 255, 255, 0.62);
		border-radius: 50%;
		background: #fff4cc;
	}

	.status-copy,
	.status-context,
	.gate-label,
	.gate-title,
	.gate-copy,
	.assurance text,
	.recovery-title,
	.recovery-intro,
	.recovery-row text,
	.stopped-title,
	.stopped-copy {
		display: block;
	}

	.status-copy {
		font-size: 23rpx;
		font-weight: 760;
	}

	.status-context {
		margin-left: auto;
		color: rgba(255, 255, 255, 0.8);
		font-size: 21rpx;
		font-weight: 620;
	}

	.gate-layout {
		display: grid;
	}

	.gate-message {
		padding: 54rpx 42rpx 48rpx;
		background: #fffefb;
	}

	.shield-mark {
		display: grid;
		width: 106rpx;
		height: 106rpx;
		place-items: center;
		border-radius: 50%;
		background: #fff0ca;
		color: #9f4105;
	}

	.shield-mark text {
		font-size: 48rpx;
		font-weight: 820;
		line-height: 1;
	}

	.gate-label {
		margin-top: 36rpx;
		color: #783007;
		font-size: 24rpx;
		font-weight: 760;
	}

	.gate-title {
		margin-top: 14rpx;
		font-size: 54rpx;
		font-weight: 800;
		line-height: 1.12;
		letter-spacing: -0.025em;
		text-wrap: balance;
	}

	.gate-copy {
		margin-top: 24rpx;
		color: #625548;
		font-size: 28rpx;
		line-height: 1.75;
		text-wrap: pretty;
	}

	.assurance {
		display: flex;
		align-items: flex-start;
		gap: 16rpx;
		margin-top: 42rpx;
		padding-top: 28rpx;
		border-top: 1px solid #ead9ba;
		color: #65481f;
	}

	.assurance-mark {
		display: grid;
		width: 32rpx;
		height: 32rpx;
		flex: 0 0 auto;
		place-items: center;
		border-radius: 50%;
		background: #efa523;
		color: #fff;
		font-size: 20rpx;
		font-weight: 800;
	}

	.assurance text {
		font-size: 23rpx;
		font-weight: 620;
		line-height: 1.6;
	}

	.recovery-panel {
		padding: 44rpx 42rpx 48rpx;
		border-top: 1px solid #ead9ba;
		background: #fff6e5;
	}

	.recovery-title {
		font-size: 34rpx;
		font-weight: 780;
		line-height: 1.25;
	}

	.recovery-intro {
		margin-top: 14rpx;
		color: #625548;
		font-size: 24rpx;
		line-height: 1.65;
	}

	.recovery-list {
		margin-top: 30rpx;
	}

	.recovery-row {
		display: flex;
		align-items: flex-start;
		gap: 18rpx;
		padding: 22rpx 0;
		color: #55483b;
	}

	.recovery-row + .recovery-row {
		border-top: 1px solid #ead9ba;
	}

	.recovery-row text {
		font-size: 24rpx;
		line-height: 1.6;
	}

	.step-number {
		display: grid !important;
		width: 40rpx;
		height: 40rpx;
		flex: 0 0 auto;
		place-items: center;
		border-radius: 50%;
		background: #f3d59d;
		color: #783007;
		font-size: 20rpx !important;
		font-weight: 800;
		line-height: 1 !important;
	}

	.stopped-state {
		display: flex;
		align-items: flex-start;
		gap: 16rpx;
		margin-top: 34rpx;
		padding: 24rpx;
		border-radius: 18rpx;
		background: #f7e4c2;
		color: #5b3b18;
	}

	.stopped-mark {
		width: 14rpx;
		height: 14rpx;
		flex: 0 0 auto;
		margin-top: 9rpx;
		border-radius: 50%;
		background: #c85a08;
	}

	.stopped-title {
		font-size: 24rpx;
		font-weight: 760;
		line-height: 1.4;
	}

	.stopped-copy {
		margin-top: 6rpx;
		color: #6b553b;
		font-size: 21rpx;
		line-height: 1.6;
	}

	@media screen and (min-width: 760px) {
		.gate-page {
			padding: 42px;
		}

		.gate-shell {
			border-radius: 16px;
		}

		.status-rail {
			min-height: 52px;
			padding: 12px 38px;
			gap: 10px;
		}

		.status-dot {
			width: 9px;
			height: 9px;
			border-width: 2px;
		}

		.status-copy,
		.status-context {
			font-size: 13px;
		}

		.gate-layout {
			grid-template-columns: minmax(0, 1.12fr) minmax(320px, 0.88fr);
		}

		.gate-message {
			padding: 62px;
		}

		.shield-mark {
			width: 68px;
			height: 68px;
		}

		.shield-mark text {
			font-size: 30px;
		}

		.gate-label {
			margin-top: 26px;
			font-size: 15px;
		}

		.gate-title {
			margin-top: 10px;
			font-size: 52px;
		}

		.gate-copy {
			margin-top: 18px;
			font-size: 18px;
		}

		.assurance {
			gap: 10px;
			margin-top: 34px;
			padding-top: 20px;
		}

		.assurance-mark {
			width: 20px;
			height: 20px;
			font-size: 12px;
		}

		.assurance text {
			font-size: 14px;
		}

		.recovery-panel {
			padding: 54px 46px;
			border-top: 0;
			border-left: 1px solid #ead9ba;
		}

		.recovery-title {
			font-size: 24px;
		}

		.recovery-intro,
		.recovery-row text {
			font-size: 15px;
		}

		.recovery-list {
			margin-top: 26px;
		}

		.recovery-row {
			gap: 12px;
			padding: 15px 0;
		}

		.step-number {
			width: 26px;
			height: 26px;
			font-size: 12px !important;
		}

		.stopped-state {
			gap: 10px;
			margin-top: 28px;
			padding: 18px;
			border-radius: 12px;
		}

		.stopped-mark {
			width: 9px;
			height: 9px;
			margin-top: 6px;
		}

		.stopped-title {
			font-size: 15px;
		}

		.stopped-copy {
			font-size: 12px;
		}
	}

	@media (prefers-reduced-motion: reduce) {
		*,
		*::before,
		*::after {
			scroll-behavior: auto !important;
			transition-duration: 0.01ms !important;
			animation-duration: 0.01ms !important;
			animation-iteration-count: 1 !important;
		}
	}
</style>
