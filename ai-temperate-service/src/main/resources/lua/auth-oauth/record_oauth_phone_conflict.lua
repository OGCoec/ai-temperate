local now = tonumber(ARGV[1])
local windowMillis = tonumber(ARGV[2])
local maxConflicts = tonumber(ARGV[3])
local blockSeconds = tonumber(ARGV[4])
if redis.call('EXISTS', KEYS[2]) == 1 or redis.call('EXISTS', KEYS[3]) == 1 then return 2 end
local values = redis.call('HMGET', KEYS[1], 'windowStartedAt', 'conflictCount')
local windowStartedAt = tonumber(values[1] or now)
local conflictCount = tonumber(values[2] or '0')
if now - windowStartedAt >= windowMillis then
    windowStartedAt = now
    conflictCount = 0
end
conflictCount = conflictCount + 1
if conflictCount > maxConflicts then
    redis.call('SET', KEYS[2], '1', 'EX', blockSeconds)
    redis.call('SET', KEYS[3], '1', 'EX', blockSeconds)
    return 2
end
redis.call('HSET', KEYS[1], 'windowStartedAt', windowStartedAt, 'conflictCount', conflictCount)
redis.call('PEXPIRE', KEYS[1], windowMillis)
return 0
