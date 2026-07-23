if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('EXISTS', KEYS[2]) == 0 then
    return 0
end

local access = redis.call('HMGET', KEYS[1],
        'flowCsrfHash', 'deviceHash', 'ipHash', 'challengeHash')
if access[1] ~= ARGV[1]
        or access[2] ~= ARGV[2]
        or access[3] ~= ARGV[3]
        or access[4] ~= ARGV[4] then
    return 3
end

if redis.call('HGET', KEYS[1], ARGV[6]) == '1' then
    return 0
end

local operationId = redis.call('HGET', KEYS[2], 'sendOperationId')
local deliveryStatus = redis.call('HGET', KEYS[2], 'deliveryStatus')
if operationId == false or operationId ~= ARGV[5]
        or (deliveryStatus ~= 'PENDING' and deliveryStatus ~= 'DELIVERING') then
    return 0
end

redis.call('DEL', KEYS[2])
redis.call('HDEL', KEYS[3], ARGV[8])
return 1
