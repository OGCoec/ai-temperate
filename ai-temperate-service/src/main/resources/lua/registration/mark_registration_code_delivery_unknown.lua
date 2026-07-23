if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('EXISTS', KEYS[2]) == 0 then
    return 0
end
local access = redis.call('HMGET', KEYS[1],
        'flowCsrfHash', 'deviceHash', 'ipHash', 'challengeHash')
if access[1] ~= ARGV[1] or access[2] ~= ARGV[2]
        or access[3] ~= ARGV[3] or access[4] ~= ARGV[4] then
    return 3
end
local values = redis.call('HMGET', KEYS[2], 'sendOperationId', 'deliveryStatus')
if values[1] ~= ARGV[5] then
    return 0
end
if values[2] == 'UNKNOWN' then
    return 1
end
if values[2] ~= 'PENDING' and values[2] ~= 'DELIVERING' then
    return 0
end
redis.call('HSET', KEYS[2],
        'deliveryStatus', 'UNKNOWN',
        'unknownReason', ARGV[6],
        'unknownAt', redis.call('TIME')[1])
redis.call('HDEL', KEYS[2], 'activeMessageId')
return 1
