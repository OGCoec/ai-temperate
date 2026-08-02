local revision = redis.call('HINCRBY', KEYS[1], 'revision', 1)
redis.call('HSET', KEYS[1], 'delta:' .. string.format('%020d', revision), ARGV[1])
redis.call('PEXPIRE', KEYS[1], ARGV[2])
return revision
