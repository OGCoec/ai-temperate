if redis.call('EXISTS', KEYS[1]) == 0 then
    return {1}
end

if redis.call('HGET', KEYS[1], 'deviceHash') ~= ARGV[1] then
    return {2}
end

local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAt') or '0')
if expiresAt <= tonumber(ARGV[2]) then
    redis.call('DEL', KEYS[1])
    return {1}
end

return {
    0,
    redis.call('HGET', KEYS[1], 'userId'),
    redis.call('HGET', KEYS[1], 'failedAttempts'),
    tostring(expiresAt)
}
