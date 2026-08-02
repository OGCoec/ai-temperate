let authenticated = false
let version = 0

export function isRuntimeSessionAuthenticated() {
	return authenticated
}

export function runtimeAuthenticationVersion() {
	return version
}

export function markRuntimeSessionAuthenticated() {
	if (!authenticated) {
		authenticated = true
		version += 1
	}
	return version
}

export function clearRuntimeSessionAuthentication() {
	if (authenticated) {
		authenticated = false
		version += 1
	}
	return version
}
