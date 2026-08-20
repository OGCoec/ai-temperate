local createdAt = tonumber(ARGV[1])
local idleTtl = tonumber(ARGV[2])
local absoluteTtl = tonumber(ARGV[3])
if redis.call('EXISTS', KEYS[1]) == 1 then return 1 end
local expiresAt = createdAt + idleTtl
local absoluteExpiresAt = createdAt + absoluteTtl
redis.call('HSET', KEYS[1],
        'schemaVersion', '1',
        'provider', ARGV[4],
        'platform', ARGV[5],
        'interactionMode', ARGV[6],
        'deviceHash', ARGV[7],
        'ipHash', ARGV[8],
        'nonceHash', ARGV[9],
        'nativeConsumed', '0',
        'state', 'PROVIDER_PENDING',
        'existingIdentityId', '0',
        'phoneRequired', '0',
        'phoneVerified', '0',
        'createdAt', createdAt,
        'expiresAt', expiresAt,
        'absoluteExpiresAt', absoluteExpiresAt)
redis.call('PEXPIRE', KEYS[1], idleTtl)
return 0
