export const AI_MODEL_CAPABILITY_OPTIONS = Object.freeze([
	{ code: 'CHAT_COMPLETIONS', label: 'Chat Completions', hint: '兼容对话补全协议' },
	{ code: 'RESPONSES', label: 'Responses', hint: '统一响应与工具调用' },
	{ code: 'IMAGE', label: '图像', hint: '图像生成或理解' },
	{ code: 'VIDEO', label: '视频', hint: '视频生成或理解' },
	{ code: 'AUDIO', label: '音频', hint: '语音、音频生成或理解' }
])

const CAPABILITY_CODES = new Set(AI_MODEL_CAPABILITY_OPTIONS.map(item => item.code))
const RATIO_PATTERN = /^(?:0|[1-9]\d{0,11})(?:\.\d{1,8})?$/
const POSITIVE_INTEGER_PATTERN = /^[1-9]\d*$/
const MAX_MODEL_TOKEN_LIMIT_K = 2147483647
const PUBLIC_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/

export function createEmptyAiModelForm() {
	return {
		modelName: '',
		description: '',
		iconPublicId: '',
		iconPreviewUrl: '',
		tagsText: '',
		vendor: '',
		inputRatio: '1',
		cachedInputRatio: '1',
		outputRatio: '1',
		contextWindowK: '',
		maxOutputK: '',
		capabilities: []
	}
}

/**
 * 把网关发现结果收敛为一次性新增表单草稿；计费倍率和能力必须由管理员重新确认。
 */
export function createDiscoveredAiModelForm(prefill = {}) {
	const modelId = String(prefill?.modelId || '').trim().slice(0, 128)
	return {
		...createEmptyAiModelForm(),
		modelName: modelId,
		vendor: '',
		inputRatio: '',
		cachedInputRatio: '',
		outputRatio: '',
		capabilities: []
	}
}

export function cloneAiModelForm(form) {
	return {
		...form,
		capabilities: [...(form?.capabilities || [])]
	}
}

export function modelToAiModelForm(model) {
	return {
		modelName: String(model?.modelName || ''),
		description: String(model?.description || ''),
		iconPublicId: String(model?.iconPublicId || ''),
		iconPreviewUrl: String(model?.icon || ''),
		tagsText: Array.isArray(model?.tags) ? model.tags.join(', ') : '',
		vendor: String(model?.vendor || ''),
		inputRatio: String(model?.inputRatio ?? ''),
		cachedInputRatio: String(model?.cachedInputRatio ?? ''),
		outputRatio: String(model?.outputRatio ?? ''),
		contextWindowK: model?.contextWindowK == null ? '' : String(model.contextWindowK),
		maxOutputK: model?.maxOutputK == null ? '' : String(model.maxOutputK),
		capabilities: orderedCapabilities(model?.capabilities)
	}
}

function orderedCapabilities(capabilities) {
	const selected = new Set(Array.isArray(capabilities) ? capabilities : [])
	return AI_MODEL_CAPABILITY_OPTIONS
		.map(item => item.code)
		.filter(code => selected.has(code))
}

function normalizeTags(tagsText, errors) {
	const values = String(tagsText || '')
		.split(/[,\n]/)
		.map(value => value.trim())
		.filter(Boolean)
	const tags = [...new Set(values)]
	if (tags.length > 20 || tags.some(tag => tag.length > 64)) {
		errors.tagsText = '标签最多 20 个，每个标签不超过 64 个字符。'
	}
	return tags
}

function normalizeRequired(value, field, maxLength, errors) {
	const normalized = String(value || '').trim().toLowerCase()
	if (!normalized || normalized.length > maxLength) {
		errors[field] = `该字段不能为空，且不能超过 ${maxLength} 个字符。`
	}
	return normalized
}

function normalizeOptional(value, field, maxLength, errors) {
	const normalized = String(value || '').trim()
	if (normalized.length > maxLength) {
		errors[field] = `该字段不能超过 ${maxLength} 个字符。`
	}
	return normalized
}

function normalizeRatio(value, field, errors) {
	const normalized = String(value ?? '').trim()
	if (!RATIO_PATTERN.test(normalized)) {
		errors[field] = '倍率必须是非负数，整数最多 12 位，小数最多 8 位。'
	}
	return normalized
}

function normalizeTokenLimit(value, field, errors) {
	const normalized = String(value ?? '')
	if (!POSITIVE_INTEGER_PATTERN.test(normalized)) {
		errors[field] = '请输入不带小数点的正整数 K 值。'
		return null
	}
	const parsed = Number(normalized)
	if (!Number.isSafeInteger(parsed) || parsed > MAX_MODEL_TOKEN_LIMIT_K) {
		errors[field] = `K 值不能超过 ${MAX_MODEL_TOKEN_LIMIT_K}。`
		return null
	}
	return parsed
}

export function validateAiModelForm(form) {
	const errors = {}
	const modelName = normalizeRequired(form?.modelName, 'modelName', 128, errors)
	const vendor = normalizeRequired(form?.vendor, 'vendor', 128, errors)
	const description = normalizeOptional(form?.description, 'description', 4000, errors)
	const iconPublicId = String(form?.iconPublicId || '').trim()
	if (iconPublicId && !PUBLIC_ID_PATTERN.test(iconPublicId)) {
		errors.iconPublicId = '请选择有效的模型图标资源。'
	}
	const tags = normalizeTags(form?.tagsText, errors)
	const inputRatio = normalizeRatio(form?.inputRatio, 'inputRatio', errors)
	const cachedInputRatio =
		normalizeRatio(form?.cachedInputRatio, 'cachedInputRatio', errors)
	const outputRatio = normalizeRatio(form?.outputRatio, 'outputRatio', errors)
	const contextWindowK = normalizeTokenLimit(
		form?.contextWindowK,
		'contextWindowK',
		errors)
	const maxOutputK = normalizeTokenLimit(form?.maxOutputK, 'maxOutputK', errors)
	if (contextWindowK !== null && maxOutputK !== null && maxOutputK > contextWindowK) {
		errors.maxOutputK = '单次最大输出不能超过最大上下文窗口。'
	}
	const rawCapabilities = Array.isArray(form?.capabilities) ? form.capabilities : []
	const capabilities = orderedCapabilities(rawCapabilities)
	if (capabilities.length < 1
		|| rawCapabilities.length !== new Set(rawCapabilities).size
		|| rawCapabilities.some(code => !CAPABILITY_CODES.has(code))) {
		errors.capabilities = '请至少选择一项受支持的模型能力。'
	}
	return {
		valid: Object.keys(errors).length === 0,
		errors,
		command: {
			modelName,
			description,
			iconPublicId: iconPublicId || null,
			tags,
			vendor,
			inputRatio,
			cachedInputRatio,
			outputRatio,
			contextWindowK,
			maxOutputK,
			capabilities
		}
	}
}

function sameArray(left, right) {
	return left.length === right.length && left.every((value, index) => value === right[index])
}

export function createMergePatch(snapshot, draft) {
	const initial = validateAiModelForm(snapshot)
	const current = validateAiModelForm(draft)
	if (!current.valid) return {}
	const patch = {}
	for (const field of [
		'modelName',
		'vendor',
		'inputRatio',
		'cachedInputRatio',
		'outputRatio',
		'contextWindowK',
		'maxOutputK'
	]) {
		if (initial.command[field] !== current.command[field]) patch[field] = current.command[field]
	}
	for (const field of ['description']) {
		if (initial.command[field] !== current.command[field]) {
			patch[field] = current.command[field] || null
		}
	}
	if (initial.command.iconPublicId !== current.command.iconPublicId) {
		patch.iconPublicId = current.command.iconPublicId
	}
	if (!sameArray(initial.command.tags, current.command.tags)) {
		patch.tags = current.command.tags
	}
	if (!sameArray(initial.command.capabilities, current.command.capabilities)) {
		patch.capabilities = current.command.capabilities
	}
	return patch
}

export function aiModelFormChanged(snapshot, draft) {
	return JSON.stringify(cloneAiModelForm(snapshot)) !== JSON.stringify(cloneAiModelForm(draft))
}
