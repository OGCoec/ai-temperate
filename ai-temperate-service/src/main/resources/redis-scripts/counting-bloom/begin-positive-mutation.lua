if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end
local pendingBefore = redis.call('HLEN', KEYS[2])
if pendingBefore == 0 then
    local previousState = redis.call('HGET', KEYS[1], 'state')
    redis.call('HSET', KEYS[1], 'mutation_resume_active',
            previousState == 'ACTIVE' and '1' or '0')
end
redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
redis.call('HSET', KEYS[1], 'state', 'DEGRADED', 'reason', 'positive_mutation_pending')
redis.call('HSET', KEYS[1], 'pending_mutations', redis.call('HLEN', KEYS[2]))
return 1
