local deviceHash = ARGV[1]
local challengeHash = ARGV[2]
local now = tonumber(ARGV[3])
local idleTtl = tonumber(ARGV[4])

local values = redis.call('HMGET', KEYS[1],
        'strategyType', 'purpose', 'identifier', 'userId', 'deviceHash', 'challengeHash',
        'humanVerified', 'createdAt', 'expiresAt', 'absoluteExpiresAt')
if not values[1] then return {1} end
if values[5] ~= deviceHash or values[6] ~= challengeHash then return {3} end
local expiresAt = tonumber(values[9])
local absoluteExpiresAt = tonumber(values[10])
if not expiresAt or not absoluteExpiresAt or now >= expiresAt or now >= absoluteExpiresAt then
    redis.call('UNLINK', KEYS[1], KEYS[2])
    return {2}
end
local renewedExpiresAt = math.min(now + idleTtl, absoluteExpiresAt)
local remaining = renewedExpiresAt - now
redis.call('HSET', KEYS[1], 'expiresAt', renewedExpiresAt)
redis.call('PEXPIRE', KEYS[1], remaining)
if redis.call('EXISTS', KEYS[2]) == 1 then redis.call('PEXPIRE', KEYS[2], remaining) end
return {0, values[1], values[2], values[3], values[4], values[7], values[8],
        tostring(renewedExpiresAt), values[10]}
