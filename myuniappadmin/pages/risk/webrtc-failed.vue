<template>
	<view class="admin-gate-page" role="main" :aria-busy="loading">
		<view class="admin-gate-shell">
			<view class="admin-intro">
				<view class="admin-mark" aria-hidden="true">
					<view class="admin-mark-core"></view>
				</view>
				<text class="admin-kicker">NETWORK SECURITY GATE</text>
				<text class="admin-title">管理员网络<br>安全校验</text>
				<text class="admin-copy">
					当前网络未通过 WebRTC 一致性检查。管理员登录与所有受保护操作已暂停。
				</text>
				<text class="security-note">
					单管理员 · 网络出口一致性 · 失败即阻断
				</text>
			</view>

			<view class="security-panel">
				<text class="failure-banner" role="alert" aria-live="assertive">
					{{ mismatch
						? 'WebRTC 公网 IP 与当前访问 IP 不一致'
						: '未获取到 WebRTC 公网 IP' }}
				</text>
				<text class="panel-title">访问已暂停</text>
				<text class="panel-copy">{{ details.message || fallbackMessage }}</text>

				<view class="evidence-panel" aria-label="管理员网络校验证据">
					<text class="evidence-kicker">NETWORK EVIDENCE</text>
					<view class="evidence-row">
						<text class="detail-label">当前 HTTP IP</text>
						<text class="detail-value" selectable>
							{{ details.httpIp || '后端未返回' }}
						</text>
					</view>
					<view class="evidence-row">
						<text class="detail-label">WebRTC 公网 IP</text>
						<view class="address-list">
							<text
								v-for="address in details.webRtcIps"
								:key="address"
								class="detail-value address"
								selectable
							>
								{{ address }}
							</text>
							<text
								v-if="details.webRtcIps.length === 0"
								class="detail-value unavailable"
							>
								未获取到
							</text>
						</view>
					</view>
				</view>

				<view class="guidance" role="note">
					<text>请检查 VPN、代理分流或 UDP 路由。</text>
					<text>确保浏览器与 HTTP 请求使用同一公网出口后重新检测。</text>
				</view>

				<text v-if="errorMessage" class="inline-error" role="alert">
					{{ errorMessage }}
				</text>
				<button
					class="retry-button"
					:disabled="loading"
					aria-label="重新检测管理员 WebRTC 网络一致性"
					@click="retry"
				>
					{{ loading ? '正在检测…' : '重新检测' }}
				</button>
				<text class="safe-exit-copy">检测通过后返回管理员登录入口</text>
			</view>
		</view>
	</view>
</template>

<script>
	import { ensureAdminPreAuth } from '@/common/admin/admin-pre-auth.js'
	import {
		presentAdminRiskBlock
	} from '@/common/admin/admin-risk-block-navigation.js'
	import {
		currentAdminWebRtcFailure,
		ensureAdminWebRtcVerified,
		refreshAdminWebRtcFailure
	} from '@/common/admin/admin-webrtc-verification.js'

	const ADMIN_SAFE_ENTRY = '/pages/index/index'
	const FAILURE_CODES = new Set([
		'WEBRTC_IP_MISMATCH',
		'WEBRTC_VERIFICATION_FAILED'
	])

	export default {
		data() {
			return {
				loading: true,
				errorMessage: '',
				exitAllowed: false,
				historyLockInstalled: false,
				gateUrl: '',
				details: {
					code: 'WEBRTC_VERIFICATION_FAILED',
					message: '',
					httpIp: '',
					webRtcIps: [],
					retryable: true
				}
			}
		},
		computed: {
			mismatch() {
				return this.details.code === 'WEBRTC_IP_MISMATCH'
			},
			fallbackMessage() {
				return this.mismatch
					? '当前 HTTP IP 不在 WebRTC 公网候选集合中。'
					: '当前环境没有返回可用于一致性校验的公网 WebRTC IP。'
			}
		},
		onLoad() {
			this.installHistoryLock()
			const cached = currentAdminWebRtcFailure()
			if (cached) this.details = this.mergeDetails(this.details, cached)
			this.loadDetails()
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
				window.history.pushState({ aitAdminWebRtcGate: true }, '', this.gateUrl)
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
				if (this.exitAllowed || typeof window === 'undefined') return
				window.history.pushState(
					{ aitAdminWebRtcGate: true },
					'',
					this.gateUrl || window.location.href
				)
				// #endif
			},
			leaveGate() {
				this.exitAllowed = true
				this.removeHistoryLock()
				uni.reLaunch({ url: ADMIN_SAFE_ENTRY })
			},
			async loadDetails() {
				this.loading = true
				this.errorMessage = ''
				try {
					await ensureAdminPreAuth()
					const fresh = await refreshAdminWebRtcFailure()
					if (!fresh) {
						this.leaveGate()
						return
					}
					this.details = this.mergeDetails(this.details, fresh)
				} catch (error) {
					if (presentAdminRiskBlock(error)) return
					const failure = currentAdminWebRtcFailure()
					if (failure) {
						this.details = this.mergeDetails(this.details, failure)
					} else if (FAILURE_CODES.has(error?.code)) {
						this.details = this.mergeDetails(this.details, this.fromError(error))
					}
					this.errorMessage = error?.message || '校验详情加载失败，请重新检测。'
				} finally {
					this.loading = false
				}
			},
			async retry() {
				if (this.loading) return
				this.loading = true
				this.errorMessage = ''
				try {
					await ensureAdminPreAuth()
					await ensureAdminWebRtcVerified({ force: true })
					this.leaveGate()
				} catch (error) {
					if (presentAdminRiskBlock(error)) return
					if (FAILURE_CODES.has(error?.code)) {
						this.details = this.mergeDetails(this.details, this.fromError(error))
					}
					this.errorMessage = error?.message || '重新检测失败，请检查网络后重试。'
				} finally {
					this.loading = false
				}
			},
			mergeDetails(current, incoming) {
				const next = incoming || {}
				const incomingIps = Array.isArray(next.webRtcIps) ? next.webRtcIps : []
				const preserveMismatchIps = next.code === 'WEBRTC_IP_MISMATCH'
					&& incomingIps.length === 0
				return {
					code: next.code || current.code || 'WEBRTC_VERIFICATION_FAILED',
					message: next.message || current.message || '',
					httpIp: next.httpIp || current.httpIp || '',
					webRtcIps: preserveMismatchIps
						? [...(current.webRtcIps || [])]
						: [...incomingIps],
					retryable: next.retryable !== false
				}
			},
			fromError(error) {
				return {
					code: error?.code || 'WEBRTC_VERIFICATION_FAILED',
					message: error?.message || '',
					httpIp: error?.httpIp || '',
					webRtcIps: Array.isArray(error?.webRtcIps) ? [...error.webRtcIps] : [],
					retryable: error?.retryable !== false
				}
			}
		}
	}
</script>

<style scoped>
	.admin-gate-page {
		box-sizing: border-box;
		min-height: 100vh;
		padding:
			calc(44rpx + env(safe-area-inset-top))
			28rpx
			calc(44rpx + env(safe-area-inset-bottom));
		color: #f3f8f8;
		background:
			radial-gradient(circle at 12% 16%, rgba(57, 214, 210, 0.17), transparent 28%),
			linear-gradient(135deg, #080b0d 0%, #0e1519 54%, #07090b 100%);
		-webkit-font-smoothing: antialiased;
	}

	.admin-gate-shell {
		display: grid;
		width: 100%;
		max-width: 1180px;
		min-height: calc(100vh - 88rpx);
		margin: 0 auto;
		gap: 44rpx;
		align-content: center;
	}

	.admin-intro {
		padding: 10rpx 4rpx;
	}

	.admin-mark {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 76rpx;
		height: 76rpx;
		border: 1px solid rgba(105, 212, 226, 0.36);
		border-radius: 18rpx;
		background: rgba(20, 29, 34, 0.78);
	}

	.admin-mark-core {
		width: 28rpx;
		height: 28rpx;
		border-radius: 50%;
		background: #39d6d2;
		box-shadow: 0 0 24rpx rgba(57, 214, 210, 0.48);
	}

	.admin-kicker,
	.admin-title,
	.admin-copy,
	.security-note,
	.failure-banner,
	.panel-title,
	.panel-copy,
	.evidence-kicker,
	.detail-label,
	.detail-value,
	.guidance text,
	.inline-error,
	.safe-exit-copy {
		display: block;
	}

	.admin-kicker {
		margin-top: 28rpx;
		color: #69d4e2;
		font-size: 21rpx;
		font-weight: 760;
		letter-spacing: 0.15em;
	}

	.admin-title {
		margin-top: 16rpx;
		font-size: 54rpx;
		font-weight: 780;
		line-height: 1.08;
		text-wrap: balance;
	}

	.admin-copy {
		max-width: 640rpx;
		margin-top: 22rpx;
		color: #a8b8bd;
		font-size: 27rpx;
		line-height: 1.6;
		text-wrap: pretty;
	}

	.security-note {
		margin-top: 28rpx;
		color: #c6d2d5;
		font-size: 23rpx;
	}

	.security-panel {
		padding: 34rpx 30rpx;
		border: 1px solid rgba(105, 212, 226, 0.22);
		border-radius: 18rpx;
		background: rgba(16, 22, 26, 0.88);
		backdrop-filter: blur(18px);
		box-shadow: 0 20rpx 60rpx rgba(0, 0, 0, 0.3);
	}

	.failure-banner {
		padding: 18rpx 20rpx;
		border: 1px solid rgba(232, 98, 98, 0.22);
		border-radius: 10rpx;
		background: rgba(232, 98, 98, 0.1);
		color: #ffb8b8;
		font-size: 23rpx;
		font-weight: 700;
		line-height: 1.5;
	}

	.panel-title {
		margin-top: 28rpx;
		font-size: 36rpx;
		font-weight: 760;
		line-height: 1.2;
	}

	.panel-copy {
		margin-top: 12rpx;
		color: #91a2a8;
		font-size: 24rpx;
		line-height: 1.6;
	}

	.evidence-panel {
		margin-top: 28rpx;
		border: 1px solid rgba(105, 212, 226, 0.18);
		border-radius: 14rpx;
		background: rgba(7, 13, 16, 0.54);
		overflow: hidden;
	}

	.evidence-kicker {
		padding: 16rpx 20rpx;
		background: rgba(57, 214, 210, 0.07);
		color: #69d4e2;
		font-size: 18rpx;
		font-weight: 800;
		letter-spacing: 0.14em;
	}

	.evidence-row {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: 24rpx;
		padding: 22rpx 20rpx;
		border-top: 1px solid rgba(145, 162, 168, 0.14);
	}

	.detail-label {
		flex: 0 0 auto;
		color: #91a2a8;
		font-size: 21rpx;
	}

	.address-list {
		min-width: 0;
		text-align: right;
	}

	.detail-value {
		color: #f3f8f8;
		font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
		font-size: 23rpx;
		font-variant-numeric: tabular-nums;
		overflow-wrap: anywhere;
	}

	.detail-value.address + .detail-value.address {
		margin-top: 10rpx;
	}

	.detail-value.unavailable {
		color: #ffb8b8;
		font-family: inherit;
		font-weight: 700;
	}

	.guidance {
		margin-top: 24rpx;
		padding: 20rpx 22rpx;
		border: 1px solid rgba(105, 212, 226, 0.18);
		border-radius: 12rpx;
		background: rgba(57, 214, 210, 0.06);
	}

	.guidance text {
		color: #b9c7cc;
		font-size: 23rpx;
		line-height: 1.65;
	}

	.guidance text + text {
		margin-top: 8rpx;
	}

	.inline-error {
		margin-top: 22rpx;
		color: #ffb8b8;
		font-size: 23rpx;
		line-height: 1.55;
	}

	.retry-button {
		min-height: 88rpx;
		margin-top: 30rpx;
		border: 1px solid rgba(105, 212, 226, 0.34);
		border-radius: 12rpx;
		background: linear-gradient(135deg, #39d6d2, #69d4e2);
		color: #071012;
		font-size: 27rpx;
		font-weight: 780;
		transition: transform 140ms cubic-bezier(0.23, 1, 0.32, 1), opacity 140ms;
	}

	.retry-button:active {
		transform: scale(0.98);
	}

	.retry-button[disabled] {
		opacity: 0.58;
	}

	.safe-exit-copy {
		margin-top: 18rpx;
		color: #718289;
		font-size: 20rpx;
		text-align: center;
	}

	@media screen and (min-width: 760px) {
		.admin-gate-page {
			padding: 56px 42px;
		}

		.admin-gate-shell {
			min-height: calc(100vh - 112px);
			grid-template-columns: minmax(300px, 1fr) minmax(400px, 500px);
			align-items: center;
			gap: 60px;
		}

		.admin-title {
			font-size: 44px;
		}

		.security-panel {
			padding: 36px 34px;
		}
	}

	@media (prefers-reduced-motion: reduce) {
		.retry-button {
			transition: opacity 140ms;
		}
	}
</style>
