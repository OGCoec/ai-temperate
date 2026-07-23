local operationId = ARGV[1]
if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('EXISTS', KEYS[2]) == 0 then
    return 0
end
local values = redis.call('HMGET', KEYS[2], 'operationId', 'deliveryStatus')
if values[1] ~= operationId then return 0 end
if values[2] == 'UNKNOWN' then return 1 end
if values[2] ~= 'PENDING' and values[2] ~= 'DELIVERING' then return 0 end
redis.call('HSET', KEYS[2],
        'deliveryStatus', 'UNKNOWN',
        'unknownReason', ARGV[2],
        'unknownAt', redis.call('TIME')[1])
redis.call('HDEL', KEYS[2], 'activeMessageId')
return 1
