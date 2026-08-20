local values = redis.call('HMGET', KEYS[1],
        'flowId', 'bindingHash', 'provider', 'platform', 'codeVerifier', 'nonceHash',
        'redirectUri', 'expiresAt')
if not values[1] then return {1} end
if values[2] ~= ARGV[1] or values[3] ~= ARGV[2] then return {3} end
if tonumber(ARGV[3]) >= tonumber(values[8]) then
    redis.call('UNLINK', KEYS[1])
    return {2}
end
redis.call('UNLINK', KEYS[1])
return {0, values[1], values[3], values[4], values[5], values[6] or '', values[7]}
