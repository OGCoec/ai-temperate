if redis.call('GET', KEYS[1]) ~= ARGV[1]
        or redis.call('HGET', KEYS[2], 'build_fence') ~= ARGV[2] then
    return -4
end
if redis.call('HGET', KEYS[2], 'state') ~= 'READY'
        or redis.call('HGET', KEYS[2], 'verified') ~= '1'
        or redis.call('HLEN', KEYS[3]) ~= 0 then
    return 0
end
redis.call('HSET', KEYS[2], 'state', 'ACTIVE', 'pending_mutations', '0')
redis.call('HDEL', KEYS[2], 'reason', 'mutation_resume_active', 'build_fence')
return 1
