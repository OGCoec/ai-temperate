if redis.call('EXISTS', KEYS[1]) == 0 then
    return {-1, 0}
end
local current = redis.call('HGET', KEYS[1], 'status')
local allowed = false
for value in string.gmatch(ARGV[1], '([^,]+)') do
    if value == current then
        allowed = true
        break
    end
end
if not allowed then
    return {0, tonumber(redis.call('GET', KEYS[3]) or '0')}
end
redis.call('HSET', KEYS[1],
    'status', ARGV[2],
    'resumeRequired', ARGV[5],
    'expiresAt', ARGV[4])
if ARGV[2] == 'RUNNING' and redis.call('HGET', KEYS[1], 'startedAt') == '' then
    redis.call('HSET', KEYS[1], 'startedAt', ARGV[3])
end
local revision = redis.call('INCR', KEYS[3])
for index = 1, #KEYS do
    redis.call('PEXPIREAT', KEYS[index], ARGV[4])
end
return {1, revision}
