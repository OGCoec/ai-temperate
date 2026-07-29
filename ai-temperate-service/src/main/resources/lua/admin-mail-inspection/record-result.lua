if redis.call('EXISTS', KEYS[1]) == 0 then
    return {-1, 0, 0}
end
local status = redis.call('HGET', KEYS[1], 'status')
if status == 'COMPLETED' or status == 'FAILED' or status == 'ABANDONED' then
    return {0, tonumber(redis.call('GET', KEYS[4]) or '0'), 0}
end
if redis.call('HSETNX', KEYS[3], ARGV[1], ARGV[2]) == 0 then
    return {0, tonumber(redis.call('GET', KEYS[4]) or '0'), 0}
end
if redis.call('HDEL', KEYS[2], 'inflight:' .. ARGV[1]) == 1 then
    local running = tonumber(redis.call('HGET', KEYS[2], 'runningCount') or '0')
    if running > 0 then
        redis.call('HINCRBY', KEYS[2], 'runningCount', -1)
    end
else
    local queued = tonumber(redis.call('HGET', KEYS[2], 'queuedCount') or '0')
    if queued > 0 then
        redis.call('HINCRBY', KEYS[2], 'queuedCount', -1)
        redis.call('HINCRBY', KEYS[2], 'dispatchFailedCount', 1)
    end
end
local processed = redis.call('HINCRBY', KEYS[2], 'processedCount', 1)
redis.call('HINCRBY', KEYS[2], 'status:' .. ARGV[3], 1)
local revision = redis.call('INCR', KEYS[4])
local completionTarget = tonumber(redis.call('HGET', KEYS[1], 'completionTarget') or '0')
local expiry = redis.call('HGET', KEYS[1], 'expiresAt') or ARGV[5]
local terminal = 0
if processed >= completionTarget then
    terminal = 1
    expiry = ARGV[6]
    redis.call('HSET', KEYS[1],
        'status', 'COMPLETED',
        'resumeRequired', 'false',
        'completedAt', ARGV[4],
        'expiresAt', expiry)
    redis.call('HSET', KEYS[2], 'queuedCount', 0, 'runningCount', 0)
    local jobHash = redis.call('HGET', KEYS[1], 'jobHash')
    if redis.call('GET', KEYS[5]) == jobHash then
        redis.call('DEL', KEYS[5])
    end
else
    redis.call('PEXPIREAT', KEYS[3], expiry)
end
if terminal == 1 then
    for index = 1, #KEYS do
        if redis.call('EXISTS', KEYS[index]) == 1 then
            redis.call('PEXPIREAT', KEYS[index], expiry)
        end
    end
end
return {1, revision, terminal}
