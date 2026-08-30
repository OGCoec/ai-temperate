let authenticated = false
let version = 0
let terminalSessionTransitionActive = false
let terminalSessionRedirectClaimed = false

export function isRuntimeSessionAuthenticated() {
	return authenticated
}

export function runtimeAuthenticationVersion() {
	return version
}

export function markRuntimeSessionAuthenticated() {
	terminalSessionTransitionActive = false
	terminalSessionRedirectClaimed = false
	if (!authenticated) {
		authenticated = true
		version += 1
	}
	return version
}

export function beginRuntimeTerminalSessionTransition() {
	if (terminalSessionTransitionActive) return false
	terminalSessionTransitionActive = true
	terminalSessionRedirectClaimed = false
	return true
}

export function claimRuntimeTerminalSessionRedirect() {
	if (!terminalSessionTransitionActive || terminalSessionRedirectClaimed) return false
	terminalSessionRedirectClaimed = true
	return true
}

export function clearRuntimeSessionAuthentication() {
	if (authenticated) {
		authenticated = false
		version += 1
	}
	return version
}
