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
return 0
