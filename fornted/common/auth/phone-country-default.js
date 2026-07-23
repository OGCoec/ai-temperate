import { authApi } from './auth-api.js'
import { findPhoneCountryById, getPhoneCountryByIso2 } from './phone-country-search.js'

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

export function parseDeviceRegionIso2(languageTag) {
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
	} catch (error) {
		osRegion = ''
	}
	if (osRegion) return osRegion
	try {
		return parseDeviceRegionIso2(uni.getLocale())
	} catch (error) {
		return ''
	}
}

function automaticFallback() {
	const deviceCountry = getPhoneCountryByIso2(readDeviceRegionIso2())
	if (deviceCountry) return { countryId: deviceCountry.id, source: SOURCE.DEVICE }
	return { countryId: '', source: SOURCE.FALLBACK }
}

function commitAutomatic(nextSelection, startedAtVersion) {
	if (selection.source === SOURCE.USER || selectionVersion !== startedAtVersion) return snapshot()
	selection = nextSelection
	return snapshot()
}

async function resolveAutomaticCountry(startedAtVersion) {
	try {
		const response = await authApi.phoneCountry()
		const ipCountry = response?.resolved
			? getPhoneCountryByIso2(response.countryIso2)
			: null
		if (ipCountry) {
			return commitAutomatic({ countryId: ipCountry.id, source: SOURCE.IP }, startedAtVersion)
		}
	} catch (error) {
		// Default-country discovery is best effort and must not create an auth error banner.
	}
	return commitAutomatic(automaticFallback(), startedAtVersion)
}

export function getCurrentPhoneCountrySelection() {
	return snapshot()
}

export function resolveInitialPhoneCountry() {
	if (selection.source !== SOURCE.UNRESOLVED) return Promise.resolve(snapshot())
	if (resolutionPromise) return resolutionPromise
	const startedAtVersion = selectionVersion
	resolutionPromise = resolveAutomaticCountry(startedAtVersion)
		.finally(() => { resolutionPromise = null })
	return resolutionPromise
}

export function selectPhoneCountry(countryId) {
	const country = findPhoneCountryById(countryId)
	if (!country) return snapshot()
	selectionVersion += 1
	selection = { countryId: country.id, source: SOURCE.USER }
	return snapshot()
}
