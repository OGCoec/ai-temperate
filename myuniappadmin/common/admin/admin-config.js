let adminApiBaseUrl = 'https://api.niko000o.site'

// #ifdef H5
const h5Hostname = typeof window !== 'undefined' && window.location
	? window.location.hostname
	: ''
if (h5Hostname === 'localhost' || h5Hostname === '127.0.0.1') {
	adminApiBaseUrl = 'https://localhost:6655'
} else if (h5Hostname === 'admin.niko000o.site') {
	adminApiBaseUrl = ''
}
// #endif

export const ADMIN_API_BASE_URL = adminApiBaseUrl

export function adminClientPlatform() {
	// #ifdef APP-PLUS
	return 'ANDROID'
	// #endif
	// #ifndef APP-PLUS
	return 'H5'
	// #endif
}
