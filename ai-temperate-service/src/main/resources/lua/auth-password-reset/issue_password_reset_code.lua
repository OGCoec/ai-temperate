if redis.call('EXISTS', KEYS[5]) == 1 or redis.call('EXISTS', KEYS[6]) == 1 then return 7 end
local deviceHash = ARGV[1]
local challengeHash = ARGV[2]
local now = tonumber(ARGV[3])
local digest = ARGV[4]
local operationId = ARGV[5]
local codeTtl = tonumber(ARGV[6])
local cooldown = tonumber(ARGV[7])
local maxSends = tonumber(ARGV[8])
local window = tonumber(ARGV[9])
local blockMillis = tonumber(ARGV[10])

local flow = redis.call('HMGET', KEYS[1],
        'deviceHash', 'challengeHash', 'humanVerified', 'expiresAt', 'absoluteExpiresAt')
if not flow[1] then return 1 end
if flow[1] ~= deviceHash or flow[2] ~= challengeHash then return 3 end
if now >= tonumber(flow[4]) or now >= tonumber(flow[5]) then return 2 end
if flow[3] ~= '1' then return 4 end

local deviceLast = tonumber(redis.call('HGET', KEYS[3], 'lastIssuedAt') or '0')
local targetLast = tonumber(redis.call('HGET', KEYS[4], 'lastIssuedAt') or '0')
if now - deviceLast < cooldown or now - targetLast < cooldown then return 5 end

local function reserve(key)
    if redis.call('EXISTS', key) == 0 then
        redis.call('HSET', key, 'count', '0', 'createdAt', now)
        redis.call('PEXPIRE', key, window)
    end
    local count = redis.call('HINCRBY', key, 'count', 1)
    redis.call('HSET', key, 'lastIssuedAt', now, 'lastOperationId', operationId)
    return count
end
local deviceCount = reserve(KEYS[3])
local targetCount = reserve(KEYS[4])
if deviceCount > maxSends then
    redis.call('PSETEX', KEYS[5], blockMillis, '1')
    redis.call('PSETEX', KEYS[6], blockMillis, '1')
    redis.call('UNLINK', KEYS[3])
    return 7
end
if targetCount > maxSends then return 6 end

redis.call('HSET', KEYS[2],
        'digest', digest,
        'operationId', operationId,
        'deliveryStatus', 'PENDING',
        'attempts', '0')
redis.call('PEXPIRE', KEYS[2], math.min(codeTtl, tonumber(flow[4]) - now))
return 0
