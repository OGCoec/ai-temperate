if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('EXISTS', KEYS[2]) == 0 then
    return ''
end
local values = redis.call('HMGET', KEYS[2], 'operationId', 'deliveryStatus', 'unknownReason')
if values[1] ~= ARGV[1] or values[2] ~= 'UNKNOWN' then
    return ''
end
return values[3] or 'verification_delivery_outcome_unknown'
