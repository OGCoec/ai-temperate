local values = redis.call('HMGET', KEYS[1], 'flowId', 'provider', 'expiresAt')
if not values[1] then return {1} end
if values[2] ~= ARGV[1] then return {3} end
if tonumber(ARGV[2]) >= tonumber(values[3]) then
    redis.call('UNLINK', KEYS[1])
    return {2}
end
redis.call('UNLINK', KEYS[1])
return {0, values[1]}
