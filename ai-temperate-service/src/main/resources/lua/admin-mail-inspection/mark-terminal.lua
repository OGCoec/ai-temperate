if redis.call('EXISTS', KEYS[1]) == 0 then
    return {-1, 0}
end
local current = redis.call('HGET', KEYS[1], 'status')
if current == 'COMPLETED' or current == 'FAILED' or current == 'ABANDONED' then
    return {0, tonumber(redis.call('GET', KEYS[3]) or '0')}
end
redis.call('HSET', KEYS[1],
    'status', ARGV[1],
    'resumeRequired', 'false',
    'completedAt', ARGV[2],
    'expiresAt', ARGV[3])
redis.call('HSET', KEYS[2], 'queuedCount', 0, 'runningCount', 0)
local jobHash = redis.call('HGET', KEYS[1], 'jobHash')
if redis.call('GET', KEYS[4]) == jobHash then
    redis.call('DEL', KEYS[4])
end
local revision = redis.call('INCR', KEYS[3])
for index = 1, #KEYS do
    if redis.call('EXISTS', KEYS[index]) == 1 then
        redis.call('PEXPIREAT', KEYS[index], ARGV[3])
    end
end
return {1, revision}
