export function appendVoiceTranscriptToDraft(draft, transcript) {
	const existing = String(draft || '')
	const text = String(transcript || '').trim()
	if (!text) return existing
	if (!existing) return text
	if (/\s$/.test(existing) || /^[,.;:!?，。；：！？]/.test(text)) return existing + text
	const previous = existing[existing.length - 1]
	const first = text[0]
	const bothCjk = /[\u3400-\u9fff]/.test(previous) && /[\u3400-\u9fff]/.test(first)
	return `${existing}${bothCjk ? '' : ' '}${text}`
}

