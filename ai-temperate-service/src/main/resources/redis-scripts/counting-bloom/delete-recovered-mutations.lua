if redis.call('GET', KEYS[1]) ~= ARGV[1]
        or redis.call('HGET', KEYS[2], 'build_fence') ~= ARGV[2] then
    return -4
end
if #ARGV > 2 then
    redis.call('HDEL', KEYS[3], unpack(ARGV, 3, #ARGV))
end
local remaining = redis.call('HLEN', KEYS[3])
redis.call('HSET', KEYS[2], 'pending_mutations', tostring(remaining))
return remaining
