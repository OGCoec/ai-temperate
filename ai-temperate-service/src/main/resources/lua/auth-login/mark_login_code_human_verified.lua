local deviceHash = ARGV[1]
local challengeHash = ARGV[2]
local now = tonumber(ARGV[3])
local idleTtl = tonumber(ARGV[4])

local values = redis.call('HMGET', KEYS[1],
        'strategyType', 'identifier', 'userId', 'deviceHash', 'challengeHash',
        'humanVerified', 'createdAt', 'expiresAt', 'absoluteExpiresAt')
if not values[1] then return {1} end
if values[4] ~= deviceHash or values[5] ~= challengeHash then return {3} end
if now >= tonumber(values[8]) or now >= tonumber(values[9]) then return {2} end
if values[6] == '1' or redis.call('GET', KEYS[2]) ~= challengeHash then return {4} end
redis.call('UNLINK', KEYS[2])
redis.call('HSET', KEYS[1], 'humanVerified', '1')
local renewedExpiresAt = math.min(now + idleTtl, tonumber(values[9]))
redis.call('HSET', KEYS[1], 'expiresAt', renewedExpiresAt)
redis.call('PEXPIRE', KEYS[1], renewedExpiresAt - now)
return {0, values[1], values[2], values[3], '1', values[7],
        tostring(renewedExpiresAt), values[9]}
