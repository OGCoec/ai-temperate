import { authorizedRequest } from '../auth/http-client.js'

export const currentUserApi = {
	me() {
		return authorizedRequest('/api/users/me', { method: 'GET' })
	},
	createAvatarPreupload(format, sizeBytes) {
		return authorizedRequest('/api/users/me/avatar/preuploads', {
			method: 'POST',
			data: { format, sizeBytes }
		})
	},
	cancelAvatarPreupload(preuploadId, format) {
		return authorizedRequest(
			`/api/users/me/avatar/preuploads/${encodeURIComponent(preuploadId)}?format=${encodeURIComponent(format)}`,
			{ method: 'DELETE' }
		)
	},
	confirmAvatar(preuploadId, format) {
		return authorizedRequest(
			`/api/users/me/avatar/preuploads/${encodeURIComponent(preuploadId)}/confirm`,
			{
				method: 'POST',
				data: { format }
			}
		)
	}
}
