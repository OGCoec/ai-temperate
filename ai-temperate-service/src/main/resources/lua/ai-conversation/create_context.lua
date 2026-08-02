if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end

redis.call('HSET', KEYS[1], 'generation', ARGV[1])
for index = 3, #ARGV, 2 do
    redis.call('HSET', KEYS[1], ARGV[index], ARGV[index + 1])
end
redis.call('PEXPIREAT', KEYS[1], ARGV[2])
return 1
