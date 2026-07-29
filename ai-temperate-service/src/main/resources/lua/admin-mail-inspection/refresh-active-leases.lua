if redis.call('EXISTS', KEYS[1]) == 0 then
    return {-1, 0}
end
local status = redis.call('HGET', KEYS[1], 'status')
if status == 'COMPLETED' or status == 'FAILED' or status == 'ABANDONED' then
    return {0, tonumber(redis.call('GET', KEYS[2]) or '0')}
end
redis.call('HSET', KEYS[1], 'expiresAt', ARGV[1])
local revision = redis.call('INCR', KEYS[2])
for index = 1, #KEYS do
    redis.call('PEXPIREAT', KEYS[index], ARGV[1])
end
return {1, revision}
