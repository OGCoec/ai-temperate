local previous = redis.call('GET', KEYS[1])
redis.call('SET', KEYS[1], ARGV[1])
return previous or ''
