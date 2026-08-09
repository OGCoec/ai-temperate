export function resolveEsmSpecifier(specifier) {
	return import.meta.resolve(specifier)
}
