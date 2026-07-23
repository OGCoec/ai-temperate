let memoryState = {}

function readBrowserState() {
	return memoryState
}

function writeBrowserState(value) {
	memoryState = value
}

export function loadFlow(name) {
	return readBrowserState()[name] || null
}

export function saveFlow(name, value) {
	const state = readBrowserState()
	state[name] = value
	writeBrowserState(state)
}

export function clearFlow(name) {
	const state = readBrowserState()
	delete state[name]
	writeBrowserState(state)
}
