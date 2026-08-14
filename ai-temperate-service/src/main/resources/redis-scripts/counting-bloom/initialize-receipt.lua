if redis.call('GET', KEYS[1]) ~= ARGV[1]
        or redis.call('HGET', KEYS[2], 'build_fence') ~= ARGV[2] then
    return -4
end
return redis.call('SADD', KEYS[3], '__ait_counting_bloom_receipt__')
