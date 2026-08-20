local values = redis.call('HMGET', KEYS[1],
        'provider', 'platform', 'interactionMode', 'state', 'deviceHash', 'ipHash',
        'providerSubject', 'verifiedEmail', 'proofType', 'existingIdentityId',
        'phoneRequired', 'lockedPhone', 'phoneVerified', 'createdAt', 'expiresAt',
        'absoluteExpiresAt')
if not values[1] then return {1} end
if values[5] ~= ARGV[1] or values[6] ~= ARGV[2] then return {3} end
local now = tonumber(ARGV[3])
local expiresAt = tonumber(values[15])
local absoluteExpiresAt = tonumber(values[16])
if not expiresAt or not absoluteExpiresAt or now >= expiresAt or now >= absoluteExpiresAt then
    redis.call('UNLINK', KEYS[1])
    return {2}
end
local renewedExpiresAt = math.min(now + tonumber(ARGV[4]), absoluteExpiresAt)
redis.call('HSET', KEYS[1], 'expiresAt', renewedExpiresAt)
redis.call('PEXPIRE', KEYS[1], renewedExpiresAt - now)
return {0, values[1], values[2], values[3], values[4], values[7] or '',
        values[8] or '', values[9] or '', values[10] or '0', values[11] or '0',
        values[12] or '', values[13] or '0', values[14], tostring(renewedExpiresAt),
        values[16]}
