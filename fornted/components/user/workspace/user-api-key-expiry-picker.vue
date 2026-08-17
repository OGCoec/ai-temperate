<template>
	<view class="api-key-expiry-picker" @keydown.esc="handleEscape">
		<view class="expiry-preset-grid" role="radiogroup" aria-label="API Key 有效期">
			<button
				v-for="preset in presets"
				:key="preset.option"
				class="expiry-preset"
				:class="{ active: selection.option === preset.option }"
				type="button"
				role="radio"
				:aria-checked="String(selection.option === preset.option)"
				:disabled="disabled"
				@click="selectPreset(preset.option)"
			>
				{{ preset.label }}
			</button>
		</view>

		<view v-if="selection.option === expiryOptions.CUSTOM" class="expiry-custom-panel">
			<label class="expiry-input-label" for="api-key-expiry-input">指定过期日期</label>
			<view class="expiry-input-shell" :class="{ invalid: Boolean(inputError) }">
				<input
					id="api-key-expiry-input"
					class="expiry-input"
					type="text"
					inputmode="numeric"
					:value="inputText"
					:disabled="disabled"
					placeholder="例如 2026年8月20日"
					:aria-describedby="inputDescribedBy"
					:aria-invalid="String(Boolean(inputError))"
					@input="handleInput"
					@blur="commitInput"
					@confirm="commitInput"
					@keydown.enter.stop.prevent="commitInput"
					@click="openCalendar"
				/>
				<button
					class="expiry-calendar-toggle"
					type="button"
					:disabled="disabled"
					:aria-label="calendarOpen ? '收起日期日历' : '打开日期日历'"
					:aria-expanded="String(calendarOpen)"
					aria-controls="api-key-expiry-calendar"
					@click="toggleCalendar"
				>
					<uni-icons type="calendar" size="18" color="#75dfb7" aria-hidden="true" />
				</button>
			</view>
			<text id="api-key-expiry-help" class="expiry-input-help">支持 2026-08-20 或 2026年8月20日</text>
			<text v-if="inputError" id="api-key-expiry-error" class="expiry-input-error" role="alert">{{ inputError }}</text>

			<view
				v-if="calendarOpen"
				id="api-key-expiry-calendar"
				class="expiry-calendar"
				role="grid"
				:aria-label="`${calendarTitle}过期日期选择`"
				@keydown="handleCalendarKeydown"
			>
				<view class="expiry-calendar-heading">
					<button
						class="expiry-calendar-nav"
						type="button"
						aria-label="上个月"
						:disabled="disabled || previousMonthDisabled"
						@click="changeVisibleMonth(-1)"
					>‹</button>
					<text class="expiry-calendar-title">{{ calendarTitle }}</text>
					<button
						class="expiry-calendar-nav"
						type="button"
						aria-label="下个月"
						:disabled="disabled"
						@click="changeVisibleMonth(1)"
					>›</button>
				</view>
				<view class="expiry-calendar-weekdays" role="row">
					<text v-for="weekday in weekdays" :key="weekday" role="columnheader">{{ weekday }}</text>
				</view>
				<view class="expiry-calendar-days" role="row">
					<view v-for="cell in calendarCells" :key="cell.localDate" class="expiry-calendar-cell" role="gridcell">
						<button
							class="expiry-calendar-day"
							:class="{
								outside: !cell.inVisibleMonth,
								today: cell.isToday,
								selected: cell.selected
							}"
							type="button"
							:data-local-date="cell.localDate"
							:aria-label="calendarCellLabel(cell)"
							:aria-selected="String(cell.selected)"
							:disabled="disabled || cell.disabled"
							@click="selectCalendarDate(cell)"
						>
							{{ cell.day }}
						</button>
					</view>
				</view>
			</view>
		</view>

		<view v-if="expirySummary" class="expiry-summary" role="status">
			<text class="expiry-summary-dot" aria-hidden="true"></text>
			<text>将于 {{ expirySummary }} 23:59 到期</text>
		</view>
	</view>
</template>

<script>
	import {
		API_KEY_EXPIRY_OPTION,
		buildExpiryCalendarMonth,
		createCustomExpirySelection,
		createPermanentExpirySelection,
		createPresetExpirySelection,
		formatExpiryDateZhCn,
		parseExpiryDateInput
	} from '@/common/user/api-key-expiry.js'

	const PRESETS = Object.freeze([
		Object.freeze({ option: API_KEY_EXPIRY_OPTION.PERMANENT, label: '永久有效' }),
		Object.freeze({ option: API_KEY_EXPIRY_OPTION.ONE_DAY, label: '1 天' }),
		Object.freeze({ option: API_KEY_EXPIRY_OPTION.THREE_DAYS, label: '3 天' }),
		Object.freeze({ option: API_KEY_EXPIRY_OPTION.ONE_WEEK, label: '1 周' }),
		Object.freeze({ option: API_KEY_EXPIRY_OPTION.ONE_MONTH, label: '1 个月' }),
		Object.freeze({ option: API_KEY_EXPIRY_OPTION.THREE_MONTHS, label: '3 个月' }),
		Object.freeze({ option: API_KEY_EXPIRY_OPTION.ONE_YEAR, label: '1 年' }),
		Object.freeze({ option: API_KEY_EXPIRY_OPTION.CUSTOM, label: '自定义日期' })
	])

	function localDateObject(value) {
		const canonical = parseExpiryDateInput(value)
		const [year, month, day] = canonical.split('-').map(Number)
		return new Date(year, month - 1, day, 12)
	}

	export default {
		props: {
			selection: {
				type: Object,
				default: () => createPermanentExpirySelection()
			},
			disabled: { type: Boolean, default: false }
		},
		emits: ['change', 'validity'],
		data() {
			const now = new Date()
			return {
				expiryOptions: API_KEY_EXPIRY_OPTION,
				presets: PRESETS,
				weekdays: Object.freeze(['日', '一', '二', '三', '四', '五', '六']),
				inputText: '',
				inputError: '',
				calendarOpen: false,
				visibleYear: now.getFullYear(),
				visibleMonth: now.getMonth()
			}
		},
		computed: {
			inputDescribedBy() {
				return this.inputError
					? 'api-key-expiry-help api-key-expiry-error'
					: 'api-key-expiry-help'
			},
			expirySummary() {
				if (!this.selection?.localDate) return ''
				try {
					return formatExpiryDateZhCn(this.selection.localDate)
				} catch (_) {
					return ''
				}
			},
			calendarTitle() {
				return `${this.visibleYear}年${this.visibleMonth + 1}月`
			},
			calendarCells() {
				return buildExpiryCalendarMonth(
					this.visibleYear,
					this.visibleMonth,
					new Date(),
					this.selection?.localDate || null)
			},
			previousMonthDisabled() {
				const now = new Date()
				return this.visibleYear < now.getFullYear()
					|| this.visibleYear === now.getFullYear() && this.visibleMonth <= now.getMonth()
			}
		},
		watch: {
			selection: {
				deep: true,
				immediate: true,
				handler(value) {
					this.syncSelection(value)
				}
			}
		},
		methods: {
			syncSelection(value) {
				if (value?.option !== API_KEY_EXPIRY_OPTION.CUSTOM) {
					this.inputText = ''
					this.inputError = ''
					return
				}
				if (!value.localDate) {
					this.inputText = ''
					return
				}
				try {
					this.inputText = formatExpiryDateZhCn(value.localDate)
					this.inputError = ''
					this.setVisibleMonth(value.localDate)
				} catch (_) {
					this.inputError = '过期日期无效。'
				}
			},
			emitSelection(value) {
				this.$emit('change', value)
			},
			emitValidity(valid, message = '') {
				this.$emit('validity', { valid, message })
			},
			selectPreset(option) {
				if (this.disabled) return
				if (option === API_KEY_EXPIRY_OPTION.PERMANENT) {
					this.calendarOpen = false
					this.inputError = ''
					this.emitSelection(createPermanentExpirySelection())
					this.emitValidity(true)
					return
				}
				if (option === API_KEY_EXPIRY_OPTION.CUSTOM) {
					if (this.selection?.option === API_KEY_EXPIRY_OPTION.CUSTOM
						&& this.selection.localDate) {
						this.openCalendar()
						return
					}
					this.inputText = ''
					this.inputError = '请选择过期日期。'
					this.emitSelection({ option: API_KEY_EXPIRY_OPTION.CUSTOM, localDate: null })
					this.emitValidity(false, this.inputError)
					this.openCalendar()
					return
				}
				const value = createPresetExpirySelection(option, new Date())
				this.calendarOpen = false
				this.inputError = ''
				this.emitSelection(value)
				this.emitValidity(true)
			},
			handleInput(event) {
				this.inputText = event?.detail?.value ?? event?.target?.value ?? ''
				this.inputError = ''
				this.emitValidity(false)
			},
			commitInput() {
				if (this.disabled) return
				try {
					const value = createCustomExpirySelection(this.inputText, new Date())
					this.inputText = formatExpiryDateZhCn(value.localDate)
					this.inputError = ''
					this.setVisibleMonth(value.localDate)
					this.emitSelection(value)
					this.emitValidity(true)
				} catch (error) {
					this.inputError = error?.message || '请输入有效的过期日期。'
					this.emitValidity(false, this.inputError)
				}
			},
			setVisibleMonth(localDate) {
				const date = localDateObject(localDate)
				this.visibleYear = date.getFullYear()
				this.visibleMonth = date.getMonth()
			},
			openCalendar() {
				if (this.disabled) return
				if (this.selection?.localDate) this.setVisibleMonth(this.selection.localDate)
				this.calendarOpen = true
			},
			closeCalendar() {
				this.calendarOpen = false
			},
			toggleCalendar() {
				if (this.calendarOpen) this.closeCalendar()
				else this.openCalendar()
			},
			handleEscape(event) {
				if (!this.calendarOpen) return
				event?.preventDefault?.()
				event?.stopPropagation?.()
				this.closeCalendar()
			},
			changeVisibleMonth(delta) {
				if (this.disabled || delta < 0 && this.previousMonthDisabled) return
				const target = new Date(this.visibleYear, this.visibleMonth + delta, 1, 12)
				this.visibleYear = target.getFullYear()
				this.visibleMonth = target.getMonth()
			},
			selectCalendarDate(cell) {
				if (this.disabled || cell.disabled) return
				try {
					const value = createCustomExpirySelection(cell.localDate, new Date())
					this.inputText = formatExpiryDateZhCn(value.localDate)
					this.inputError = ''
					this.emitSelection(value)
					this.emitValidity(true)
					this.closeCalendar()
				} catch (error) {
					this.inputError = error?.message || '请选择有效的过期日期。'
					this.emitValidity(false, this.inputError)
				}
			},
			calendarCellLabel(cell) {
				const suffix = cell.isToday ? '，今天' : ''
				return `${formatExpiryDateZhCn(cell.localDate)}${suffix}`
			},
			handleCalendarKeydown(event) {
				if (event.key === 'PageUp' || event.key === 'PageDown') {
					event.preventDefault()
					this.changeVisibleMonth(event.key === 'PageUp' ? -1 : 1)
					return
				}
				const offsets = {
					ArrowLeft: -1,
					ArrowRight: 1,
					ArrowUp: -7,
					ArrowDown: 7
				}
				if (!Object.hasOwn(offsets, event.key)) return
				const localDate = event.target?.dataset?.localDate
				if (!localDate) return
				event.preventDefault()
				this.focusRelativeDate(localDate, offsets[event.key])
			},
			focusRelativeDate(localDate, offset) {
				const source = localDateObject(localDate)
				const target = new Date(
					source.getFullYear(),
					source.getMonth(),
					source.getDate() + offset,
					12)
				const targetLocalDate = `${target.getFullYear()}-${String(target.getMonth() + 1).padStart(2, '0')}-${String(target.getDate()).padStart(2, '0')}`
				const targetCells = buildExpiryCalendarMonth(
					target.getFullYear(),
					target.getMonth(),
					new Date(),
					this.selection?.localDate || null)
				if (targetCells.find(cell => cell.localDate === targetLocalDate)?.disabled) return
				this.visibleYear = target.getFullYear()
				this.visibleMonth = target.getMonth()
				this.$nextTick(() => {
					const root = this.$el?.querySelector ? this.$el : this.$el?.$el
					root?.querySelector?.(`[data-local-date="${targetLocalDate}"]`)?.focus?.()
				})
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.api-key-expiry-picker { width: 100%; }
	.expiry-preset-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
	.expiry-preset { @include user-frosted-control; min-width: 0; min-height: 44px; margin: 0; padding: 0 10px; border-radius: 12px; color: #aeb9b3; font-size: 13px; font-weight: 680; transition: border-color .16s ease, background-color .16s ease, color .16s ease, transform .16s ease; }
	.expiry-preset:hover:not(:disabled) { border-color: rgba(117, 223, 183, .34); color: #d8e4de; transform: translateY(-1px); }
	.expiry-preset.active { border-color: rgba(55, 211, 154, .58); background: rgba(55, 211, 154, .11); color: #75dfb7; box-shadow: inset 0 0 0 1px rgba(55, 211, 154, .08); }
	.expiry-preset:focus-visible, .expiry-calendar-toggle:focus-visible, .expiry-calendar-nav:focus-visible, .expiry-calendar-day:focus-visible, .expiry-input:focus-visible { outline: 2px solid rgba(55, 211, 154, .82); outline-offset: 2px; }
	.expiry-custom-panel { margin-top: 14px; padding: 16px; border: 1px solid rgba(151, 170, 160, .17); border-radius: 16px; background: rgba(10, 13, 11, .56); }
	.expiry-input-label { display: block; margin-bottom: 8px; color: #cbd4cf; font-size: 12px; font-weight: 720; }
	.expiry-input-shell { display: flex; align-items: center; min-height: 48px; border: 1px solid rgba(151, 170, 160, .24); border-radius: 12px; background: #0e120f; transition: border-color .16s ease, box-shadow .16s ease; }
	.expiry-input-shell:focus-within { border-color: rgba(55, 211, 154, .56); box-shadow: 0 0 0 3px rgba(55, 211, 154, .08); }
	.expiry-input-shell.invalid { border-color: rgba(229, 135, 126, .58); }
	.expiry-input { flex: 1; min-width: 0; height: 46px; padding: 0 14px; box-sizing: border-box; border: 0; background: transparent; color: #eef4f1; font-size: 14px; }
	.expiry-calendar-toggle { width: 44px; height: 44px; min-height: 44px; display: inline-flex; align-items: center; justify-content: center; flex: 0 0 44px; margin: 0 2px 0 0; padding: 0; box-sizing: border-box; border: 0; border-radius: 9px; background: rgba(55, 211, 154, .08); color: #75dfb7; font-size: 0; line-height: 1; }
	.expiry-input-help, .expiry-input-error { display: block; margin-top: 7px; font-size: 11px; line-height: 1.5; }
	.expiry-input-help { color: #7e8b84; }
	.expiry-input-error { color: #efb0aa; }
	.expiry-calendar { margin-top: 14px; padding: 12px; border: 1px solid rgba(151, 170, 160, .16); border-radius: 14px; background: #0b0e0c; box-shadow: 0 18px 44px rgba(0, 0, 0, .22); }
	.expiry-calendar-heading { display: grid; grid-template-columns: 40px 1fr 40px; align-items: center; gap: 8px; }
	.expiry-calendar-title { text-align: center; color: #e3ebe7; font-size: 14px; font-weight: 740; }
	.expiry-calendar-nav { width: 40px; height: 40px; min-height: 40px; margin: 0; padding: 0; border: 1px solid rgba(151, 170, 160, .16); border-radius: 10px; background: rgba(24, 29, 26, .72); color: #b8c4be; font-size: 24px; }
	.expiry-calendar-nav:disabled { opacity: .32; }
	.expiry-calendar-weekdays, .expiry-calendar-days { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); }
	.expiry-calendar-weekdays { margin-top: 8px; color: #65736c; font-size: 10px; font-weight: 720; text-align: center; }
	.expiry-calendar-weekdays text { padding: 8px 0 5px; }
	.expiry-calendar-cell { display: flex; align-items: center; justify-content: center; min-width: 0; }
	.expiry-calendar-day { width: 100%; min-width: 0; min-height: 40px; margin: 1px; padding: 0; border: 1px solid transparent; border-radius: 10px; background: transparent; color: #b8c3bd; font-size: 12px; }
	.expiry-calendar-day.outside { color: #56615b; }
	.expiry-calendar-day.today { border-color: rgba(117, 223, 183, .34); color: #dbe9e2; }
	.expiry-calendar-day.selected { border-color: rgba(55, 211, 154, .74); background: #2eaf82; color: #06110d; font-weight: 780; box-shadow: 0 6px 18px rgba(46, 175, 130, .22); }
	.expiry-calendar-day:disabled { color: #3d4541; opacity: .68; }
	.expiry-summary { display: flex; align-items: center; gap: 8px; margin-top: 12px; padding: 10px 12px; border: 1px solid rgba(55, 211, 154, .14); border-radius: 11px; background: rgba(55, 211, 154, .055); color: #9edac2; font-size: 12px; }
	.expiry-summary-dot { width: 7px; height: 7px; flex: 0 0 7px; border-radius: 50%; background: #37d39a; box-shadow: 0 0 0 4px rgba(55, 211, 154, .09); }

	@media screen and (max-width: 640px) {
		.expiry-preset-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
		.expiry-custom-panel { padding: 13px 10px; }
		.expiry-calendar { padding: 9px 5px; }
		.expiry-calendar-day { min-height: 40px; margin: 0; border-radius: 8px; }
	}

	@media (prefers-reduced-motion: reduce) {
		.expiry-preset, .expiry-input-shell { transition: none; }
		.expiry-preset:hover:not(:disabled) { transform: none; }
	}
</style>
