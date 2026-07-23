local operationId = ARGV[1]
local messageId = ARGV[2]
if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('EXISTS', KEYS[2]) == 0 then
    return 0
end
local values = redis.call('HMGET', KEYS[2], 'operationId', 'deliveryStatus', 'activeMessageId')
if values[1] ~= operationId or values[2] ~= 'DELIVERING' or values[3] ~= messageId then
    return 0
end
redis.call('HSET', KEYS[2], 'deliveryStatus', 'PENDING')
redis.call('HDEL', KEYS[2], 'activeMessageId')
return 1
