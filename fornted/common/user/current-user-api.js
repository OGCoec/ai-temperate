import { authorizedRequest } from '../auth/http-client.js'
import {
	createAuthUiPreviewProfile,
	isAuthUiPreviewEnabled
} from '../auth/ui-preview-session.js'

export const currentUserApi = {
	me() {
		if (isAuthUiPreviewEnabled()) return Promise.resolve(createAuthUiPreviewProfile())
		return authorizedRequest('/api/users/me', { method: 'GET' })
	}
}
