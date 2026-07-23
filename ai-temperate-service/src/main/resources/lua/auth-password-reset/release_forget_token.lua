if redis.call('HGET', KEYS[1], 'claimed') ~= ARGV[1] then return 0 end
redis.call('HSET', KEYS[1], 'claimed', '0')
return 1
