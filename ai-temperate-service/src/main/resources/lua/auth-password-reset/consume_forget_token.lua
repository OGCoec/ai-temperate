if redis.call('HGET', KEYS[1], 'claimed') ~= ARGV[1] then return 0 end
redis.call('UNLINK', KEYS[1])
return 1
