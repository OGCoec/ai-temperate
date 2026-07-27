local presentedDeviceHash = ARGV[1]
local presentedCsrfHash = ARGV[2]
local refreshTtlMillis = tonumber(ARGV[3])
local userIndexPrefix = ARGV[4]
local tokenHash = ARGV[5]
local preAuthScope = ARGV[6]
local preAuthDeviceDigest = ARGV[7]
local preAuthSessionType = ARGV[8]
local preAuthSessionRefDigest = ARGV[9]
local preAuthTtlMillis = tonumber(ARGV[10])
local promoteAnonymous = ARGV[11] == '1'

local values = redis.call('HMGET', KEYS[1],
        'userId', 'publicId', 'csrfHash', 'email', 'phone', 'deviceHash')
for index = 1, 6 do
    if not values[index] then
        return {1}
    end
end
if values[6] ~= presentedDeviceHash then
    return {2}
end
if values[3] ~= presentedCsrfHash then
    return {3}
end

local userIndexKey = userIndexPrefix .. values[1]
if redis.call('HEXISTS', userIndexKey, tokenHash) ~= 1 then
    return {4}
end

local preAuth = redis.call('HMGET', KEYS[2],
        'schemaVersion', 'scope', 'authState', 'sessionType',
        'sessionRefDigest', 'deviceDigest')
local alreadyBound = preAuth[3] == 'AUTHENTICATED'
        and preAuth[4] == preAuthSessionType
        and preAuth[5] == preAuthSessionRefDigest
local anonymousRecovery = promoteAnonymous
        and preAuth[3] == 'ANONYMOUS'
        and preAuth[4] == 'NONE'
        and (not preAuth[5] or preAuth[5] == '')
if preAuth[1] ~= '4'
        or preAuth[2] ~= preAuthScope
        or preAuth[6] ~= preAuthDeviceDigest
        or (not alreadyBound and not anonymousRecovery) then
    return {5}
end

local serverTime = redis.call('TIME')
local nowMillis = (tonumber(serverTime[1]) * 1000)
        + math.floor(tonumber(serverTime[2]) / 1000)
local refreshExpiresAt = nowMillis + refreshTtlMillis
local preAuthExpiresAt = nowMillis + preAuthTtlMillis

redis.call('PEXPIREAT', KEYS[1], refreshExpiresAt)
redis.call('HPEXPIREAT', userIndexKey, refreshExpiresAt, 'FIELDS', 1, tokenHash)
redis.call('PEXPIREAT', userIndexKey, refreshExpiresAt)
if anonymousRecovery then
    redis.call('HSET', KEYS[2],
            'authState', 'AUTHENTICATED',
            'sessionType', preAuthSessionType,
            'sessionRefDigest', preAuthSessionRefDigest)
end
redis.call('PEXPIREAT', KEYS[2], preAuthExpiresAt)

return {0, values[1], values[2], values[3], values[4], values[5], values[6],
        tostring(refreshExpiresAt)}
