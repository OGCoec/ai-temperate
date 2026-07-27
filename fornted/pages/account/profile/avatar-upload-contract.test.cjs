const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const projectRoot = path.resolve(__dirname, '../../..')

test('avatar upload uses raw PUT and never sends object keys to application APIs', () => {
  const source = fs.readFileSync(
    path.join(projectRoot, 'common/user/avatar-upload.js'),
    'utf8'
  )
  assert.match(source, /method:\s*'PUT'/)
  assert.match(source, /ArrayBuffer/)
  assert.doesNotMatch(source, /multipart|formData/i)

  const api = fs.readFileSync(
    path.join(projectRoot, 'common/user/current-user-api.js'),
    'utf8'
  )
  assert.match(api, /avatar\/preuploads/)
  assert.doesNotMatch(api, /objectKey|bucket|avatarUrl\s*:/)
})

test('confirmed avatar is written only to the in-memory profile vault', () => {
  const source = fs.readFileSync(
    path.join(projectRoot, 'common/user/current-user-profile.js'),
    'utf8'
  )
  assert.match(source, /updateCurrentUserAvatar/)
  assert.match(source, /writeProfileVault/)
  assert.doesNotMatch(source, /refreshToken|localStorage|setStorage/)
})
