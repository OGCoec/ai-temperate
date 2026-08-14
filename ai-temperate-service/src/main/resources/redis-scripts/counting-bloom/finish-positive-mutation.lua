if ARGV[2] ~= '1' then
    redis.call('HSET', KEYS[1], 'mutation_resume_active', '0')
    local pendingFailed = redis.call('HLEN', KEYS[2])
    redis.call('HSET', KEYS[1],
            'state', 'DEGRADED',
            'reason', 'positive_mutation_incomplete',
            'pending_mutations', pendingFailed)
    return pendingFailed
end
redis.call('HDEL', KEYS[2], ARGV[1])
local pending = redis.call('HLEN', KEYS[2])
redis.call('HSET', KEYS[1], 'pending_mutations', pending)
if pending == 0
        and ARGV[2] == '1'
        and redis.call('HGET', KEYS[1], 'verified') == '1'
        and redis.call('HGET', KEYS[1], 'mutation_resume_active') == '1' then
    redis.call('HSET', KEYS[1], 'state', 'ACTIVE')
    redis.call('HDEL', KEYS[1], 'reason')
end
if pending == 0 then
    redis.call('HDEL', KEYS[1], 'mutation_resume_active')
end
return pending
