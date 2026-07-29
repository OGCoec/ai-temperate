<template>
	<view class="gate-page" :class="{ 'appearance-admin-quiet': appearance === 'ADMIN_QUIET' }">
		<view class="gate-panel" role="main" aria-labelledby="challenge-failed-title">
			<view class="gate-status" role="alert" aria-live="assertive">
				<view class="status-label">
					<view class="status-mark" aria-hidden="true"></view>
					<text>{{ copy.status }}</text>
				</view>
				<view
					id="challenge-failed-title"
					ref="title"
					class="gate-title"
					role="heading"
					aria-level="1"
					tabindex="-1"
				>
					{{ copy.title }}
				</view>
				<text class="gate-description">{{ description }}</text>
				<text class="gate-security">{{ copy.security }}</text>
			</view>

			<view class="gate-recovery" aria-labelledby="challenge-recovery-title">
				<view
					id="challenge-recovery-title"
					class="recovery-title"
					role="heading"
					aria-level="2"
				>
					重新开始前
				</view>
				<text class="recovery-hint">{{ copy.hint }}</text>
				<button
					class="retry-button"
					type="button"
					:disabled="busy"
					:aria-busy="busy ? 'true' : 'false'"
					@click="requestRetry"
				>
					{{ busy ? '正在重新检查…' : '重新检查' }}
				</button>
			</view>
		</view>
	</view>
</template>

<script>
	const COPY = Object.freeze({
		USER: Object.freeze({
			status: '安全验证未完成',
			title: '需要重新检查本次访问',
			description: '连续三次验证后，系统仍无法确认本次访问可以继续。请先确认网络连接稳定，再重新检查。',
			security: '登录请求和其他敏感操作均未继续执行。',
			hint: '确认网络连接稳定；如正在使用代理、VPN 或网络分流，请确认配置可信。'
		}),
		ADMIN: Object.freeze({
			status: '管理员安全验证未完成',
			title: '需要重新检查管理员访问',
			description: '连续三次验证后，系统仍无法确认管理员访问可以继续。请先确认受信网络和代理配置，再重新检查。',
			security: '管理员登录和其他受保护操作均未继续执行。',
			hint: '确认正在使用受信网络，并检查代理、VPN 与网络分流配置。'
		})
	})

	export default {
		name: 'RiskChallengeFailedGate',
		props: {
			audience: {
				type: String,
				default: 'USER',
				validator: value => value === 'USER' || value === 'ADMIN'
			},
			failureReason: {
				type: String,
				default: 'RECHECK_ERROR'
			},
			busy: {
				type: Boolean,
				default: false
			},
			appearance: {
				type: String,
				default: 'DEFAULT',
				validator: value => value === 'DEFAULT' || value === 'ADMIN_QUIET'
			}
		},
		computed: {
			copy() {
				return COPY[this.audience]
			},
			description() {
				if (this.failureReason === 'FLOW_EXPIRED') {
					return '本次安全验证已过期。请重新检查以开始新的验证。'
				}
				if (this.failureReason === 'RECHECK_ERROR') {
					return '安全状态复查暂时无法完成。请检查网络连接后重新检查。'
				}
				return this.copy.description
			}
		},
		methods: {
			requestRetry() {
				if (this.busy) return
				this.$emit('retry')
			},
			focusTitle() {
				// #ifdef H5
				document.getElementById('challenge-failed-title')?.focus()
				// #endif
			}
		}
	}
</script>

<style scoped>
	.gate-page {
		--gate-bg: #1c2118;
		--gate-bg: oklch(0.19 0.03 118);
		--gate-surface: #303827;
		--gate-surface: oklch(0.25 0.04 112);
		--gate-text: #f6f7ec;
		--gate-text: oklch(0.96 0.02 105);
		--gate-muted: #c6cdb3;
		--gate-muted: oklch(0.80 0.04 108);
		--gate-lime: #c4ec43;
		--gate-lime: oklch(0.84 0.18 113);
		--gate-yellow: #f0d54a;
		--gate-yellow: oklch(0.87 0.15 92);
		--gate-border: #606a43;
		--gate-border: oklch(0.43 0.07 108);
		--gate-on-action: #202817;
		--gate-on-action: oklch(0.20 0.04 115);
		box-sizing: border-box;
		min-height: 100vh;
		min-height: 100dvh;
		padding:
			max(24px, env(safe-area-inset-top))
			max(16px, env(safe-area-inset-right))
			max(24px, env(safe-area-inset-bottom))
			max(16px, env(safe-area-inset-left));
		background: var(--gate-bg);
		color: var(--gate-text);
		font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
	}

	.gate-panel {
		display: grid;
		gap: 32px;
		width: min(100%, 1040px);
		margin: 0 auto;
		padding: clamp(24px, 5vw, 56px);
		border: 1px solid var(--gate-border);
		border-radius: 16px;
		background: var(--gate-surface);
	}

	.gate-status,
	.gate-recovery {
		display: flex;
		min-width: 0;
		flex-direction: column;
		align-items: flex-start;
	}

	.status-label {
		display: inline-flex;
		min-height: 32px;
		align-items: center;
		gap: 8px;
		margin-bottom: 24px;
		color: var(--gate-yellow);
		font-size: 15px;
		font-weight: 700;
		letter-spacing: 0.02em;
	}

	.status-mark {
		width: 10px;
		height: 10px;
		border: 2px solid currentColor;
		border-radius: 50%;
	}

	.gate-title {
		max-width: 18ch;
		margin: 0 0 16px;
		font-size: clamp(32px, 7vw, 52px);
		font-weight: 750;
		line-height: 1.12;
		letter-spacing: -0.03em;
		outline: none;
	}

	.gate-title:focus-visible {
		border-radius: 8px;
		box-shadow: 0 0 0 3px var(--gate-yellow);
	}

	.gate-description,
	.gate-security,
	.recovery-hint {
		display: block;
		font-size: 16px;
		line-height: 1.7;
	}

	.gate-description,
	.recovery-hint {
		color: var(--gate-muted);
	}

	.gate-security {
		margin-top: 24px;
		padding-top: 24px;
		border-top: 1px solid var(--gate-border);
		color: var(--gate-text);
		font-weight: 650;
	}

	.gate-recovery {
		justify-content: flex-end;
		padding-top: 32px;
		border-top: 1px solid var(--gate-border);
	}

	.recovery-title {
		margin-bottom: 12px;
		font-size: clamp(22px, 5vw, 28px);
		font-weight: 720;
		line-height: 1.25;
	}

	.recovery-hint {
		margin-bottom: 24px;
	}

	.retry-button {
		box-sizing: border-box;
		width: 100%;
		min-height: 52px;
		margin: 0;
		padding: 12px 20px;
		border: 1px solid var(--gate-lime);
		border-radius: 12px;
		background: var(--gate-lime);
		color: var(--gate-on-action);
		font: inherit;
		font-size: 17px;
		font-weight: 750;
		line-height: 1.35;
		transition: transform 110ms ease-out, filter 110ms ease-out, opacity 110ms ease-out;
		transform: scale(1);
		-webkit-tap-highlight-color: transparent;
	}

	.retry-button::after {
		border: 0;
	}

	.retry-button:active:not([disabled]) {
		transform: scale(0.985);
		filter: brightness(0.94);
	}

	.retry-button:focus-visible {
		outline: 3px solid var(--gate-yellow);
		outline-offset: 3px;
	}

	.retry-button[disabled] {
		cursor: wait;
		opacity: 0.7;
	}

	.appearance-admin-quiet {
		--gate-bg: #080b0d;
		--gate-surface: rgba(16, 22, 26, 0.88);
		--gate-text: #f3f8f8;
		--gate-muted: #91a2a8;
		--gate-lime: #39d6d2;
		--gate-yellow: #f3be58;
		--gate-border: rgba(145, 162, 168, 0.26);
		--gate-on-action: #071012;
		background:
			radial-gradient(circle at 18% 0%, rgba(57, 214, 210, 0.1), transparent 34%),
			var(--gate-bg);
	}

	.appearance-admin-quiet .gate-panel {
		-webkit-backdrop-filter: blur(24px) saturate(145%);
		backdrop-filter: blur(24px) saturate(145%);
		box-shadow: 0 8px 16px rgba(0, 0, 0, 0.24);
	}

	@media (min-width: 760px) {
		.gate-page {
			display: grid;
			place-items: center;
			padding:
				max(40px, env(safe-area-inset-top))
				max(32px, env(safe-area-inset-right))
				max(40px, env(safe-area-inset-bottom))
				max(32px, env(safe-area-inset-left));
		}

		.gate-panel {
			grid-template-columns: minmax(0, 1.15fr) minmax(300px, 0.85fr);
			align-items: stretch;
			gap: 48px;
		}

		.gate-recovery {
			padding-top: 0;
			padding-left: 48px;
			border-top: 0;
			border-left: 1px solid var(--gate-border);
		}
	}

	@media (prefers-reduced-motion: reduce) {
		.retry-button {
			transition: opacity 0s, filter 0s;
		}

		.retry-button:active:not([disabled]) {
			transform: none;
		}
	}

	@media (prefers-reduced-transparency: reduce) {
		.appearance-admin-quiet .gate-panel {
			background: #10161a;
			-webkit-backdrop-filter: none;
			backdrop-filter: none;
		}
	}

	@media (prefers-contrast: more) {
		.appearance-admin-quiet .gate-panel {
			border: 2px solid #f3f8f8;
			background: #080b0d;
		}
	}
</style>
