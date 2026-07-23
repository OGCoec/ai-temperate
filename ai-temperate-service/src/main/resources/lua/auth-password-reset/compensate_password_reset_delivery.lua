local operationId = ARGV[1]
if redis.call('HGET', KEYS[2], 'operationId') ~= operationId
        or (redis.call('HGET', KEYS[2], 'deliveryStatus') ~= 'PENDING'
        and redis.call('HGET', KEYS[2], 'deliveryStatus') ~= 'DELIVERING') then
    return 0
end
redis.call('UNLINK', KEYS[2])
for index = 3, 4 do
    if redis.call('HGET', KEYS[index], 'lastOperationId') == operationId then
        local count = tonumber(redis.call('HGET', KEYS[index], 'count') or '0')
        if count > 0 then redis.call('HINCRBY', KEYS[index], 'count', -1) end
        redis.call('HDEL', KEYS[index], 'lastIssuedAt', 'lastOperationId')
    end
end
return 1
