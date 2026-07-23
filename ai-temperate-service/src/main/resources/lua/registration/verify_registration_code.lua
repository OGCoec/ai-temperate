local ATTEMPT_LIMIT = 5
local attemptLimit = tonumber(ARGV[7])
if attemptLimit ~= ATTEMPT_LIMIT then
    return redis.error_reply('invalid verification attempt boundary')
end

local function snapshot()
    local values = redis.call('HMGET', KEYS[1],
            'email', 'phone', 'humanVerified', 'emailVerified', 'phoneVerified',
            'createdAt', 'expiresAt', 'absoluteExpiresAt')
    local completing = '0'
    if redis.call('HEXISTS', KEYS[1], 'completionClaim') == 1 then
        completing = '1'
    end
    return {0, values[1], values[2], values[3], values[4], values[5],
            completing, values[6], values[7], values[8]}
end

if redis.call('EXISTS', KEYS[1]) == 0 then
    return {1}
end

local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAt'))
local now = tonumber(ARGV[5])
if expiresAt == nil or now == nil or now >= expiresAt then
    return {2}
end

local access = redis.call('HMGET', KEYS[1],
        'flowCsrfHash', 'deviceHash', 'ipHash', 'challengeHash')
if access[1] ~= ARGV[1]
        or access[2] ~= ARGV[2]
        or access[3] ~= ARGV[3]
        or access[4] ~= ARGV[4] then
    return {3}
end

if redis.call('HGET', KEYS[1], 'humanVerified') ~= '1' then
    return {4}
end

local stored = redis.call('HMGET', KEYS[2], 'digest', 'deliveryStatus')
if stored[1] == false or (stored[2] ~= 'SUCCESS' and stored[2] ~= 'UNKNOWN') then
    return {5}
end
local storedDigest = stored[1]

if storedDigest ~= ARGV[6] then
    local attempts = redis.call('HINCRBY', KEYS[2], 'attempts', 1)
    if attempts >= attemptLimit then
        redis.call('DEL', KEYS[2])
        return {7}
    end
    return {6}
end

redis.call('DEL', KEYS[2])
local verifiedField = ARGV[8]
redis.call('HSET', KEYS[1], verifiedField, '1')
return snapshot()
