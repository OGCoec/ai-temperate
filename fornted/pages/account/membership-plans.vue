<template>
	<view class="membership-page" :class="{ 'app-readonly': appClient }">
		<view class="membership-shell" :aria-busy="loading || Boolean(purchasingTier)">
			<view class="membership-header">
				<button class="icon-button" type="button" aria-label="返回个人资料" @click="returnToProfile">
					<text aria-hidden="true">←</text>
				</button>
				<view class="membership-heading">
					<text class="membership-kicker">MEMBERSHIP STUDIO</text>
					<text class="membership-title">选择你的套餐</text>
					<text class="membership-subtitle">
						{{ appClient
							? '查看服务端实时价格，购买和升级请在网页版完成。'
							: '服务端实时计算价格，可选择支付宝或微信支付。' }}
					</text>
				</view>
				<view class="simulation-badge">
					<text class="simulation-dot"></text>
					<text>{{ appClient ? '只读报价' : '在线支付' }}</text>
				</view>
			</view>

			<view class="membership-context">
				<view>
					<text class="context-label">当前套餐</text>
					<text class="context-value">{{ currentTierLabel }}</text>
				</view>
				<view class="context-boundary">
					<text class="context-label">支付边界</text>
					<text class="context-detail">
						{{ appClient
							? 'App 客户端不创建订单、不提供支付'
							: '支付成功仅以本项目后端确认状态为准' }}
					</text>
				</view>
			</view>

			<view v-if="h5Client" class="payment-method-block">
				<view>
					<text class="section-eyebrow">PAYMENT METHOD</text>
					<text class="section-title">选择支付方式</text>
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
						:disabled="Boolean(purchasingTier) || paymentProviderLocked"
						@click="payType = method.value"
					>
						<image class="method-logo" :src="method.icon" mode="aspectFit" aria-hidden="true" />
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
						<text class="price-period">{{ appClient ? '最终费用' : '本次支付' }}</text>
					</view>
					<view v-if="appClient || offer.creditAmountYuan !== '0.00'" class="offer-credit">
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
						:loading="h5Client && purchasingTier === offer.targetTier"
						:disabled="purchaseDisabled(offer)"
						@click="handleOfferAction(offer)"
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
				<text>{{ appClient ? '只读报价' : payTypeLabel }}</text>
				<text>
					{{ appClient
						? '价格以服务端最新报价为准。App 客户端仅供查看。'
						: '短时支付入口只用于本次跳转，不保存、不缓存、不写入日志。' }}
				</text>
			</view>
		</view>
	</view>
</template>

<script>
	import { AUTH_ROUTES } from '@/common/auth/config.js'
	import { membershipPaymentApi } from '@/common/user/membership-payment-api.js'
	// #ifdef H5
	import {
		createPaymentIdempotencyKey,
		isUncertainPaymentError,
		submitPaymentCheckout,
		writePaymentReturnContext
	} from '@/common/user/membership-payment-state.js'
	// #endif

	const PAYMENT_PROVIDER = 'LIUHAO'
	const PAY_TYPE_PRESENTATION = Object.freeze({
		alipay: Object.freeze({
			value: 'alipay',
			label: '支付宝渠道一',
			icon: '/static/icons/payment/alipay.svg'
		}),
		wxpay: Object.freeze({
			value: 'wxpay',
			label: '微信支付渠道一',
			icon: '/static/icons/payment/wechat.svg'
		})
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
				checkoutEnabled: false,
				paymentOption: null,
				payType: 'alipay',
				offers: [],
				purchasingTier: '',
				paymentIntents: Object.create(null),
				paymentProviderLocked: false
			}
		},
		computed: {
			h5Client() {
				// #ifdef H5
				return true
				// #endif
				// #ifndef H5
				return false
				// #endif
			},
			appClient() {
				return !this.h5Client
			},
			currentTierLabel() {
				return TIER_LABELS[this.currentTier] || '未设置'
			},
			availablePayTypes() {
				return (this.paymentOption?.payTypes || [])
					.map(value => PAY_TYPE_PRESENTATION[value])
					.filter(Boolean)
			},
			payTypeLabel() {
				return PAY_TYPE_PRESENTATION[this.payType]?.label || '未选择'
			},
			webCheckoutAvailable() {
				return this.checkoutEnabled
					&& Boolean(this.paymentOption)
					&& this.availablePayTypes.length > 0
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
					this.checkoutEnabled = result.checkoutEnabled
					const paymentOption = result.paymentOptions.find(
						option => option.provider === PAYMENT_PROVIDER)
					this.paymentOption = paymentOption
						? { payTypes: [...paymentOption.payTypes] }
						: null
					this.offers = [...result.offers]
					if (!this.paymentOption?.payTypes.includes(this.payType)) {
						this.payType = this.paymentOption?.payTypes[0] || ''
					}
				} catch (error) {
					this.error = this.paymentErrorMessage(error, '套餐报价暂时无法读取。')
				} finally {
					this.loading = false
				}
			},
			purchaseDisabled() {
				if (this.appClient) return !this.webCheckoutAvailable
				return Boolean(this.purchasingTier)
					|| this.paymentProviderLocked
					|| !this.checkoutEnabled
					|| !this.paymentOption
					|| !this.payType
			},
			purchaseLabel(offer) {
				if (this.appClient) {
					return this.webCheckoutAvailable
						? '请前往网页版升级'
						: '网页版升级维护中'
				}
				if (this.purchasingTier === offer.targetTier) return '正在创建支付…'
				if (this.paymentProviderLocked) return '支付结果确认中'
				if (!this.checkoutEnabled) return '支付维护中'
				if (!this.paymentOption) return '当前环境不提供 H5 支付'
				return '立即购买'
			},
			handleOfferAction(offer) {
				if (this.appClient) {
					this.showAppUpgradeNotice(offer)
					return
				}
				// #ifdef H5
				this.purchase(offer)
				// #endif
			},
			showAppUpgradeNotice(offer) {
				uni.showModal({
					title: '请前往网页版升级',
					content: `升级到 ${offer.displayName} 的当前费用为 ¥${offer.payAmountYuan}。\nApp 客户端暂不提供支付，请使用浏览器访问 niko000o.site，登录同一账号后完成升级。`,
					showCancel: false,
					confirmText: '我知道了'
				})
			},
			// #ifdef H5
			async purchase(offer) {
				if (this.purchaseDisabled(offer)) return
				// 本地订单幂等身份只由购买目标和支付渠道组成；切换外部 Provider 必须复用同一订单。
				const intentName = `${offer.targetTier}:${this.payType}`
				const idempotencyKey = this.paymentIntents[intentName]
					|| createPaymentIdempotencyKey()
				this.paymentIntents = {
					...this.paymentIntents,
					[intentName]: idempotencyKey
				}
				this.purchasingTier = offer.targetTier
				this.error = ''
				let order = null
				try {
					order = await membershipPaymentApi.createOrder({
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
					const payment = await this.startPaymentAttempt(order)
					const submission = payment.checkoutSubmission
					writePaymentReturnContext(sessionStorage, order.orderId)
					this.removePaymentIntent(intentName)
					submitPaymentCheckout(submission)
				} catch (error) {
					if (error?.code === 'LIUHAO_CHECKOUT_REPLAY_UNAVAILABLE'
						&& order?.orderId) {
						uni.showModal({
							title: '旧支付入口无法恢复',
							content: '是否关闭这笔旧六号订单？安全关闭后可重新创建支付订单。',
							confirmText: '关闭旧订单',
							success: async result => {
								if (!result.confirm) return
								try {
									await membershipPaymentApi.cancelOrder(order.orderId)
									this.removePaymentIntent(intentName)
									this.error = '旧订单正在关闭，请稍后重新购买。'
								} catch (cancelError) {
									this.error = this.paymentErrorMessage(
										cancelError,
										'旧订单暂时无法关闭。')
								}
							}
						})
					}
					if (!isUncertainPaymentError(error)) this.removePaymentIntent(intentName)
					this.error = this.paymentErrorMessage(error, '支付暂时无法发起。')
				} finally {
					this.purchasingTier = ''
				}
			},
			async startPaymentAttempt(order) {
				const orderId = order.orderId
				// 请求一旦可能到达后端，就不能再切换 Provider；后端 CAS 会继续作为最终并发保护。
				this.paymentProviderLocked = true
				// 外部请求可能已经创建第三方订单，提交描述失效也不能再次调用创建接口。
				const payment = await membershipPaymentApi.startPayment(orderId, PAYMENT_PROVIDER)
				return this.requireLiveSubmission(payment, order)
			},
			requireLiveSubmission(payment, originalOrder) {
				const submission = payment?.checkoutSubmission
				if (!submission || submission.provider !== PAYMENT_PROVIDER) {
					const error = new Error('当前支付渠道不支持 H5 提交。')
					error.code = 'PAYMENT_PROVIDER_UNSUPPORTED'
					throw error
				}
				if (payment.order?.orderId !== originalOrder.orderId
					|| payment.order.payType !== originalOrder.payType
					|| payment.order.payAmountYuan !== originalOrder.payAmountYuan) {
					const error = new Error('支付提交描述与本地订单不一致。')
					error.code = 'PAYMENT_CHECKOUT_SUBMISSION_INVALID'
					throw error
				}
				const expiresAt = Date.parse(submission.submitExpiresAt)
				if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
					const error = new Error('支付提交描述已过期。')
					error.code = `${PAYMENT_PROVIDER}_CHECKOUT_SUBMISSION_EXPIRED`
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
			// #endif
			paymentErrorMessage(error, fallback) {
				const messages = {
					PAYMENT_CHECKOUT_DISABLED: '当前暂停创建新的支付。',
					PAYMENT_PROVIDER_UNSUPPORTED: '当前支付渠道不可用。',
					LIUHAO_TIMEOUT: '六号易支付响应超时，支付结果正在确认中，请勿重复下单。',
					LIUHAO_UNAVAILABLE: '六号易支付请求结果暂时不明确，正在确认中，请勿重复下单。',
					LIUHAO_CLIENT_IP_UNAVAILABLE: '暂时无法确认可信客户端地址，请稍后重试。',
					PAYMENT_CREATE_OUTCOME_UNKNOWN: '支付请求已经发出，第三方结果正在确认中，请勿切换提供方或重复下单。',
					LIUHAO_CREATE_OUTCOME_UNKNOWN: '六号下单结果暂时不明确，请勿重复支付。',
					LIUHAO_CHECKOUT_UNAVAILABLE: '六号已创建订单，但返回的支付入口暂时无法安全打开，请勿重复下单。',
					LIUHAO_CHECKOUT_REPLAY_UNAVAILABLE: '旧支付入口无法安全恢复，请先关闭旧订单后重建。',
					LIUHAO_ORDER_CONFLICT: '六号支付订单状态冲突，请刷新报价后重试。',
					MEMBERSHIP_UPGRADE_HISTORY_MISSING: '缺少可信历史支付周期，暂时无法计算升级价。',
					LIUHAO_CHECKOUT_SUBMISSION_EXPIRED: '六号支付提交描述已过期，请重新发起。',
					PAYMENT_CHECKOUT_SUBMISSION_INVALID: '支付提交描述与订单不一致，已阻止提交。'
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
	.method-logo { width: 22px; height: 22px; flex: 0 0 22px; }
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
	.purchase-button { width: 100%; min-height: 48px; margin: 24px 0 0; display: flex; align-items: center; justify-content: center; box-sizing: border-box; border: 1px solid rgba(55, 211, 154, .38); border-radius: 12px; background: rgba(55, 211, 154, .1); color: #a3e9cc; font-size: 14px; font-weight: 760; text-align: center; }
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
