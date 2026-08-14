if redis.call('GET', KEYS[1]) ~= ARGV[1]
        or redis.call('HGET', KEYS[2], 'build_fence') ~= ARGV[2] then
    return -4
end
redis.call('SET', KEYS[3], '')
redis.call('SETRANGE', KEYS[3], tonumber(ARGV[3]) - 1, string.char(0))
return redis.call('STRLEN', KEYS[3])
