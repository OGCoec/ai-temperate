if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('EXISTS', KEYS[2]) == 0 then
    return ''
end
local access = redis.call('HMGET', KEYS[1],
        'flowCsrfHash', 'deviceHash', 'ipHash', 'challengeHash')
if access[1] ~= ARGV[1] or access[2] ~= ARGV[2]
        or access[3] ~= ARGV[3] or access[4] ~= ARGV[4] then
    return ''
end
local values = redis.call('HMGET', KEYS[2], 'sendOperationId', 'deliveryStatus', 'unknownReason')
if values[1] ~= ARGV[5] or values[2] ~= 'UNKNOWN' then
    return ''
end
return values[3] or 'verification_delivery_outcome_unknown'
