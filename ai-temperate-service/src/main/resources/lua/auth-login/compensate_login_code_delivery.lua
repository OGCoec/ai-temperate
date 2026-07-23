local operationId = ARGV[1]
if redis.call('HGET', KEYS[2], 'operationId') ~= operationId
        or (redis.call('HGET', KEYS[2], 'deliveryStatus') ~= 'PENDING'
        and redis.call('HGET', KEYS[2], 'deliveryStatus') ~= 'DELIVERING') then
    return 0
end
redis.call('UNLINK', KEYS[2])
redis.call('HDEL', KEYS[1], 'lastIssuedAt')
return 1
