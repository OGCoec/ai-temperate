import { adminApi } from './admin-api.js'
import { findPhoneCountryById, getPhoneCountryByIso2 } from '@shared-auth/phone-country-search.js'

const SOURCE = Object.freeze({
	UNRESOLVED: 'UNRESOLVED',
	IP: 'IP',
	DEVICE: 'DEVICE',
	FALLBACK: 'FALLBACK',
	USER: 'USER'
})

let selection = { countryId: '', source: SOURCE.UNRESOLVED }
let selectionVersion = 0
let resolutionPromise = null

function snapshot() {
	return { ...selection }
}

function parseDeviceRegionIso2(languageTag) {
	const normalized = String(languageTag || '').trim().replace(/_/g, '-')
	if (!normalized) return ''
	const subtags = normalized.split('-').filter(Boolean)
	for (let index = 1; index < subtags.length; index += 1) {
		if (/^[A-Za-z]{2}$/.test(subtags[index])) return subtags[index].toUpperCase()
	}
	return ''
}

function readDeviceRegionIso2() {
	let osRegion = ''
	try {
		osRegion = parseDeviceRegionIso2(uni.getSystemInfoSync()?.osLanguage)
	} catch (_) {
		osRegion = ''
	}
	if (osRegion) return osRegion
	try {
		return parseDeviceRegionIso2(uni.getLocale())
	} catch (_) {
		return ''
	}
}

function automaticFallback() {
	const deviceCountry = getPhoneCountryByIso2(readDeviceRegionIso2())
	if (deviceCountry) return { countryId: deviceCountry.id, source: SOURCE.DEVICE }
	return { countryId: '', source: SOURCE.FALLBACK }
}

function commitAutomatic(nextSelection, startedAtVersion) {
	// 用户选择优先级最高；网络结果迟到时不得把表单恢复成自动识别值。
	if (selection.source === SOURCE.USER || selectionVersion !== startedAtVersion) return snapshot()
	selection = nextSelection
	return snapshot()
}

async function resolveAutomaticCountry(startedAtVersion) {
	try {
		const response = await adminApi.phoneCountry()
		const ipCountry = response?.resolved
			? getPhoneCountryByIso2(response.countryIso2)
			: null
		if (ipCountry) {
			return commitAutomatic(
				{ countryId: ipCountry.id, source: SOURCE.IP },
				startedAtVersion)
		}
		return commitAutomatic(automaticFallback(), startedAtVersion)
	} catch (_) {
		// 网络、HTTP 和八秒前端超时均保持空选择，避免根据设备语言伪造国家建议。
		return commitAutomatic(
			{ countryId: '', source: SOURCE.FALLBACK },
			startedAtVersion)
	}
}

export function getCurrentAdminPhoneCountrySelection() {
	return snapshot()
}

export function resolveInitialAdminPhoneCountry() {
	if (selection.source !== SOURCE.UNRESOLVED) return Promise.resolve(snapshot())
	if (resolutionPromise) return resolutionPromise
	const startedAtVersion = selectionVersion
	resolutionPromise = resolveAutomaticCountry(startedAtVersion)
		.finally(() => { resolutionPromise = null })
	return resolutionPromise
}

export function selectAdminPhoneCountry(countryId) {
	const country = findPhoneCountryById(countryId)
	if (!country) return snapshot()
	selectionVersion += 1
	selection = { countryId: country.id, source: SOURCE.USER }
	return snapshot()
}
