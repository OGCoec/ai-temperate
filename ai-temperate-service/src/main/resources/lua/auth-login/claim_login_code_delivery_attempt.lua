local operationId = ARGV[1]
local attemptNo = ARGV[2]
local messageId = ARGV[3]
if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('EXISTS', KEYS[2]) == 0 then
    return 0
end
local values = redis.call('HMGET', KEYS[2], 'operationId', 'deliveryStatus', 'activeMessageId')
if values[1] ~= operationId then
    return 0
end
if values[2] == 'DELIVERING' and values[3] == messageId then
    return 1
end
if values[2] ~= 'PENDING' then
    return 0
end
redis.call('HSET', KEYS[2],
        'deliveryStatus', 'DELIVERING',
        'attempts', attemptNo,
        'activeMessageId', messageId)
return 1
