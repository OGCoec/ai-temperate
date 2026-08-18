<template>
	<view class="gate-page" role="main" :aria-busy="loading">
		<view class="gate-card">
			<view class="status-header" role="alert" aria-live="assertive">
				<view class="status-mark" :class="{ leak: mismatch }" aria-hidden="true">
					{{ mismatch ? '!' : '…' }}
				</view>
				<view class="status-heading">
					<text class="eyebrow">NETWORK CONSISTENCY</text>
					<text class="status-copy">登录保护已暂停</text>
				</view>
			</view>

			<text class="title">
				{{ mismatch ? '检测到 WebRTC IP 泄漏' : '当前网络无法继续登录' }}
			</text>
			<text class="summary">{{ details.message || fallbackMessage }}</text>

			<view class="evidence-panel" aria-label="网络校验证据">
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
				<text>请确认浏览器允许 WebRTC，UDP 未被禁用。</text>
				<text>如正在使用 VPN 或代理，请结束当前会话，并在统一网络出口下重新建立安全会话。</text>
			</view>

			<text v-if="errorMessage" class="inline-error" role="alert">
				{{ errorMessage }}
			</text>
			<text class="safe-exit-copy">当前会话不允许再次开启临时放行窗口</text>
		</view>
	</view>
</template>

<script>
	import { AUTH_ROUTES } from '@/common/auth/config.js'
	import { ensurePreAuth } from '@/common/auth/pre-auth.js'
	import { presentRiskBlock } from '@/common/auth/risk-block-navigation.js'
	import {
		currentWebRtcFailure,
		refreshWebRtcFailure
	} from '@/common/auth/webrtc-verification.js'

	const FAILURE_CODES = new Set([
		'WEBRTC_IP_MISMATCH',
		'WEBRTC_IP_FAMILY_INCOMPLETE',
		'WEBRTC_VERIFICATION_FAILED',
		'WEBRTC_VERIFICATION_TIMEOUT'
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
					retryable: false
				}
			}
		},
		computed: {
			mismatch() {
				return this.details.code === 'WEBRTC_IP_MISMATCH'
			},
			familyIncomplete() {
				return this.details.code === 'WEBRTC_IP_FAMILY_INCOMPLETE'
			},
			fallbackMessage() {
				if (this.mismatch) return '当前 HTTP IP 不在 WebRTC 公网候选集合中。'
				if (this.familyIncomplete) {
					return '未获取到与当前 HTTP 连接同协议族的 WebRTC 公网候选，当前会话已停止访问。'
				}
				return '未获取到可用于一致性校验的公网 WebRTC IP。'
			}
		},
		onLoad() {
			this.installHistoryLock()
			const cached = currentWebRtcFailure()
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
				window.history.pushState({ aitWebRtcGate: true }, '', this.gateUrl)
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
					{ aitWebRtcGate: true },
					'',
					this.gateUrl || window.location.href
				)
				// #endif
			},
			leaveGate() {
				this.exitAllowed = true
				this.removeHistoryLock()
				uni.reLaunch({ url: AUTH_ROUTES.sessionGate })
			},
			async loadDetails() {
				this.loading = true
				this.errorMessage = ''
				try {
					await ensurePreAuth()
					const fresh = await refreshWebRtcFailure()
					if (!fresh) {
						this.leaveGate()
						return
					}
					this.details = this.mergeDetails(this.details, fresh)
				} catch (error) {
					if (presentRiskBlock(error)) return
					const failure = currentWebRtcFailure()
					if (failure) {
						this.details = this.mergeDetails(this.details, failure)
					} else if (FAILURE_CODES.has(error?.code)) {
						this.details = this.mergeDetails(this.details, this.fromError(error))
					}
					this.errorMessage = error?.message || '校验详情加载失败。'
				} finally {
					this.loading = false
				}
			},
			mergeDetails(current, incoming) {
				const next = incoming || {}
				const incomingIps = Array.isArray(next.webRtcIps) ? next.webRtcIps : []
				const preserveFailureIps = [
					'WEBRTC_IP_MISMATCH',
					'WEBRTC_IP_FAMILY_INCOMPLETE'
				].includes(next.code)
					&& incomingIps.length === 0
				return {
					code: next.code || current.code || 'WEBRTC_VERIFICATION_FAILED',
					message: next.message || current.message || '',
					httpIp: next.httpIp || current.httpIp || '',
					webRtcIps: preserveFailureIps
						? [...(current.webRtcIps || [])]
						: [...incomingIps],
					retryable: false
				}
			},
			fromError(error) {
				return {
					code: error?.code || 'WEBRTC_VERIFICATION_FAILED',
					message: error?.message || '',
					httpIp: error?.httpIp || '',
					webRtcIps: Array.isArray(error?.webRtcIps) ? [...error.webRtcIps] : [],
					retryable: false
				}
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.gate-page {
		@include user-safe-viewport;
		box-sizing: border-box;
		padding:
			calc(56rpx + env(safe-area-inset-top))
			28rpx
			calc(56rpx + env(safe-area-inset-bottom));
		background: #080c0a;
		color: #eff8f3;
		-webkit-font-smoothing: antialiased;
	}

	.gate-card {
		@include user-frosted-surface;
		box-sizing: border-box;
		width: min(100%, 720rpx);
		margin: 0 auto;
		padding: 44rpx 36rpx;
		border-radius: 28rpx;
	}

	.status-header {
		display: flex;
		align-items: center;
		gap: 20rpx;
	}

	.status-mark {
		display: grid;
		flex: 0 0 auto;
		place-items: center;
		width: 76rpx;
		height: 76rpx;
		border-radius: 22rpx;
		background: rgba(245, 176, 65, 0.14);
		color: #f5b041;
		font-size: 38rpx;
		font-weight: 800;
	}

	.status-mark.leak {
		background: rgba(255, 100, 96, 0.14);
		color: #ff7d78;
	}

	.eyebrow,
	.status-copy,
	.title,
	.summary,
	.detail-label,
	.detail-value,
	.guidance text,
	.inline-error,
	.safe-exit-copy {
		display: block;
	}

	.eyebrow {
		color: #6fddb1;
		font-size: 20rpx;
		font-weight: 800;
		letter-spacing: 0.18em;
	}

	.status-copy {
		margin-top: 8rpx;
		color: #84958c;
		font-size: 22rpx;
	}

	.title {
		margin-top: 42rpx;
		font-size: 44rpx;
		font-weight: 780;
		line-height: 1.25;
		text-wrap: balance;
	}

	.summary {
		margin-top: 18rpx;
		color: #afbeb6;
		font-size: 27rpx;
		line-height: 1.7;
		text-wrap: pretty;
	}

	.evidence-panel {
		@include user-frosted-surface;
		margin-top: 34rpx;
		padding: 0 24rpx;
		border-radius: 18rpx;
	}

	.evidence-row {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: 24rpx;
		padding: 24rpx 0;
	}

	.evidence-row + .evidence-row {
		border-top: 1px solid #24322c;
	}

	.detail-label {
		flex: 0 0 auto;
		color: #819188;
		font-size: 22rpx;
	}

	.address-list {
		min-width: 0;
		text-align: right;
	}

	.detail-value {
		color: #eff8f3;
		font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
		font-size: 24rpx;
		font-variant-numeric: tabular-nums;
		overflow-wrap: anywhere;
	}

	.detail-value.address + .detail-value.address {
		margin-top: 10rpx;
	}

	.detail-value.unavailable {
		color: #f5b041;
		font-family: inherit;
		font-weight: 700;
	}

	.guidance {
		margin-top: 24rpx;
		padding: 22rpx 24rpx;
		border-left: 6rpx solid #37d39a;
		border-radius: 8rpx 18rpx 18rpx 8rpx;
		background: rgba(55, 211, 154, 0.07);
	}

	.guidance text {
		color: #b9c8c0;
		font-size: 24rpx;
		line-height: 1.7;
	}

	.guidance text + text {
		margin-top: 8rpx;
	}

	.inline-error {
		margin-top: 24rpx;
		color: #ff8d88;
		font-size: 24rpx;
		line-height: 1.55;
	}

	.safe-exit-copy {
		margin-top: 18rpx;
		color: #64756c;
		font-size: 21rpx;
		text-align: center;
	}

</style>
