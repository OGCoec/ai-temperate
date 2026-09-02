<template>
	<view class="payment-result-page">
		<view class="payment-result-shell" :aria-busy="querying">
			<view class="result-brand"><text class="brand-mark">P</text><text>PAYMENT STATUS</text></view>
			<view class="result-card" :class="`is-${viewState.toLowerCase()}`">
				<view class="result-symbol" aria-hidden="true">
					<text v-if="viewState === 'PAID'">✓</text>
					<text v-else-if="viewState === 'TERMINAL'">×</text>
					<text v-else-if="viewState === 'ERROR'">!</text>
					<text v-else class="result-spinner"></text>
				</view>
				<text class="result-eyebrow">{{ eyebrow }}</text>
				<text class="result-title">{{ title }}</text>
				<text class="result-message">{{ message }}</text>
				<view v-if="context" class="result-order">
					<text>本地订单</text>
					<text>{{ maskedOrderId }}</text>
				</view>
				<view class="result-actions">
					<button
						v-if="viewState === 'PROCESSING' || viewState === 'ERROR'"
						class="result-primary"
						type="button"
						:loading="querying"
						:disabled="querying || !context"
						@click="retry"
					>重新查询</button>
					<button class="result-secondary" type="button" @click="returnToProfile">返回账户</button>
				</view>
			</view>
			<text class="result-footnote">浏览器返回不代表支付成功；本页只展示主项目后端确认的订单状态。</text>
		</view>
	</view>
</template>

<script>
	import { AUTH_ROUTES } from '@/common/auth/config.js'
	import { membershipPaymentApi } from '@/common/user/membership-payment-api.js'
	import {
		clearPaymentReturnContext,
		readPaymentReturnContext
	} from '@/common/user/membership-payment-state.js'

	const POLL_INTERVAL_MILLIS = 2000
	const POLL_WINDOW_MILLIS = 30000
	const TERMINAL_MESSAGES = Object.freeze({
		CANCELLED: '本地订单已经取消，没有确认支付成功。',
		CLOSED: '本地订单已经关闭，没有确认支付成功。',
		EXPIRED: '支付订单已经过期。',
		FAILED: '支付订单处理失败。',
		REFUNDED: '支付订单已经标记为退款。'
	})

	export default {
		data() {
			return {
				initialized: false,
				context: null,
				viewState: 'LOCATING',
				orderStatus: '',
				message: '正在读取本地支付上下文…',
				querying: false,
				deadlineAt: 0,
				timer: null
			}
		},
		computed: {
			eyebrow() {
				return ({
					PAID: 'PAYMENT CONFIRMED',
					TERMINAL: 'ORDER CLOSED',
					PROCESSING: 'STILL PROCESSING',
					ERROR: 'STATUS UNAVAILABLE'
				})[this.viewState] || 'VERIFYING ORDER'
			},
			title() {
				return ({
					PAID: '模拟支付已确认',
					TERMINAL: '本次订单未完成',
					PROCESSING: '订单仍在处理中',
					ERROR: '暂时无法确认状态'
				})[this.viewState] || '正在确认支付结果'
			},
			maskedOrderId() {
				const orderId = this.context?.orderId || ''
				return orderId ? `${orderId.slice(0, 6)}…${orderId.slice(-4)}` : ''
			}
		},
		methods: {
			onAuthenticatedPageReady() {
				if (!this.initialized) this.initialize()
			},
			initialize() {
				this.initialized = true
				this.context = readPaymentReturnContext(sessionStorage)
				if (!this.context) {
					this.viewState = 'ERROR'
					this.message = '无法定位本次订单。请返回账户后重新发起支付。'
					return
				}
				this.beginPolling()
			},
			beginPolling() {
				this.stopTimer()
				this.deadlineAt = Date.now() + POLL_WINDOW_MILLIS
				this.viewState = 'LOCATING'
				this.message = '正在向主项目后端确认订单状态…'
				this.queryOrder()
			},
			async queryOrder() {
				if (!this.context || this.querying) return
				this.querying = true
				try {
					const order = await membershipPaymentApi.order(this.context.orderId)
					this.orderStatus = order.status
					if (order.status === 'PAID') {
						this.finish('PAID', '支付已由本项目后端确认')
						return
					}
					if (TERMINAL_MESSAGES[order.status]) {
						this.finish('TERMINAL', TERMINAL_MESSAGES[order.status])
						return
					}
					if (order.status !== 'PENDING_PAYMENT' && order.status !== 'CLOSING') {
						this.finish('ERROR', '后端返回了无法识别的订单状态，未显示支付成功。', false)
						return
					}
					this.viewState = 'LOCATING'
					this.message = order.status === 'CLOSING'
						? '订单正在完成最终核对，请稍候…'
						: '支付回调或主动查询尚未完成，请稍候…'
				} catch (error) {
					this.viewState = 'LOCATING'
					this.message = error?.message || '状态查询暂时失败，正在继续尝试…'
				} finally {
					this.querying = false
				}
				if (Date.now() >= this.deadlineAt) {
					this.viewState = 'PROCESSING'
					this.message = '30 秒内尚未取得终态。订单可能仍在处理，可以稍后重新查询。'
					return
				}
				this.timer = setTimeout(() => this.queryOrder(), POLL_INTERVAL_MILLIS)
			},
			finish(state, message, clear = true) {
				this.stopTimer()
				this.viewState = state
				this.message = message
				if (clear) clearPaymentReturnContext(sessionStorage)
			},
			retry() {
				if (this.context && !this.querying) this.beginPolling()
			},
			stopTimer() {
				if (this.timer) clearTimeout(this.timer)
				this.timer = null
			},
			returnToProfile() {
				this.stopTimer()
				uni.redirectTo({ url: AUTH_ROUTES.profile })
			}
		},
		onUnload() {
			this.stopTimer()
		}
	}
</script>

<style lang="scss">
	.payment-result-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: radial-gradient(circle at 50% 10%, #18251f 0%, #0b0d0c 46%); color: #eef3f0; }
	.payment-result-shell { width: min(520px, calc(100% - 28px)); padding: 32px 0; }
	.result-brand { margin-bottom: 18px; display: flex; align-items: center; justify-content: center; gap: 9px; color: #718078; font-size: 10px; font-weight: 800; letter-spacing: 1.7px; }
	.brand-mark { width: 27px; height: 27px; border: 1px solid #4bd39b; border-radius: 9px; color: #74e0b3; font-size: 14px; line-height: 27px; text-align: center; letter-spacing: 0; }
	.result-card { padding: 42px 38px 34px; display: flex; align-items: center; flex-direction: column; border: 1px solid #29322d; border-radius: 24px; background: rgba(18, 23, 20, .94); box-shadow: 0 30px 80px rgba(0, 0, 0, .28); text-align: center; }
	.result-symbol { width: 70px; height: 70px; display: flex; align-items: center; justify-content: center; border: 1px solid #354039; border-radius: 22px; background: #171d19; color: #6adeae; font-size: 34px; font-weight: 760; }
	.is-terminal .result-symbol, .is-error .result-symbol { color: #dca36e; }
	.result-spinner { width: 24px; height: 24px; border: 2px solid #36423b; border-top-color: #55d9a2; border-radius: 50%; animation: result-spin 800ms linear infinite; }
	.result-eyebrow { margin-top: 27px; color: #5bdba7; font-size: 10px; font-weight: 800; letter-spacing: 2px; }
	.is-terminal .result-eyebrow, .is-error .result-eyebrow { color: #dca36e; }
	.result-title { margin-top: 9px; color: #f4f7f5; font-size: 30px; font-weight: 760; letter-spacing: -.7px; }
	.result-message { max-width: 390px; margin-top: 12px; color: #9aa69f; font-size: 14px; line-height: 1.75; }
	.result-order { width: 100%; margin-top: 26px; padding: 13px 15px; display: flex; justify-content: space-between; box-sizing: border-box; border: 1px solid #28312c; border-radius: 12px; background: #0e1210; color: #728078; font-size: 11px; }
	.result-order text:last-child { color: #aab5af; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
	.result-actions { width: 100%; margin-top: 25px; display: flex; gap: 10px; }
	.result-actions button { min-height: 47px; margin: 0; flex: 1; border-radius: 12px; font-size: 13px; font-weight: 730; }
	.result-actions button::after { border: 0; }
	.result-primary { background: #57dba5; color: #07130e; }
	.result-secondary { border: 1px solid #303a34; background: #171c19; color: #b5c0ba; }
	.result-footnote { display: block; margin: 17px auto 0; max-width: 420px; color: #5f6b64; font-size: 10px; line-height: 1.6; text-align: center; }
	@keyframes result-spin { to { transform: rotate(360deg); } }
	@media (max-width: 520px) {
		.result-card { padding: 34px 22px 27px; border-radius: 20px; }
		.result-title { font-size: 26px; }
		.result-actions { flex-direction: column; }
	}
	@media (prefers-reduced-motion: reduce) { .result-spinner { animation: none; } }
</style>
