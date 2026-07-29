if redis.call('EXISTS', KEYS[1]) == 0 then
    return {-1, 0}
end
local status = redis.call('HGET', KEYS[1], 'status')
if status == 'COMPLETED' or status == 'FAILED' or status == 'ABANDONED' then
    return {0, tonumber(redis.call('GET', KEYS[3]) or '0')}
end
local field = ARGV[1] .. ':' .. ARGV[2]
if redis.call('HSETNX', KEYS[2], field, '1') == 0 then
    return {0, tonumber(redis.call('GET', KEYS[3]) or '0')}
end
redis.call('HINCRBY', KEYS[2], ARGV[1] .. 'SubmissionChunkCount', 1)
redis.call('HSET', KEYS[1], 'submissionExpiresAt', ARGV[4], 'expiresAt', ARGV[5])
local revision = redis.call('INCR', KEYS[3])
for index = 1, #KEYS do
    redis.call('PEXPIREAT', KEYS[index], ARGV[5])
end
return {1, revision}
