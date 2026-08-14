if redis.call('GET', KEYS[1]) ~= ARGV[1]
        or redis.call('HGET', KEYS[2], 'build_fence') ~= ARGV[2] then
    return -4
end
local count = tonumber(redis.call('HGET', KEYS[2], 'element_count') or '-1')
if count < tonumber(ARGV[3]) or count > tonumber(ARGV[4]) then
    redis.call('HSET', KEYS[2],
            'state', 'DEGRADED',
            'reason', 'element_count_mismatch')
    return 0
end
redis.call('HSET', KEYS[2], 'state', 'READY', 'verified', '1')
return 1
