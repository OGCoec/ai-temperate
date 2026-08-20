if redis.call('EXISTS', KEYS[1]) == 1 then return 1 end
redis.call('HSET', KEYS[1], 'flowId', ARGV[1], 'provider', ARGV[2], 'expiresAt', ARGV[3])
redis.call('PEXPIREAT', KEYS[1], ARGV[3])
return 0
