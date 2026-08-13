const PROVIDER_FALLBACK_LABELS = Object.freeze({
	openai: 'AI',
	xai: 'xAI',
	anthropic: 'A',
	google: 'G'
})

function providerKey(model) {
	return String(model?.vendor || model?.providerName || model?.provider || '')
		.trim()
		.toLowerCase()
		.replace(/[\s_-]+/g, '')
}

function existingModelIcon(model) {
	return typeof model?.icon === 'string' ? model.icon.trim() : ''
}

export function modelProviderLogoSources(model) {
	const modelIcon = existingModelIcon(model)
	return modelIcon ? [modelIcon] : []
}

export function modelProviderFallbackLabel(model) {
	const key = providerKey(model)
	if (PROVIDER_FALLBACK_LABELS[key]) return PROVIDER_FALLBACK_LABELS[key]
	const provider = String(model?.vendor || model?.providerName || model?.provider || '').trim()
	return Array.from(provider).slice(0, 2).join('').toUpperCase() || 'AI'
}
