if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end

redis.call('HSET', KEYS[1], 'generation', ARGV[1])
redis.call('PEXPIREAT', KEYS[1], ARGV[2])
return 1
