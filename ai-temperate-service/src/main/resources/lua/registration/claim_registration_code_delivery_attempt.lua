if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('EXISTS', KEYS[2]) == 0 then
    return 0
end
local access = redis.call('HMGET', KEYS[1],
        'flowCsrfHash', 'deviceHash', 'ipHash', 'challengeHash')
if access[1] ~= ARGV[1] or access[2] ~= ARGV[2]
        or access[3] ~= ARGV[3] or access[4] ~= ARGV[4] then
    return 3
end
local values = redis.call('HMGET', KEYS[2],
        'sendOperationId', 'deliveryStatus', 'activeMessageId')
if values[1] ~= ARGV[5] then
    return 0
end
if values[2] == 'DELIVERING' and values[3] == ARGV[7] then
    return 1
end
if values[2] ~= 'PENDING' then
    return 0
end
redis.call('HSET', KEYS[2],
        'deliveryStatus', 'DELIVERING',
        'attempts', ARGV[6],
        'activeMessageId', ARGV[7])
return 1
