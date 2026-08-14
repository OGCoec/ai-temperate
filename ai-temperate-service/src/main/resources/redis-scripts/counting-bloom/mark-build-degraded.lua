if redis.call('GET', KEYS[1]) ~= ARGV[1]
        or redis.call('HGET', KEYS[2], 'build_fence') ~= ARGV[2] then
    return -4
end
redis.call('HSET', KEYS[2], 'state', 'DEGRADED', 'reason', ARGV[3])
return 1
