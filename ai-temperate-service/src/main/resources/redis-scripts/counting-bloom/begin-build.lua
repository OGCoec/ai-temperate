if redis.call('GET', KEYS[1]) ~= ARGV[1] then
    return -4
end
local pending = redis.call('HLEN', KEYS[3])
redis.call('HSET', KEYS[2],
        'state', 'BUILDING',
        'capacity', ARGV[3],
        'hash_count', ARGV[4],
        'counter_bytes', ARGV[5],
        'counters_per_bucket', ARGV[6],
        'bucket_count', ARGV[7],
        'element_count', '0',
        'verified', '0',
        'pending_mutations', tostring(pending),
        'build_fence', ARGV[2])
redis.call('HDEL', KEYS[2], 'reason', 'mutation_resume_active')
return 1
