local now = tonumber(ARGV[1])
local windowMillis = tonumber(ARGV[2])
local cooldownMillis = tonumber(ARGV[3])
local maxRequests = tonumber(ARGV[4])
local blockSeconds = tonumber(ARGV[5])
if redis.call('EXISTS', KEYS[2]) == 1 or redis.call('EXISTS', KEYS[3]) == 1 then return 2 end
local values = redis.call('HMGET', KEYS[1], 'windowStartedAt', 'requestCount', 'lastRequestedAt')
local windowStartedAt = tonumber(values[1] or now)
local requestCount = tonumber(values[2] or '0')
local lastRequestedAt = tonumber(values[3] or '0')
if now - windowStartedAt >= windowMillis then
    windowStartedAt = now
    requestCount = 0
    lastRequestedAt = 0
end
if lastRequestedAt > 0 and now - lastRequestedAt < cooldownMillis then
    redis.call('SET', KEYS[2], '1', 'EX', blockSeconds)
    redis.call('SET', KEYS[3], '1', 'EX', blockSeconds)
    return 2
end
if requestCount >= maxRequests then
    redis.call('SET', KEYS[2], '1', 'EX', blockSeconds)
    redis.call('SET', KEYS[3], '1', 'EX', blockSeconds)
    return 2
end
redis.call('HSET', KEYS[1],
        'windowStartedAt', windowStartedAt,
        'requestCount', requestCount + 1,
        'lastRequestedAt', now)
redis.call('PEXPIRE', KEYS[1], windowMillis)
return 0
