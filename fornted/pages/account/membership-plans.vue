<template>
	<view class="membership-page">
		<view class="membership-shell" :aria-busy="loading || Boolean(purchasingTier)">
			<view class="membership-header">
				<button class="icon-button" type="button" aria-label="返回个人资料" @click="returnToProfile">
					<text aria-hidden="true">←</text>
				</button>
				<view class="membership-heading">
					<text class="membership-kicker">MEMBERSHIP STUDIO</text>
					<text class="membership-title">选择你的套餐</text>
					<text class="membership-subtitle">服务端实时计算价格，一次点击即可安全提交到 BAR 模拟支付页面。</text>
				</view>
				<view class="simulation-badge"><text class="simulation-dot"></text><text>沙箱模拟</text></view>
			</view>

			<view class="membership-context">
				<view>
					<text class="context-label">当前套餐</text>
					<text class="context-value">{{ currentTierLabel }}</text>
				</view>
				<view class="context-boundary">
					<text class="context-label">支付边界</text>
					<text class="context-detail">仅确认模拟订单，不发放会员权益</text>
				</view>
			</view>

			<view class="payment-method-block">
				<view>
					<text class="section-eyebrow">PAYMENT METHOD</text>
					<text class="section-title">选择支付展示方式</text>
				</view>
				<view class="payment-methods" role="tablist" aria-label="支付方式">
					<button
						v-for="method in availablePayTypes"
						:key="method.value"
						class="payment-method"
						:class="{ active: payType === method.value }"
						type="button"
						role="tab"
						:aria-selected="payType === method.value"
						:disabled="Boolean(purchasingTier)"
						@click="payType = method.value"
					>
						<text class="method-mark">{{ method.mark }}</text>
						<text>{{ method.label }}</text>
					</button>
				</view>
			</view>

			<view v-if="error" class="membership-banner" role="alert">
				<view><text class="banner-title">暂时无法继续</text><text class="banner-copy">{{ error }}</text></view>
				<button type="button" :disabled="loading || Boolean(purchasingTier)" @click="loadOffers">重新加载</button>
			</view>

			<view v-if="loading" class="membership-state" role="status">
				<text class="state-orbit"></text>
				<text>正在取得服务端报价…</text>
			</view>

			<view v-else-if="offers.length" class="offer-grid">
				<view
					v-for="(offer, index) in offers"
					:key="offer.targetTier"
					class="offer-card"
					:class="{ featured: offer.targetTier === 'MAX' }"
				>
					<view class="offer-index">0{{ index + 1 }}</view>
					<view class="offer-name-row">
						<text class="offer-name">{{ offer.displayName }}</text>
						<text v-if="offer.transitionType === 'UPGRADE'" class="upgrade-pill">升级价</text>
					</view>
					<text class="offer-description">{{ planDescription(offer.targetTier) }}</text>
					<view class="offer-price">
						<text class="price-currency">¥</text>
						<text class="price-value">{{ offer.payAmountYuan }}</text>
						<text class="price-period">本次模拟订单</text>
					</view>
					<view v-if="offer.creditAmountYuan !== '0.00'" class="offer-credit">
						<text>套餐原价 ¥{{ offer.listPriceYuan }}</text>
						<text>已抵扣 −¥{{ offer.creditAmountYuan }}</text>
					</view>
					<view class="offer-rule"></view>
					<view class="offer-features">
						<text v-for="feature in planFeatures(offer.targetTier)" :key="feature">✓ {{ feature }}</text>
					</view>
					<button
						class="purchase-button"
						type="button"
						:loading="purchasingTier === offer.targetTier"
						:disabled="purchaseDisabled(offer)"
						@click="purchase(offer)"
					>
						{{ purchaseLabel(offer) }}
					</button>
				</view>
			</view>

			<view v-else-if="!error" class="membership-state empty" role="status">
				<text class="empty-title">当前没有可升级套餐</text>
				<text>当前套餐可能已经是个人套餐最高等级，或属于独立管理的团队套餐。</text>
			</view>

			<view class="membership-footnote">
				<text>BAR SANDBOX</text>
				<text>短时签名提交描述只用于本次 Form POST，不保存、不缓存、不写入日志。</text>
			</view>
		</view>
	</view>
</template>

<script>
	import { AUTH_ROUTES } from '@/common/auth/config.js'
	import { membershipPaymentApi } from '@/common/user/membership-payment-api.js'
	import {
		createPaymentIdempotencyKey,
		isUncertainPaymentError,
		submitBarCheckout,
		writePaymentReturnContext
	} from '@/common/user/membership-payment-state.js'

	const PAY_TYPE_PRESENTATION = Object.freeze({
		alipay: Object.freeze({ value: 'alipay', label: '支付宝', mark: '支' }),
		wxpay: Object.freeze({ value: 'wxpay', label: '微信支付', mark: '微' })
	})
	const TIER_LABELS = Object.freeze({
		FREE: 'Free', GO: 'Go', EDU: 'Education', TEAM: 'Team',
		PLUS: 'Plus', PRO: 'Pro', MAX: 'Ultra'
	})

	export default {
		data() {
			return {
				loading: false,
				error: '',
				currentTier: 'FREE',
				provider: '',
				checkoutEnabled: false,
				payTypes: [],
				payType: 'alipay',
				offers: [],
				purchasingTier: '',
				paymentIntents: Object.create(null)
			}
		},
		computed: {
			currentTierLabel() {
				return TIER_LABELS[this.currentTier] || '未设置'
			},
			availablePayTypes() {
				return this.payTypes
					.map(value => PAY_TYPE_PRESENTATION[value])
					.filter(Boolean)
			}
		},
		methods: {
			onAuthenticatedPageReady() {
				if (!this.offers.length && !this.loading) this.loadOffers()
			},
			async loadOffers() {
				if (this.loading || this.purchasingTier) return
				this.loading = true
				this.error = ''
				try {
					const result = await membershipPaymentApi.offers()
					this.currentTier = result.currentTier
					this.provider = result.provider
					this.checkoutEnabled = result.checkoutEnabled
					this.payTypes = [...result.payTypes]
					this.offers = [...result.offers]
					if (!this.payTypes.includes(this.payType)) {
						this.payType = this.payTypes[0] || ''
					}
				} catch (error) {
					this.error = this.paymentErrorMessage(error, '套餐报价暂时无法读取。')
				} finally {
					this.loading = false
				}
			},
			purchaseDisabled() {
				return Boolean(this.purchasingTier)
					|| !this.checkoutEnabled
					|| this.provider !== 'BAR'
					|| !this.payType
			},
			purchaseLabel(offer) {
				if (this.purchasingTier === offer.targetTier) return '正在创建支付…'
				if (!this.checkoutEnabled) return '支付维护中'
				if (this.provider !== 'BAR') return '当前环境不提供 H5 支付'
				return '立即购买'
			},
			async purchase(offer) {
				if (this.purchaseDisabled(offer)) return
				const intentName = `${offer.targetTier}:${this.payType}`
				const idempotencyKey = this.paymentIntents[intentName]
					|| createPaymentIdempotencyKey()
				this.paymentIntents = {
					...this.paymentIntents,
					[intentName]: idempotencyKey
				}
				this.purchasingTier = offer.targetTier
				this.error = ''
				try {
					const order = await membershipPaymentApi.createOrder({
						targetTier: offer.targetTier,
						payType: this.payType,
						idempotencyKey
					})
					if (order.payAmountYuan !== offer.payAmountYuan) {
						this.removePaymentIntent(intentName)
						await this.loadOffersAfterPurchase()
						uni.showModal({
							title: '价格已经变化',
							content: '服务端已刷新套餐价格，请确认新金额后再次点击购买。',
							showCancel: false
						})
						return
					}
					const payment = await this.startBarPaymentAttempt(order)
					const submission = payment.checkoutSubmission
					writePaymentReturnContext(sessionStorage, order.orderId)
					this.removePaymentIntent(intentName)
					submitBarCheckout(submission)
				} catch (error) {
					if (!isUncertainPaymentError(error)) this.removePaymentIntent(intentName)
					this.error = this.paymentErrorMessage(error, '支付暂时无法发起。')
				} finally {
					this.purchasingTier = ''
				}
			},
			async startBarPaymentAttempt(order) {
				const orderId = order.orderId
				let payment
				try {
					payment = await membershipPaymentApi.startPayment(orderId)
					return this.requireLiveBarSubmission(payment, order)
				} catch (error) {
					if (error?.code !== 'BAR_CHECKOUT_SUBMISSION_EXPIRED') throw error
				}
				// 提交前过期只允许为同一订单刷新一次；第二次失败会直接向上抛出，不形成循环重试。
				payment = await membershipPaymentApi.startPayment(orderId)
				return this.requireLiveBarSubmission(payment, order)
			},
			requireLiveBarSubmission(payment, originalOrder) {
				const submission = payment?.checkoutSubmission
				if (!submission || submission.provider !== 'BAR') {
					const error = new Error('当前支付提供方不支持 H5 提交。')
					error.code = 'PAYMENT_PROVIDER_UNSUPPORTED'
					throw error
				}
				if (payment.order?.orderId !== originalOrder.orderId
					|| payment.order.payType !== originalOrder.payType
					|| payment.order.payAmountYuan !== originalOrder.payAmountYuan) {
					const error = new Error('支付提交描述与本地订单不一致。')
					error.code = 'BAR_CHECKOUT_SUBMISSION_INVALID'
					throw error
				}
				const expiresAt = Date.parse(submission.submitExpiresAt)
				if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
					const error = new Error('支付提交描述已过期。')
					error.code = 'BAR_CHECKOUT_SUBMISSION_EXPIRED'
					throw error
				}
				return payment
			},
			async loadOffersAfterPurchase() {
				this.purchasingTier = ''
				await this.loadOffers()
			},
			removePaymentIntent(name) {
				const next = { ...this.paymentIntents }
				delete next[name]
				this.paymentIntents = next
			},
			paymentErrorMessage(error, fallback) {
				const messages = {
					PAYMENT_CHECKOUT_DISABLED: '当前暂停创建新的模拟支付。',
					PAYMENT_PROVIDER_UNSUPPORTED: '当前支付渠道不可用。',
					BAR_TIMEOUT: 'BAR 响应超时，可以安全重试本次购买。',
					BAR_UNAVAILABLE: 'BAR 暂时不可连接，可以稍后重试。',
					BAR_ORDER_CONFLICT: '支付订单状态发生冲突，请刷新报价后重试。',
					MEMBERSHIP_UPGRADE_HISTORY_MISSING: '缺少可信历史支付周期，暂时无法计算升级价。',
					BAR_CHECKOUT_SUBMISSION_EXPIRED: '支付提交描述已过期，请重新发起本次支付。',
					BAR_CHECKOUT_SUBMISSION_INVALID: '支付提交描述与订单不一致，已阻止提交。'
				}
				return messages[error?.code] || error?.message || fallback
			},
			planDescription(tier) {
				return ({
					GO: '轻量体验，适合日常短对话与基础工具。',
					PLUS: '更宽裕的使用空间，覆盖高频个人需求。',
					PRO: '面向重度创作、研究与持续工作流。',
					MAX: '个人套餐最高档，提供最大的模拟额度展示。'
				})[tier] || ''
			},
			planFeatures(tier) {
				return ({
					GO: ['个人基础套餐', '服务端实时定价'],
					PLUS: ['更多模拟额度', '支持升级抵扣'],
					PRO: ['重度使用展示', '支持升级抵扣'],
					MAX: ['个人最高等级', '支持升级抵扣']
				})[tier] || []
			},
			returnToProfile() {
				uni.redirectTo({ url: AUTH_ROUTES.profile })
			}
		}
	}
</script>

<style lang="scss">
	.membership-page { min-height: 100vh; background: #0b0d0c; color: #f2f6f3; }
	.membership-shell { width: min(1180px, calc(100% - 32px)); margin: 0 auto; padding: 42px 0 56px; box-sizing: border-box; }
	.membership-header { display: grid; grid-template-columns: 52px minmax(0, 1fr) auto; align-items: start; gap: 18px; }
	.icon-button { width: 48px; height: 48px; min-height: 48px; margin: 0; padding: 0; border: 1px solid #2d3531; border-radius: 14px; background: #141816; color: #e7eeea; font-size: 25px; line-height: 46px; }
	.icon-button::after, .payment-method::after, .purchase-button::after, .membership-banner button::after { border: 0; }
	.membership-heading { display: flex; min-width: 0; flex-direction: column; }
	.membership-kicker, .section-eyebrow { color: #56d6a2; font-size: 11px; font-weight: 800; letter-spacing: 2.2px; }
	.membership-title { margin-top: 9px; color: #f5f7f6; font-size: clamp(34px, 5vw, 58px); font-weight: 760; line-height: 1.03; letter-spacing: -1.8px; }
	.membership-subtitle { max-width: 640px; margin-top: 13px; color: #98a49e; font-size: 15px; line-height: 1.7; }
	.simulation-badge { display: flex; align-items: center; gap: 8px; padding: 9px 12px; border: 1px solid rgba(86, 214, 162, .25); border-radius: 999px; background: rgba(86, 214, 162, .07); color: #9ce6c7; font-size: 12px; font-weight: 700; }
	.simulation-dot { width: 7px; height: 7px; border-radius: 50%; background: #56d6a2; box-shadow: 0 0 0 4px rgba(86, 214, 162, .11); }
	.membership-context { margin-top: 34px; padding: 18px 20px; display: flex; align-items: center; justify-content: space-between; gap: 24px; border: 1px solid #29312d; border-radius: 16px; background: #111513; }
	.membership-context > view { display: flex; flex-direction: column; }
	.context-boundary { text-align: right; }
	.context-label { color: #79857f; font-size: 11px; font-weight: 750; letter-spacing: 1.2px; text-transform: uppercase; }
	.context-value { margin-top: 4px; color: #f1f5f2; font-size: 21px; font-weight: 750; }
	.context-detail { margin-top: 5px; color: #a8b2ad; font-size: 13px; }
	.payment-method-block { margin-top: 36px; display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; }
	.section-title { display: block; margin-top: 7px; color: #e7ece9; font-size: 19px; font-weight: 700; }
	.payment-methods { display: flex; padding: 4px; gap: 4px; border: 1px solid #2b332f; border-radius: 13px; background: #111513; }
	.payment-method { min-height: 42px; margin: 0; padding: 0 15px; display: flex; align-items: center; gap: 8px; border-radius: 9px; background: transparent; color: #929e98; font-size: 13px; font-weight: 650; }
	.payment-method.active { background: #e8f5ef; color: #102018; }
	.method-mark { width: 22px; height: 22px; border-radius: 7px; background: rgba(55, 211, 154, .14); text-align: center; line-height: 22px; font-size: 11px; font-weight: 800; }
	.payment-method.active .method-mark { background: #37d39a; color: #07130e; }
	.offer-grid { margin-top: 20px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
	.offer-card { position: relative; min-width: 0; padding: 25px; overflow: hidden; border: 1px solid #2b332f; border-radius: 20px; background: linear-gradient(145deg, #151a17 0%, #101311 100%); box-shadow: 0 22px 50px rgba(0, 0, 0, .18); }
	.offer-card.featured { border-color: rgba(86, 214, 162, .4); background: linear-gradient(145deg, #17231d 0%, #101512 70%); }
	.offer-card.featured::before { content: ''; position: absolute; width: 180px; height: 180px; top: -110px; right: -80px; border-radius: 50%; background: rgba(55, 211, 154, .12); filter: blur(4px); }
	.offer-index { color: #53615a; font-size: 11px; font-weight: 800; letter-spacing: 1.5px; }
	.offer-name-row { position: relative; margin-top: 17px; display: flex; align-items: center; justify-content: space-between; gap: 12px; }
	.offer-name { color: #f3f6f4; font-size: 27px; font-weight: 760; letter-spacing: -.5px; }
	.upgrade-pill { padding: 5px 8px; border-radius: 999px; background: rgba(101, 199, 194, .11); color: #89d9d4; font-size: 10px; font-weight: 750; }
	.offer-description { display: block; min-height: 44px; margin-top: 8px; color: #8f9b95; font-size: 13px; line-height: 1.65; }
	.offer-price { margin-top: 24px; display: flex; align-items: baseline; gap: 5px; }
	.price-currency { color: #96a29c; font-size: 18px; font-weight: 700; }
	.price-value { color: #f8faf9; font-size: 45px; font-weight: 780; line-height: 1; letter-spacing: -2px; font-variant-numeric: tabular-nums; }
	.price-period { margin-left: 5px; color: #707b75; font-size: 11px; }
	.offer-credit { min-height: 21px; margin-top: 12px; display: flex; justify-content: space-between; gap: 12px; color: #8cd8b9; font-size: 11px; font-variant-numeric: tabular-nums; }
	.offer-rule { height: 1px; margin: 22px 0 18px; background: #2a312e; }
	.offer-features { min-height: 48px; display: flex; flex-direction: column; gap: 8px; color: #a9b4ae; font-size: 12px; }
	.purchase-button { width: 100%; min-height: 48px; margin: 24px 0 0; border: 1px solid rgba(55, 211, 154, .38); border-radius: 12px; background: rgba(55, 211, 154, .1); color: #a3e9cc; font-size: 14px; font-weight: 760; }
	.featured .purchase-button { border-color: #54d8a1; background: #54d8a1; color: #07120d; }
	.purchase-button:disabled { opacity: .45; }
	.membership-banner { margin-top: 22px; padding: 15px 16px; display: flex; align-items: center; justify-content: space-between; gap: 16px; border: 1px solid rgba(222, 153, 83, .35); border-radius: 14px; background: rgba(222, 153, 83, .08); }
	.membership-banner > view { display: flex; flex-direction: column; }
	.banner-title { color: #f0b879; font-size: 13px; font-weight: 750; }
	.banner-copy { margin-top: 4px; color: #b7a28d; font-size: 12px; line-height: 1.5; }
	.membership-banner button { min-width: 90px; margin: 0; padding: 0 12px; background: #272019; color: #e9b579; font-size: 12px; }
	.membership-state { min-height: 260px; margin-top: 20px; padding: 30px; display: flex; align-items: center; justify-content: center; gap: 12px; border: 1px dashed #303833; border-radius: 18px; color: #8d9993; }
	.membership-state.empty { flex-direction: column; text-align: center; line-height: 1.7; }
	.empty-title { color: #e5ebe7; font-size: 18px; font-weight: 720; }
	.state-orbit { width: 17px; height: 17px; border: 2px solid #334039; border-top-color: #54d8a1; border-radius: 50%; animation: orbit 800ms linear infinite; }
	.membership-footnote { margin-top: 26px; padding-top: 18px; display: flex; justify-content: space-between; gap: 16px; border-top: 1px solid #242b27; color: #66716b; font-size: 10px; letter-spacing: .5px; }
	@keyframes orbit { to { transform: rotate(360deg); } }
	@media (max-width: 720px) {
		.membership-shell { width: min(100% - 24px, 620px); padding-top: 20px; }
		.membership-header { grid-template-columns: 44px minmax(0, 1fr); gap: 12px; }
		.icon-button { width: 42px; height: 42px; min-height: 42px; line-height: 40px; }
		.simulation-badge { grid-column: 2; justify-self: start; }
		.membership-title { font-size: 36px; letter-spacing: -1px; }
		.membership-context, .payment-method-block, .membership-footnote { align-items: stretch; flex-direction: column; }
		.context-boundary { text-align: left; }
		.payment-methods { width: 100%; box-sizing: border-box; }
		.payment-method { flex: 1; justify-content: center; }
		.offer-grid { grid-template-columns: 1fr; }
		.membership-footnote { gap: 7px; }
	}
	@media (prefers-reduced-motion: reduce) { .state-orbit { animation: none; } }
</style>
