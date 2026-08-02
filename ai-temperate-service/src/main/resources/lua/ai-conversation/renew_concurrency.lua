if redis.call('ZSCORE', KEYS[1], ARGV[1]) == false
        or redis.call('ZSCORE', KEYS[2], ARGV[1]) == false then
    return 0
end

redis.call('ZADD', KEYS[1], 'XX', ARGV[2], ARGV[1])
redis.call('ZADD', KEYS[2], 'XX', ARGV[2], ARGV[1])
redis.call('PEXPIREAT', KEYS[1], ARGV[3])
redis.call('PEXPIREAT', KEYS[2], ARGV[3])
return 1
