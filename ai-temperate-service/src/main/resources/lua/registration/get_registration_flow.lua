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
local absoluteExpiresAt = tonumber(redis.call('HGET', KEYS[1], 'absoluteExpiresAt'))
local now = tonumber(ARGV[5])
if expiresAt == nil or absoluteExpiresAt == nil or now == nil
        or now >= expiresAt or now >= absoluteExpiresAt then
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

local idleTtlMillis = tonumber(ARGV[6])
if idleTtlMillis == nil or idleTtlMillis ~= 600000 then
    return redis.error_reply('invalid registration idle ttl')
end
local renewedExpiresAt = math.min(now + idleTtlMillis, absoluteExpiresAt)
local remaining = renewedExpiresAt - now
redis.call('HSET', KEYS[1], 'expiresAt', renewedExpiresAt)
redis.call('PEXPIRE', KEYS[1], remaining)
if redis.call('EXISTS', KEYS[2]) == 1 then
    redis.call('PEXPIRE', KEYS[2], remaining)
end

return snapshot()
