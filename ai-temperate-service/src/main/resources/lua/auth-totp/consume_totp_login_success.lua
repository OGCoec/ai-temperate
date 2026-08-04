if redis.call('EXISTS', KEYS[1]) == 0 then
    return 1
end

if redis.call('HGET', KEYS[1], 'deviceHash') ~= ARGV[1] then
    return 2
end

local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAt') or '0')
if expiresAt <= tonumber(ARGV[2]) then
    redis.call('DEL', KEYS[1])
    return 1
end

local claimed = redis.call('SET', KEYS[2], '1', 'NX', 'PX', ARGV[3])
if not claimed then
    return 4
end

redis.call('DEL', KEYS[1])
return 0
