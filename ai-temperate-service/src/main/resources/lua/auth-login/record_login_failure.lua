local windowMillis = tonumber(ARGV[1])
local maximumFailures = tonumber(ARGV[2])
local blockMillis = tonumber(ARGV[3])

if redis.call('EXISTS', KEYS[2]) == 1 or redis.call('EXISTS', KEYS[3]) == 1 then
    return 1
end

local failures = redis.call('INCR', KEYS[1])
if failures == 1 then
    redis.call('PEXPIRE', KEYS[1], windowMillis)
end
if failures > maximumFailures then
    redis.call('PSETEX', KEYS[2], blockMillis, '1')
    redis.call('PSETEX', KEYS[3], blockMillis, '1')
    redis.call('UNLINK', KEYS[1])
    return 1
end
return 0
