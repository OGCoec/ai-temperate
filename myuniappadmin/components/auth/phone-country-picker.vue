<template>
	<view class="country-field">
		<text class="auth-label">国家或地区</text>
		<button
			ref="trigger"
			class="country-trigger"
			type="button"
			:aria-label="currentCountry ? `${currentCountry.name}，区号 ${currentCountry.dialCode}` : '正在识别国家或地区，可打开手动选择'"
			:aria-expanded="isOpen"
			:aria-busy="resolving"
			aria-controls="auth-country-dialog"
			@click="openPicker"
		>
			<view class="country-main">
				<template v-if="currentCountry">
					<image
						v-if="hasFlag(currentCountry)"
						class="country-flag"
						:src="currentCountry.flag"
						mode="aspectFill"
						aria-hidden="true"
						@error="handleFlagError(currentCountry.iso2)"
					/>
					<view v-else class="flag-fallback" aria-hidden="true">
						<uni-icons type="map" size="18" color="#91a2a8" />
					</view>
					<text class="country-name">{{ currentCountry.name }}</text>
				</template>
				<template v-else>
					<uni-icons class="country-neutral-icon" type="map" size="20" color="#91a2a8" aria-hidden="true" />
					<text class="country-name country-neutral-copy">{{ resolving ? '正在识别国家或地区' : '请选择国家或地区' }}</text>
				</template>
			</view>
			<view class="country-meta" aria-hidden="true">
				<text v-if="currentCountry" class="country-code">{{ currentCountry.dialCode }}</text>
				<uni-icons type="bottom" size="16" color="#91a2a8" />
			</view>
		</button>

		<uni-popup ref="popup" type="bottom" :safe-area="true" @change="onPopupChange">
			<view
				id="auth-country-dialog"
				class="country-sheet"
				role="dialog"
				aria-modal="true"
				aria-labelledby="auth-country-title"
			>
				<view class="sheet-header">
					<text id="auth-country-title" class="sheet-title">选择国家或地区</text>
					<button class="sheet-close" type="button" aria-label="关闭国家选择" @click="closePicker">
						<uni-icons type="closeempty" size="22" color="#f3f8f8" aria-hidden="true" />
					</button>
				</view>
				<uni-search-bar
					v-model="keyword"
					class="country-search"
					placeholder="搜索名称、ISO2 或国际区号"
					cancel-button="none"
					clear-button="auto"
					:focus="searchFocused"
					:radius="10"
					@confirm="selectFirst"
				/>
				<scroll-view
					:key="countryListKey"
					class="country-list"
					scroll-y
					:scroll-into-view="scrollTarget"
					role="listbox"
					aria-label="国家或地区"
				>
					<view v-if="countries.length === 0" class="empty-state" role="status">没有匹配的国家或地区</view>
					<button
						v-for="(country, index) in countries"
						:id="`country-${country.id}`"
						ref="countryOptions"
						:key="country.id"
						class="country-option"
						:class="{ selected: currentCountry && country.id === currentCountry.id }"
						type="button"
						role="option"
						:aria-selected="Boolean(currentCountry && country.id === currentCountry.id)"
						@click="select(country)"
						@keydown="onOptionKeydown($event, index)"
					>
						<view class="country-main">
							<image
								v-if="hasFlag(country)"
								class="country-flag"
								:src="country.flag"
								mode="aspectFill"
								aria-hidden="true"
								@error="handleFlagError(country.iso2)"
							/>
							<view v-else class="flag-fallback" aria-hidden="true">
								<uni-icons type="map" size="18" color="#91a2a8" />
							</view>
							<view class="country-copy">
								<text class="country-name">{{ country.name }}</text>
								<text class="country-iso">{{ country.iso2.toUpperCase() }}</text>
							</view>
						</view>
						<view class="country-meta" aria-hidden="true">
							<text class="country-code">{{ country.dialCode }}</text>
							<uni-icons v-if="currentCountry && country.id === currentCountry.id" type="checkmarkempty" size="18" color="#39d6d2" />
						</view>
					</button>
				</scroll-view>
			</view>
		</uni-popup>
	</view>
</template>

<script>
	import { filterPhoneCountries, findPhoneCountryById } from '@shared-auth/phone-country-search.js'

	export default {
		name: 'PhoneCountryPicker',
		emits: ['update:modelValue', 'visibility-change'],
		props: {
			modelValue: { type: String, default: '' },
			resolving: { type: Boolean, default: false }
		},
		data() {
			return {
				keyword: '',
				scrollTarget: '',
				searchFocused: false,
				isOpen: false,
				scrollTimer: null,
				failedFlags: {}
			}
		},
		computed: {
			currentCountry() { return findPhoneCountryById(this.modelValue) },
			countries() { return filterPhoneCountries(this.keyword) },
			countryListKey() {
				return this.keyword ? `search-${this.keyword.trim().toLowerCase()}` : 'all-countries'
			}
		},
		watch: {
			keyword() {
				clearTimeout(this.scrollTimer)
				this.scrollTarget = ''
			}
		},
		mounted() {
			// #ifdef H5
			window.addEventListener('keydown', this.onWindowKeydown)
			// #endif
		},
		beforeUnmount() {
			clearTimeout(this.scrollTimer)
			// #ifdef H5
			window.removeEventListener('keydown', this.onWindowKeydown)
			// #endif
		},
		methods: {
			openPicker() {
				if (this.isOpen) return
				clearTimeout(this.scrollTimer)
				this.keyword = ''
				this.scrollTarget = ''
				this.$refs.popup.open('bottom')
			},
			closePicker() {
				if (!this.isOpen) return false
				this.$refs.popup.close()
				return true
			},
			onPopupChange(event) {
				this.isOpen = Boolean(event.show)
				this.$emit('visibility-change', this.isOpen)

				if (this.isOpen) {
					// #ifdef H5
					this.searchFocused = true
					// #endif
					clearTimeout(this.scrollTimer)
					if (this.currentCountry) {
						this.scrollTimer = setTimeout(() => {
							if (this.keyword || !this.currentCountry) return
							this.scrollTarget = `country-${this.currentCountry.id}`
						}, 80)
					}
					return
				}

				this.keyword = ''
				this.scrollTarget = ''
				this.searchFocused = false
				this.restoreTriggerFocus()
			},
			select(country) {
				this.$emit('update:modelValue', country.id)
				this.closePicker()
			},
			selectFirst() {
				if (this.countries.length) this.select(this.countries[0])
			},
			onWindowKeydown(event) {
				if (this.isOpen && event.key === 'Escape') {
					event.preventDefault()
					this.closePicker()
				}
			},
			onOptionKeydown(event, index) {
				if (!['ArrowDown', 'ArrowUp', 'Home', 'End', 'Escape'].includes(event.key)) return
				event.preventDefault()
				if (event.key === 'Escape') {
					this.closePicker()
					return
				}
				let nextIndex = index
				if (event.key === 'ArrowDown') nextIndex = Math.min(index + 1, this.countries.length - 1)
				if (event.key === 'ArrowUp') nextIndex = Math.max(index - 1, 0)
				if (event.key === 'Home') nextIndex = 0
				if (event.key === 'End') nextIndex = this.countries.length - 1
				this.focusOption(nextIndex)
			},
			focusOption(index) {
				// #ifdef H5
				const option = (this.$refs.countryOptions || [])[index]
				const element = option?.$el || option
				if (element?.focus) element.focus()
				// #endif
			},
			restoreTriggerFocus() {
				// #ifdef H5
				this.$nextTick(() => {
					const trigger = this.$refs.trigger?.$el || this.$refs.trigger
					if (trigger?.focus) trigger.focus()
				})
				// #endif
			},
			hasFlag(country) {
				return Boolean(country?.flag && !this.failedFlags[country.iso2])
			},
			handleFlagError(iso2) {
				const key = String(iso2 || '').trim().toLowerCase()
				if (!key) return
				if (this.$set) {
					this.$set(this.failedFlags, key, true)
					return
				}
				this.failedFlags = { ...this.failedFlags, [key]: true }
			}
		}
	}
</script>

<style scoped>
	.country-field { margin-bottom: 20px; }
	.country-trigger,
	.country-option,
	.country-main,
	.country-meta,
	.sheet-header,
	.sheet-close,
	.flag-fallback { display: flex; align-items: center; }
	.country-trigger {
		width: 100%;
		min-height: 50px;
		margin: 0;
		padding: 0 14px;
		border: 1px solid #2b3c44;
		border-radius: 10px;
		background: #141d22;
		justify-content: space-between;
		line-height: 1;
		box-sizing: border-box;
		transition: border-color 180ms ease, background-color 180ms ease, box-shadow 180ms ease, transform 180ms ease;
	}
	.country-trigger::after,
	.country-option::after,
	.sheet-close::after { border: 0; }
	.country-trigger:active { border-color: #39d6d2; transform: scale(.985); }
	.sheet-close:active { background: #18252a; transform: scale(.94); }
	.country-trigger:focus-visible,
	.country-option:focus-visible,
	.sheet-close:focus-visible { outline: none; box-shadow: 0 0 0 3px rgba(57, 214, 210, .18); }
	.country-main { min-width: 0; flex: 1; gap: 10px; }
	.country-meta { flex-shrink: 0; gap: 10px; }
	.country-flag,
	.flag-fallback {
		width: 28px;
		height: 21px;
		flex-shrink: 0;
		overflow: hidden;
		border-radius: 3px;
		background: #18252a;
	}
	.flag-fallback { justify-content: center; }
	.country-neutral-icon { width: 28px; flex-shrink: 0; text-align: center; }
	.country-neutral-copy { color: #91a2a8; }
	.country-name { min-width: 0; overflow: hidden; color: #f3f8f8; font-size: 15px; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
	.country-code,
	.country-iso { color: #91a2a8; font-size: 13px; font-weight: 600; line-height: 1.35; }
	.country-copy { min-width: 0; display: flex; flex-direction: column; gap: 2px; text-align: left; }
	.country-sheet {
		height: 72vh;
		max-height: 620px;
		padding: 18px 16px 0;
		border-radius: 18px 18px 0 0;
		background: #10161a;
		box-sizing: border-box;
	}
	.sheet-header { min-height: 44px; justify-content: space-between; }
	.sheet-title { color: #f3f8f8; font-size: 18px; font-weight: 700; }
	.sheet-close {
		width: 44px;
		height: 44px;
		margin: 0;
		padding: 0;
		justify-content: center;
		border: 0;
		border-radius: 22px;
		background: transparent;
		line-height: 1;
		transition: background-color 180ms ease, box-shadow 180ms ease, transform 180ms ease;
	}
	.country-search { margin: 6px -10px 8px; }
	::v-deep .country-search .uni-searchbar__box { background: #18252a !important; border: 1px solid #2b3c44 !important; }
	::v-deep .country-search .uni-input-input,
	::v-deep .country-search .uni-searchbar__box-search-input { color: #f3f8f8 !important; font-size: 16px !important; }
	.country-list { height: calc(72vh - 126px); max-height: 494px; }
	.country-option {
		width: 100%;
		min-height: 58px;
		margin: 0;
		padding: 0 12px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		justify-content: space-between;
		line-height: 1;
		box-sizing: border-box;
	}
	.country-option:active { background: #18252a; }
	.country-option.selected { background: #123f45; }
	.empty-state { padding: 52px 16px; color: #91a2a8; font-size: 14px; text-align: center; }
	@media (hover: hover) and (pointer: fine) {
		.country-trigger:hover { background: #18252a; border-color: #3e5a63; }
		.country-option:hover,
		.sheet-close:hover { background: #18252a; }
	}
	@media screen and (min-width: 690px) { .country-sheet { width: 520px; margin: 0 auto; } }
	@media (prefers-reduced-motion: reduce) {
		.country-trigger,
		.sheet-close { transition: none; }
	}
</style>
