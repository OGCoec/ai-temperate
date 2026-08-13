function hasSuccessfulMediaOutput(attachments) {
	return (attachments || []).some(attachment => {
		if (!String(attachment?.url || '').trim()) return false
		const mediaType = String(
			attachment?.contentType || attachment?.mediaType || attachment?.type || ''
		).toUpperCase()
		return mediaType.startsWith('IMAGE/')
			|| mediaType.startsWith('VIDEO/')
			|| mediaType === 'IMAGE'
			|| mediaType === 'VIDEO'
	})
}

export function shouldShowAiResultDisclaimer(message) {
	if (!message || message.streaming || message.saving
		|| message.stopped || message.error) return false
	if (String(message.responseText || '').trim()) return true
	return hasSuccessfulMediaOutput(message.responseAttachments)
}
