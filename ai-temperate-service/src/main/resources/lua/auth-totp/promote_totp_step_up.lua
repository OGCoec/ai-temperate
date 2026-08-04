if redis.call('EXISTS', KEYS[1]) == 0 then
    return 1
end
local values = redis.call('HMGET', KEYS[1], 'userId', 'deviceHash', 'action', 'expiresAt')
if values[1] ~= ARGV[1] or values[2] ~= ARGV[2] or values[3] ~= ARGV[3] then
    return 2
end
if tonumber(values[4] or '0') <= tonumber(ARGV[4]) then
    redis.call('UNLINK', KEYS[1])
    return 1
end
if redis.call('EXISTS', KEYS[2]) == 1 then
    return 3
end
redis.call('UNLINK', KEYS[1])
redis.call('HSET', KEYS[2],
    'schemaVersion', '1',
    'userId', ARGV[1],
    'deviceHash', ARGV[2],
    'action', ARGV[3],
    'failedAttempts', '0',
    'createdAt', ARGV[4],
    'expiresAt', ARGV[5])
redis.call('PEXPIRE', KEYS[2], ARGV[6])
return 0
