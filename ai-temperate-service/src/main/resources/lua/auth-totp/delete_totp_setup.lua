if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end
if redis.call('HGET', KEYS[1], 'setupTokenHash') ~= ARGV[1] then
    return 0
end
redis.call('UNLINK', KEYS[1])
return 1
