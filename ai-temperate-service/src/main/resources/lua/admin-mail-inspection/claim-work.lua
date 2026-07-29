if redis.call('EXISTS', KEYS[1]) == 0 then
    return {-1, 0}
end
local status = redis.call('HGET', KEYS[1], 'status')
if status == 'COMPLETED' or status == 'FAILED' or status == 'ABANDONED'
        or status == 'AWAITING_ADMIN_RESUME'
        or status == 'RECOVERY_FAILED' then
    return {0, tonumber(redis.call('GET', KEYS[4]) or '0')}
end
if redis.call('HEXISTS', KEYS[3], ARGV[1]) == 1 then
    return {0, tonumber(redis.call('GET', KEYS[4]) or '0')}
end
local inflightField = 'inflight:' .. ARGV[1]
local claimedAt = tonumber(redis.call('HGET', KEYS[2], inflightField) or '')
if claimedAt and claimedAt > tonumber(ARGV[3]) then
    return {0, tonumber(redis.call('GET', KEYS[4]) or '0')}
end
if claimedAt then
    redis.call('HSET', KEYS[2], inflightField, ARGV[2])
    local reclaimedRevision = redis.call('INCR', KEYS[4])
    return {1, reclaimedRevision}
end
redis.call('HSET', KEYS[2], inflightField, ARGV[2])
local queued = tonumber(redis.call('HGET', KEYS[2], 'queuedCount') or '0')
if queued > 0 then
    redis.call('HINCRBY', KEYS[2], 'queuedCount', -1)
end
redis.call('HINCRBY', KEYS[2], 'runningCount', 1)
if redis.call('HGET', KEYS[1], 'startedAt') == '' then
    redis.call('HSET', KEYS[1], 'startedAt', ARGV[2])
end
redis.call('HSET', KEYS[1], 'status', 'RUNNING', 'resumeRequired', 'false')
local revision = redis.call('INCR', KEYS[4])
return {1, revision}
