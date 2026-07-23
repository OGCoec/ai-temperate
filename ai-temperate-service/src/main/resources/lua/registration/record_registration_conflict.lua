local WINDOW_MILLIS = 300000
local BLOCK_SECONDS = 7200
local ALLOWED_FAILURES = 5

if redis.call('EXISTS', KEYS[2]) == 1 then
    return 1
end
if redis.call('EXISTS', KEYS[3]) == 1 then
    return 1
end

local now = tonumber(ARGV[1])
local windowMillis = tonumber(ARGV[2])
local blockSeconds = tonumber(ARGV[3])
if now == nil or windowMillis ~= WINDOW_MILLIS or blockSeconds ~= BLOCK_SECONDS then
    return redis.error_reply('invalid conflict boundaries')
end

if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('HSET', KEYS[1], 'createdAt', ARGV[1], 'total', '0',
            'phone', '0', 'email', '0')
    redis.call('PEXPIRE', KEYS[1], windowMillis)
end

local count = redis.call('HINCRBY', KEYS[1], 'total', 1)
if ARGV[4] == '1' then
    redis.call('HINCRBY', KEYS[1], 'phone', 1)
end
if ARGV[5] == '1' then
    redis.call('HINCRBY', KEYS[1], 'email', 1)
end
if count > ALLOWED_FAILURES then
    redis.call('SET', KEYS[2], '1', 'EX', blockSeconds)
    redis.call('SET', KEYS[3], '1', 'EX', blockSeconds)
    redis.call('UNLINK', KEYS[1])
    return 1
end

return 0
